# 11. Toolchain upgrade: AGP 9.3, Gradle 9.7, JDK 21

Status: ready-for-agent
Type: task
Blocked by: None
Execution: Not started

## Scope and completion criteria

The issue 01 pins (AGP 8.13.2, Gradle 8.14.5, JDK 17) were an implementer choice, not an agreed design decision. Their reason ("AGP 9 is a breaking migration") does not apply to a greenfield project. They caused three workarounds: R8 too old for Kotlin 2.4 (ADR-0003 item 9), Robolectric at SDK 35, and jOOQ 3.19 (ADR-0004).

Upgrade to JDK 21, AGP 9.3.3 (built-in Kotlin, no `kotlin-android` plugin), Gradle 9.7.0 in every build, jOOQ 3.21.9, Robolectric at SDK 36. Keep compileSdk 36 and the other pins. Update `TOOLCHAIN.md`, `gradle/libs.versions.toml`, wrappers, `scripts/setup-toolchain.sh`, CI, ADR-0003 item 9, and ADR-0004.

Done when SDK tests, server tests with `verifyJooq`, and `scripts/verify-contract.sh` pass on the new pins, with commands and results recorded here.

## Comments

2026-10-11: Created after the user approved the upgrade.
