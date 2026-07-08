# Baritone (26.2 fork)

A Minecraft pathfinder bot, ported to **Minecraft 26.2** (Fabric, Java 25, Mojmap).

This is a personal fork of [MeteorDevelopment/baritone](https://github.com/MeteorDevelopment/baritone) (itself
built on the original [cabaletta/baritone](https://github.com/cabaletta/baritone)), living on the `26.2` branch.

## What's different in this fork

- Ported to Minecraft 26.2 / Java 25 / Mojmap (Fabric only for now; Forge/NeoForge pending Unimined support).
- Elytra pathing now works reactively in the **Overworld and End**, not just the Nether:
  - `VanillaElytraContext` / `ElytraTerrainProvider` add a pure-Java, chunk-data-backed pathfinder for dimensions
    where the native, terrain-predicting Nether pathfinder isn't available.
  - Sharp corners in generated routes are now rounded (Chaikin corner-cutting over the string-pulled path) so the
    flight solver doesn't overshoot and circle when navigating around an obstacle.
- Elytra flight solver optimizations: cached firework-entity lookups and cached block-state reads during
  safe-landing-spot search, to cut down on redundant per-tick work.

## Building

Requires a JDK with `jmods` available (e.g. [Azul Zulu 25](https://www.azul.com/downloads/)) if you want the full
`./gradlew build`, which runs ProGuard as part of packaging. Point Gradle at it via `org.gradle.java.home` in
`~/.gradle/gradle.properties`, or run:

```
JAVA_HOME=/path/to/zulu-25.jdk/Contents/Home ./gradlew build
```

For local iteration without ProGuard's shrink/obfuscate/optimize pass (readable stack traces, works with any
JDK 25):

```
./gradlew :fabric:remapJar
```

Output jars land in `fabric/build/libs/`.

## Usage

Same chat-command interface as upstream Baritone: type `#goto 1000 500` to path to x=1000 z=500, `#mine diamond_ore`
to mine diamond ore, `#stop` to stop, `#elytra` to fly (Nether, Overworld, or End). See [USAGE.md](USAGE.md) and
[FEATURES.md](FEATURES.md) for the full command/settings reference (still accurate for this fork - the pathing
API surface hasn't changed, only the terrain backend used during elytra flight).

## API

The API is documented via Javadocs on the upstream project; usage outside the `baritone.api` package isn't
supported by the API jar. Basic example:

```java
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().primaryTimeoutMS.value = 2000L;

BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

## License

LGPL-3.0, same as upstream - see [LICENSE](LICENSE).
