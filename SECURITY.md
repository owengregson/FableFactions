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

The shaded stack was **modernized across beta.3/beta.4** to current upstream releases: the connection
pool is **Apache Commons DBCP2 2.14.0**, the embedded database is **HSQLDB 2.7.4** (its
official `jdk8` build — pure Java-8 bytecode, current release), **mysql-connector-j 9.7.0**,
and **Adventure 5.x** (normalized per bytecode tier by the downgrade legs) — clearing the
advisories that had accrued against the old pinned lines and removing every
Java-8-compatibility version hold.

Bundled advisories are also **not reachable in FableFactions' usage**:

- The build **strips the advisory-bearing surfaces** before shipping (`core/build.gradle.kts`
  shade config): HSQLDB's servlet entry point and GUI/transfer tools, DBCP2's optional
  JTA-managed datasource + servlet cleaner, and the MySQL c3p0 integration are all
  excluded — the plugin links only the embedded/JDBC driver path.
- Everything is **relocated** under `dev.fablemc.factions.lib.*`, so it cannot collide with
  or be driven by another plugin's copy.
- The default backend is **embedded HSQLDB (local file)**; there is no exposed network database
  service. MySQL, when configured, is the operator's own trusted host.

No dependency is held back for Java-8 compatibility anymore (the remaining pins in
`.github/dependabot.yml` are normative compile levels, not version holds). If a genuinely
reachable advisory appears in the bundled stack, it is handled as a real vulnerability per
the process above.
