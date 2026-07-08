# Implementation wave plan

Fable designs; Opus agents implement per work order. File ownership is exclusive per agent
(CONTRACTS §6.9). Each wave's agents read the ACTUAL code landed by earlier waves.

| Wave | Agent | Owns | Depends on | Acceptance |
|---|---|---|---|---|
| W0 | scaffold | all Gradle files, gradle.properties, libs.versions.toml, support-matrix.json, plugin.yml, verify gates, scripts/tools/*, scripts/floor-symbols.txt, stub `FableFactionsPlugin` | — | `./gradlew build` GREEN end-to-end (mega-jar + all gates pass on the stub) |
| W1a | kernel | `:kernel` entire module (+ structure unit tests) | W0 | `./gradlew :kernel:test` green |
| W1b | platform | `:platform`, `:compat-folia`, `:compat-modern` | W0 (no `:kernel` dep — platform is kernel-free) | `:platform:compileJava` green |
| W2a | kernel-rules | `:kernel` reduce/ + rules/ + formula/property tests | W1a | `:kernel:test` green |
| W2b | pipeline | `:api`, core boot skeleton, pipeline/, journal/, storage/, text/Messages, session/SessionRegistry+PlayerSession skeleton | W1a, W1b | `:core:compileJava` green |
| W3a | listeners | core listen/ + DamageAttribution wiring | W2 | compiles |
| W3b | commands-player | core command framework + member/ claim/ travel/ bank/ power/ | W2 | compiles |
| W3c | commands-admin | relation/ role/ flagcmd/ chestcmd/ admin/ merge/ predefined/ + Completions | W2 | compiles |
| W3d | config-resources | config/ messages/ + ALL resource YAMLs (config, database, gui, roles, pre-defined, notifications, 8 locale files) | W2 | compiles; keys = parity inventory |
| W3e | sessions | session/ (TeleportSaga, CombatTags), chest/, gui/, power/PowerTicker, economy/ | W2 | compiles |
| W3f | integrations | integration/*, metrics/, update/, `:probe` | W2 | compiles |
| W4 | boot | rewrite boot/ (FableFactionsPlugin, FeatureReconciler, BootReport) wiring everything | W3 | `./gradlew build` green |
| W5 | fix loop + tests + review | error-driven; multi-agent review; fixes | W4 | build+tests green, review clean |
