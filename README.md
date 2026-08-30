# Baritone (26.2 fork)

> **Work in progress.** This branch is under active development and has not been tested much. Things may break or
> change between commits. Use at your own risk.

Baritone is a Minecraft pathfinder bot. This repository is a fork of it ported to Minecraft 26.2, built for
Fabric, Forge and NeoForge. It is standalone Baritone, the pathfinding mod on its own, installable like any other
mod. It does not include or bundle Meteor Client.

Environment: Minecraft 26.2, Fabric loader 0.18.6, Forge 65.1.3 or NeoForge 26.2.0.72, Java 25, Mojang mappings.

## Credits

Baritone is not my work. [leijurv](https://github.com/leijurv) and [Brady](https://github.com/bwhitty) created it,
with contributions from many others, and it is developed at
[cabaletta/baritone](https://github.com/cabaletta/baritone). The pathfinding, movement, mining, building and
command systems in this repository are theirs.

All this fork adds is the port to Minecraft 26.2 and the elytra changes listed under
[What's different in this fork](#whats-different-in-this-fork). It descends from
[cabaletta/baritone](https://github.com/cabaletta/baritone) by way of
[wagyourtail/baritone](https://github.com/wagyourtail/baritone) and
[MeteorDevelopment/baritone](https://github.com/MeteorDevelopment/baritone), and lives on the `26.2` branch.

Send your stars, sponsorship and thanks upstream to cabaletta/baritone. If you want supported, actively
maintained Baritone, use upstream instead of this fork.

## Download

Grab the latest build from the [Releases page](https://github.com/dysnasia/baritone-26.2/releases/latest) and drop
it into your `mods` folder: `baritone-fabric-26.2.jar` for Fabric (alongside Fabric API),
`baritone-forge-26.2.jar` for Forge, or `baritone-neoforge-26.2.jar` for NeoForge.

## Fixes on this branch

- Pathing visuals and path lines
- Elytra flight (details below)

## What's different in this fork

- Ported to Minecraft 26.2 / Java 25 / Mojmap, for Fabric, Forge and NeoForge.
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
./gradlew :fabric:remapJar
./gradlew :forge:remapJar
./gradlew :neoforge:remapJar
```

Those jars land in `fabric/build/libs/`, `forge/build/libs/` and `neoforge/build/libs/`. A full `./gradlew build`
also writes one ProGuarded release jar per enabled loader to `dist/`.

`available_loaders` in `gradle.properties` controls which loaders get built. It is currently set to
`fabric,forge,neoforge`.

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
