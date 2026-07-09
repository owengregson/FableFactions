# Security Policy

## Supported versions

Only the **latest release** of FableFactions receives security fixes. If you are on an
older build, update before reporting.

## Reporting a vulnerability

Report vulnerabilities **privately** via GitHub Security Advisories:
[owengregson/FableFactions — report a vulnerability](https://github.com/owengregson/FableFactions/security/advisories/new).

Do **not** open a public issue for exploitable bugs — anything that lets players
duplicate items or money, bypass territory protection, escalate faction roles or
permissions, corrupt or exfiltrate stored data, or crash the server on demand. A public
report puts every server running the plugin at risk before a fix exists.

Include what you would in a good bug report: server platform and exact version, plugin
version, storage backend, and reproduction steps. You will get a response in the
advisory thread, and credit in the release notes for the fix unless you ask otherwise.

Non-exploitable bugs go through the normal
[issue tracker](https://github.com/owengregson/FableFactions/issues).

## A note on the bundled-dependency advisories

Dependabot reports advisories against FableFactions' **shaded storage stack** — HikariCP
4.0.3, H2 1.4.200, and mysql-connector-j. These versions are **pinned on purpose**
(`docs/ARCHITECTURE.md` AM-10): they are the last lines that stay loadable on the Java-8
base bytecode tier the one-jar architecture targets (1.7.10 → modern). They are *not*
freely upgradable — a newer line breaks the Java-8 tier — so Dependabot is configured to
hold them (`.github/dependabot.yml`), and the alerts persist by design.

They are also **not reachable in FableFactions' usage**:

- The build **strips the advisory-bearing surfaces** before shipping (`core/build.gradle.kts`
  shade config): the H2 web console / TCP-PG servers and tools, the OSGi activators, the
  Hikari Prometheus/Hibernate/Javassist extras, and the MySQL c3p0 integration are all
  excluded — the plugin links only the embedded/JDBC driver path.
- Everything is **relocated** under `dev.fablemc.factions.lib.*`, so it cannot collide with
  or be driven by another plugin's copy.
- The default backend is **embedded H2 (local file)**; there is no exposed network database
  service. MySQL, when configured, is the operator's own trusted host.

If a genuinely reachable advisory in this stack appears, it is handled as a real
vulnerability per the process above, and the pin is re-evaluated (see
`docs/design/per-version-dependencies.md` for the upgrade constraints).
