# Item Tracker

Fabric client mod for Minecraft Java 1.21.11, by JPMods.

Install the release JAR together with Fabric API and Mod Menu for 1.21.11.
Open **Mods > Item Tracker > Configure** to change either tracker's settings.
Changes apply when you press Done or Escape. Each tracker has its own enable,
range, HUD position, scale, names, rows, text colour and background options.

- PotionTracker counts other players' Healing II splash potion throws, with an
  option to include lingering Healing II. It ignores your own throws.
- DiaSMPTracker counts other players' ender pearl throws and estimates cobweb use.
  It retains support for the original optional confirmed-placement server
  protocol. Client-only cobweb ownership is an estimate; newly discovered
  arena webs may be attributed to the sole nearby opponent when enabled.
- Both reset on your death, local respawn, world change and disconnect.
  An opponent's death resets that opponent's counters. A new player entity
  starts a fresh observed life, including fast respawns and rejoining range.
  Damage and totem pops do not reset the counters.
- Open **Options > Controls > Key Binds > Item Tracker** to assign **Reset
  PotionTracker** and **Reset DiaSMPTracker**. Both start unbound. DiaSMPTracker
  reset clears both pearls and cobwebs. No chat commands are installed.

The same Controls category also contains **Toggle PotionTracker** and **Toggle
DiaSMPTracker**, initially unbound. Each enables or disables only its own
tracker and saves that state to the configuration. Re-enabling respects the
tracker's Reset on enable setting.

Counters only cover events the server sends while tracking. No client can
identify a death or projectile the server never reports. Preferences are
stored in `config/itemtracker.json`; counters are not saved across sessions.

Build with Java 21 and `./gradlew build`. The compiled release is
`build/libs/itemtracker-fabric-mc1.21.11-0.0.1.jar`. Tests are in `src/testmod`,
outside the release JAR; see `TESTING.md` for verification results.

GPL-3.0-or-later. Corresponding source and license are included in the source
archive. No dependency on the original combined client is required.
