package dev.fablemc.factions.core.economy;

import java.lang.reflect.Method;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;

/**
 * The reflection-only bridge to Vault's {@code Economy} service (proposal-C §7, integration
 * boundary). It never imports {@code net.milkbowl}: the {@code Economy} class is resolved through
 * {@code Class.forName} and every call reflects through {@link Method} handles, so a server without
 * Vault links nothing and simply reports {@link #present()} as {@code false}. The provider is
 * resolved <b>per call</b> from the {@link ServicesManager} to honour late-registering economy
 * plugins (EzEconomy); the reflective method handles (which depend only on the stable {@code
 * Economy} interface) are resolved once and cached.
 *
 * <p>Only the {@code OfflinePlayer} overloads are used (a {@link Player} is an {@link OfflinePlayer}),
 * so both online sagas and offline reconciliation share one path. Every operation fails closed:
 * any reflective/linkage error yields {@code false}/{@code 0.0} rather than propagating.
 *
 * <p>This is the concrete implementation of W3b's {@code command/VaultBridge} seam — it exposes the
 * {@code present()} / {@code withdraw(Player,double)} / {@code deposit(Player,double)} signatures the
 * bridge declares (the integrator adds {@code implements VaultBridge} once that interface lands).
 *
 * <p><b>Owning thread(s):</b> the wallet mutations run on the target player's region thread (the
 * escrow executor / teleport saga marshal there); resolution is thread-safe. <b>Mutability:</b> the
 * cached method handles are written once under the instance monitor; provider lookup is stateless.
 */
public final class VaultAdapter {

    private static final String ECONOMY_CLASS = "net.milkbowl.vault.economy.Economy";

    private final Object methodLock = new Object();

    private volatile boolean resolvedMethods;
    private volatile Class<?> economyClass;
    private volatile Method mIsEnabled;
    private volatile Method mHas;
    private volatile Method mWithdraw;
    private volatile Method mDeposit;
    private volatile Method mGetBalance;
    private volatile Method mTransactionSuccess;

    /** Whether a Vault economy provider is currently registered and its wallet methods resolved. */
    public boolean present() {
        return provider() != null && mWithdraw != null && mDeposit != null;
    }

    /** Whether the resolved provider reports itself enabled (Vault + backing economy both up). */
    public boolean enabled() {
        Object provider = provider();
        if (provider == null || mIsEnabled == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(mIsEnabled.invoke(provider));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Withdraws {@code amount} from {@code player}'s wallet; {@code true} on a successful transaction. */
    public boolean withdraw(Player player, double amount) {
        return withdraw((OfflinePlayer) player, amount);
    }

    /** Deposits {@code amount} into {@code player}'s wallet; {@code true} on a successful transaction. */
    public boolean deposit(Player player, double amount) {
        return deposit((OfflinePlayer) player, amount);
    }

    /** Offline-safe deposit by UUID (escrow reconciliation for a player who left mid-saga). */
    public boolean depositOffline(UUID playerId, double amount) {
        return deposit(Bukkit.getOfflinePlayer(playerId), amount);
    }

    /** Offline-safe withdraw by UUID. */
    public boolean withdrawOffline(UUID playerId, double amount) {
        return withdraw(Bukkit.getOfflinePlayer(playerId), amount);
    }

    /** {@code player}'s wallet balance, or {@code 0.0} when no provider/resolution is available. */
    public double balance(Player player) {
        Object provider = provider();
        if (provider == null || mGetBalance == null) {
            return 0.0;
        }
        try {
            Object result = mGetBalance.invoke(provider, player);
            return result instanceof Number number ? number.doubleValue() : 0.0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return 0.0;
        }
    }

    /** Whether {@code player} can afford {@code amount} (native {@code has}, else a balance check). */
    public boolean has(Player player, double amount) {
        Object provider = provider();
        if (provider == null) {
            return false;
        }
        if (mHas != null) {
            try {
                return Boolean.TRUE.equals(mHas.invoke(provider, player, amount));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                // fall through to a balance comparison
            }
        }
        return balance(player) >= amount;
    }

    // ── internals ──────────────────────────────────────────────────────────────────────────

    private boolean withdraw(OfflinePlayer player, double amount) {
        Object provider = provider();
        if (provider == null || mWithdraw == null || mTransactionSuccess == null) {
            return false;
        }
        try {
            Object response = mWithdraw.invoke(provider, player, amount);
            return response != null && Boolean.TRUE.equals(mTransactionSuccess.invoke(response));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    private boolean deposit(OfflinePlayer player, double amount) {
        Object provider = provider();
        if (provider == null || mDeposit == null || mTransactionSuccess == null) {
            return false;
        }
        try {
            Object response = mDeposit.invoke(provider, player, amount);
            return response != null && Boolean.TRUE.equals(mTransactionSuccess.invoke(response));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** Resolves the registered {@code Economy} provider fresh each call (late-registration safe). */
    private Object provider() {
        try {
            Class<?> economy = economyClass();
            if (economy == null) {
                return null;
            }
            ServicesManager services = Bukkit.getServicesManager();
            if (services == null) {
                return null;
            }
            RegisteredServiceProvider<?> registration = services.getRegistration(economy);
            return registration == null ? null : registration.getProvider();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /** The {@code Economy} interface class (loaded + methods cached once), or {@code null} if absent. */
    private Class<?> economyClass() {
        if (resolvedMethods) {
            return economyClass;
        }
        synchronized (methodLock) {
            if (resolvedMethods) {
                return economyClass;
            }
            try {
                Class<?> economy = Class.forName(ECONOMY_CLASS);
                mIsEnabled = optionalMethod(economy, "isEnabled");
                mHas = optionalMethod(economy, "has", OfflinePlayer.class, double.class);
                mWithdraw = optionalMethod(economy, "withdrawPlayer", OfflinePlayer.class, double.class);
                mDeposit = optionalMethod(economy, "depositPlayer", OfflinePlayer.class, double.class);
                mGetBalance = optionalMethod(economy, "getBalance", OfflinePlayer.class);
                mTransactionSuccess = mWithdraw == null
                        ? null
                        : optionalMethod(mWithdraw.getReturnType(), "transactionSuccess");
                economyClass = economy;
            } catch (ClassNotFoundException | LinkageError | RuntimeException absent) {
                economyClass = null;
            }
            resolvedMethods = true;
            return economyClass;
        }
    }

    private static Method optionalMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (NoSuchMethodException | LinkageError | RuntimeException absent) {
            return null;
        }
    }
}
