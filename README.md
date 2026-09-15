# Baritone (26.3 fork)

> **Work in progress.** This branch is under active development and has not been tested much. Things may break or
> change between commits. Use at your own risk.

Baritone is a Minecraft pathfinder bot. This repository is a fork of it ported to Minecraft 26.3,
built for Fabric and NeoForge. Forge source stays in the tree and is retargeted to 26.3, but
Minecraft Forge has not published a 26.3 loader yet so that jar is not built. It is standalone
Baritone, the pathfinding mod on its own, installable like any other mod. It does not include
or bundle Meteor Client.

Environment: Minecraft 26.3, Java 25, Mojang mappings. Built against Fabric loader 0.19.5 and
NeoForge 26.3.0.0-beta. The Fabric mod metadata requires Fabric loader 0.19.5 or newer and
Minecraft 26.3.

## Credits

Baritone is not my work. [leijurv](https://github.com/leijurv) and [Brady](https://github.com/bwhitty) created it,
with contributions from many others, and it is developed at
[cabaletta/baritone](https://github.com/cabaletta/baritone). The pathfinding, movement, mining, building and
command systems in this repository are theirs.

All this fork adds is the port to Minecraft 26.3 and the elytra changes listed under
[What's different in this fork](#whats-different-in-this-fork). It descends from
[cabaletta/baritone](https://github.com/cabaletta/baritone) by way of
[wagyourtail/baritone](https://github.com/wagyourtail/baritone) and
[MeteorDevelopment/baritone](https://github.com/MeteorDevelopment/baritone), and lives on the `26.3` branch.

Send your stars, sponsorship and thanks upstream to cabaletta/baritone.

## Download

Grab the latest build from the [Releases page](https://github.com/dysnasia/baritone-26.2/releases/latest) and drop
`baritone-fabric-26.3.jar` or `baritone-neoforge-26.3.jar` into your `mods` folder. Forge 26.3 is not published yet.

## What's different in this fork

- Ported to Minecraft 26.3 / Java 25 / Mojmap, for Fabric and NeoForge (Forge waits on a 26.3 loader).
- Elytra pathing now works reactively in the Overworld and the End, not only the Nether:
  - `VanillaElytraContext` and `ElytraTerrainProvider` add a pure-Java, chunk-data-backed pathfinder for the
    dimensions where the native, terrain-predicting Nether pathfinder is unavailable.
  - Sharp corners in generated routes get rounded off (Chaikin corner-cutting over the string-pulled path), so the
    flight solver stops overshooting and circling when it navigates around an obstacle.
- The elytra flight solver caches firework-entity lookups and block-state reads during the safe-landing-spot
  search, which cuts redundant per-tick work.

## Building

A full `./gradlew build` runs ProGuard during packaging, so it needs a JDK that ships `jmods`, such as
[Azul Zulu 25](https://www.azul.com/downloads/). Point Gradle at it with `org.gradle.java.home` in
`~/.gradle/gradle.properties`, or run:

```
JAVA_HOME=/path/to/zulu-25.jdk/Contents/Home ./gradlew build
```

To iterate locally without ProGuard's shrink, obfuscate and optimize pass, which gives you readable stack traces
and works with any JDK 25:

```
./gradlew :fabric:remapJar :neoforge:remapJar
```

Those jars land in `fabric/build/libs/` and `neoforge/build/libs/`. A full `./gradlew build` also writes the ProGuarded release jars to `dist/`.

`available_loaders` in `gradle.properties` controls which loaders get built. It is currently set to `fabric,neoforge`. When a 26.3 Forge artifact exists, add `forge` to `available_loaders` and set `forge_version` to that loader.

## Usage

The chat-command interface is the same as upstream Baritone. Type `#goto 1000 500` to path to x=1000 z=500,
`#mine diamond_ore` to mine diamond ore, `#stop` to stop, and `#elytra` to fly in the Nether, Overworld or End.
[USAGE.md](USAGE.md) and [FEATURES.md](FEATURES.md) have the full command and settings reference. Both are still
accurate here, since the pathing API surface has not changed, only the terrain backend used during elytra flight.

## API

Javadocs for the API live on the upstream project. The API jar does not support usage outside the `baritone.api`
package. Basic example:

```java
BaritoneAPI.getSettings().allowSprint.value = true;
BaritoneAPI.getSettings().primaryTimeoutMS.value = 2000L;

BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(10000, 20000));
```

## License

LGPL-3.0, the same license as upstream. [LICENSE](LICENSE) has the LGPL-3.0 text, [COPYING](COPYING) has the
GPL-3.0 text that the LGPL incorporates by reference, and [NOTICE](NOTICE) has the attribution and modification
notice.

Copyright for the original work belongs to the Baritone authors. This fork is a modified version of Baritone,
modified during 2025-2026 by [dysnasia](https://github.com/dysnasia). The section
[What's different in this fork](#whats-different-in-this-fork) describes the modifications. It is distributed
under the same LGPL-3.0 terms, with every original license header and author attribution left intact.

This project is not affiliated with, endorsed by, or sponsored by Meteor Development or Mojang.
