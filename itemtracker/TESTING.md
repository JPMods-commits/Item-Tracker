# Verification — 3 November 2026

The production-remapped release JAR was compiled for Java 21 and loaded in
Minecraft 1.21.11 with Fabric Loader 0.18.4, Fabric API 0.141.6+1.21.11 and
Mod Menu 17.0.1. The original combined client was absent.

The separate client integration test mod finished with exit code 0 and the
marker `ITEMTRACKER_INTEGRATION_TEST_PASSED`. It exercised real vanilla packet
handlers and real key events for:

- Both Mod Menu settings tabs, saving configuration, and the item HUDs.
- Healing II splash and lingering potions, excluding Healing I and own throws.
- Ender pearls and client cobweb estimates, including duplicate updates.
- Opponent death events while remote visible health remains positive.
- Zero-health metadata and clearing only the deceased opponent's counters.
- Delayed potion item data after death and ignoring throws while locally dead.
- Local death and revival, immediate remote player replacement, and local
  respawn packets without a preceding death notification.
- Keeping counters through ordinary damage and totem pops.
- Four initially unbound Controls entries: separate reset and toggle keys for
  each tracker.
- Independently disabling/re-enabling both trackers with real key events,
  ignoring throws while disabled, respecting Reset on enable, and persisting
  the enabled state to the configuration file.
- Resetting pearls and cobwebs together, and clearing counters on disconnect.

The settings tabs and HUD screenshots were visually inspected. Tests are
excluded from the release JAR. With Java 21, use `./gradlew runClientGameTest`
to run the included tests, and `./gradlew build` to build the mod.

This verifies the client logic and packet hooks. It does not test a live
DiaSMP server or install the optional server companion. Cobweb attribution
retains the extracted feature's client estimation limits.
