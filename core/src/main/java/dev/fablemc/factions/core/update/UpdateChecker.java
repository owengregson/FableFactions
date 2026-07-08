package dev.fablemc.factions.core.update;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.plugin.Plugin;

import dev.fablemc.factions.kernel.config.ConfigImage;
import dev.fablemc.factions.platform.sched.Scheduling;

/**
 * The async update checker (proposal-C §10.3): Modrinth primary, GitHub fallback. It fetches the
 * latest published version off-thread and publishes an {@link UpdateStatus} that the session listener
 * reads to post an op-join notice ({@link #status()} is the supplier hook point). Everything targets
 * the FableFactions project ({@code fablefactions} / {@code fablemc/FableFactions}), never the
 * reference project (CONTRACTS §7.7).
 *
 * <p><b>Owning thread(s):</b> {@link #start} on the boot thread; {@link #check} on the async thread
 * {@link Scheduling#runAsync} routes to (HTTP IO only, no Bukkit). The published {@link UpdateStatus}
 * is read on any thread via a volatile field. <b>Mutability:</b> the status reference is the only
 * mutable field.
 */
public final class UpdateChecker {

    private static final String USER_AGENT = "FableFactions-UpdateChecker";
    private static final int TIMEOUT_MS = 5_000;

    private final Plugin plugin;
    private final Logger logger;
    private final Scheduling scheduling;
    private final ConfigImage.Updates config;
    private final String currentVersion;

    private volatile UpdateStatus status;

    /** Constructor injection: the plugin (for version), the scheduler, and the update config. */
    public UpdateChecker(Plugin plugin, Scheduling scheduling, ConfigImage.Updates config) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.scheduling = scheduling;
        this.config = config;
        this.currentVersion = plugin.getDescription().getVersion();
    }

    /** Schedules the async check when updates are enabled; a no-op otherwise. */
    public void start() {
        if (!config.enabled()) {
            return;
        }
        scheduling.runAsync(this::check);
    }

    /** The latest published status, or {@code null} until the first check completes. */
    public UpdateStatus status() {
        return status;
    }

    /** {@code true} when a newer version was found. */
    public boolean updateAvailable() {
        UpdateStatus current = status;
        return current != null && current.available();
    }

    /** Whether ops should be notified on join (config); the listener pairs this with {@link #status()}. */
    public boolean notifyOpsOnJoin() {
        return config.notifyOpsOnJoin();
    }

    /** The status as an {@link Optional} (the supplier form the session listener may hold). */
    public Optional<UpdateStatus> latest() {
        return Optional.ofNullable(status);
    }

    // ── the async check ────────────────────────────────────────────────────────────────────

    /** Fetches Modrinth (primary) then GitHub (fallback), publishing the resulting status. */
    void check() {
        Resolved resolved = fetchModrinth();
        if (resolved == null) {
            resolved = fetchGitHub();
        }
        if (resolved == null) {
            return;   // both endpoints unreachable — leave the last status untouched
        }
        boolean available = isNewer(resolved.version(), currentVersion);
        status = new UpdateStatus(available, currentVersion, resolved.version(),
                resolved.downloadUrl(), resolved.source());
        if (available) {
            logger.info("A FableFactions update is available: " + currentVersion + " -> "
                    + resolved.version() + " (" + resolved.downloadUrl() + ")");
        }
    }

    private Resolved fetchModrinth() {
        String body = httpGet("https://api.modrinth.com/v2/project/" + config.modrinthSlug() + "/version");
        String version = firstJsonString(body, "version_number");
        if (version == null) {
            return null;
        }
        return new Resolved(version, "https://modrinth.com/plugin/" + config.modrinthSlug(), "Modrinth");
    }

    private Resolved fetchGitHub() {
        String body = httpGet("https://api.github.com/repos/" + config.githubOwner() + "/"
                + config.githubRepo() + "/releases/latest");
        String tag = firstJsonString(body, "tag_name");
        if (tag == null) {
            return null;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        return new Resolved(version, "https://github.com/" + config.githubOwner() + "/"
                + config.githubRepo() + "/releases/latest", "GitHub");
    }

    /** Fetches the response body for a GET, or {@code null} on any non-200 / IO error. */
    private String httpGet(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = connection.getInputStream()) {
                return readAll(in);
            }
        } catch (IOException | RuntimeException failed) {
            logger.log(Level.FINE, "update check request failed for " + url, failed);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Extracts the first {@code "field":"value"} string value from {@code json} by hand (no JSON
     * dependency on the update path); returns {@code null} when absent.
     */
    static String firstJsonString(String json, String field) {
        if (json == null) {
            return null;
        }
        String needle = "\"" + field + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return null;
        }
        int close = json.indexOf('"', open + 1);
        if (close < 0) {
            return null;
        }
        return json.substring(open + 1, close);
    }

    /** {@code true} when {@code candidate}'s numeric version segments strictly exceed {@code current}'s. */
    static boolean isNewer(String candidate, String current) {
        List<Integer> a = numericSegments(candidate);
        List<Integer> b = numericSegments(current);
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            int av = i < a.size() ? a.get(i) : 0;
            int bv = i < b.size() ? b.get(i) : 0;
            if (av != bv) {
                return av > bv;
            }
        }
        return false;
    }

    /** Splits a version string into its runs of digits as integers (e.g. {@code 1.0.0-beta.2 → [1,0,0,2]}). */
    private static List<Integer> numericSegments(String version) {
        List<Integer> segments = new ArrayList<>();
        int i = 0;
        int n = version.length();
        while (i < n) {
            if (Character.isDigit(version.charAt(i))) {
                int start = i;
                while (i < n && Character.isDigit(version.charAt(i))) {
                    i++;
                }
                try {
                    segments.add(Integer.parseInt(version.substring(start, i)));
                } catch (NumberFormatException overflow) {
                    segments.add(Integer.MAX_VALUE);
                }
            } else {
                i++;
            }
        }
        return segments;
    }

    /** A resolved latest version with its download page and source label. */
    private record Resolved(String version, String downloadUrl, String source) {
    }
}
