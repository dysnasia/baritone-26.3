# Installation

The easiest way to install Baritone on this 26.3 branch is to drop the Fabric jar into `mods`. Forge and NeoForge are not built for 26.3 yet. If you know how you can also use it with a custom `version.json`
(Examples: [1.14.4](https://www.dropbox.com/s/rkml3hjokd3qv0m/1.14.4-Baritone.zip?dl=1), [1.15.2](https://www.dropbox.com/s/8rx6f0kts9hvd4f/1.15.2-Baritone.zip?dl=1), [1.16.5](https://www.dropbox.com/s/i6f292o2i7o9acp/1.16.5-Baritone.zip?dl=1)).

Once Baritone is installed, look [here](USAGE.md) for instructions on how to use it.

## Prebuilt releases

This fork: [Releases](https://github.com/dysnasia/baritone-26.2/releases). Drop `baritone-fabric-26.3.jar` into `mods` (Fabric only for 26.3).

This branch is Minecraft **26.3** / Baritone **26.3**, Fabric only. Upstream's historical mapping (not this fork):

| Minecraft version | 1.12 | 1.13 | 1.14 | 1.15 | 1.16 | 1.17 | 1.18 | 1.19 | 1.20  | 1.21  | 1.21.4 | 1.21.5 |  1.21.6 - 1.21.8 |
|-------------------|------|------|------|------|------|------|------|------|-------|-------|--------|--------|------------------|
| Baritone version  | v1.2 | v1.3 | v1.4 | v1.5 | v1.6 | v1.7 | v1.8 | v1.9 | v1.10 | v1.11 | v1.13  | v1.14  | v1.15            |

A smoke build in Docker (JDK 25):

```
docker build --no-cache -t dysnasia/baritone-26.3 .
```

That image is for compiling, not a bit-for-bit match of GitHub release jars.


## Artifacts

Building Baritone will create the final artifacts in the ``dist`` directory. These are the same as the artifacts created in the [releases](https://github.com/dysnasia/baritone-26.2/releases).

**The Fabric release can simply be added as a Fabric mod.** Forge and NeoForge are not built for 26.3 yet.

`dist` holds one jar per enabled loader, `baritone-LOADER-VERSION.jar`. That is the API build, so it is both the
jar you install and the one another mod can integrate against.

The standalone and unoptimized builds are not copied to `dist`; look in `LOADER/build/libs` if you want them.
- **API** (built as `LOADER/build/libs/baritone-api-LOADER-VERSION.jar`, copied to `dist` as `baritone-LOADER-VERSION.jar`): Only the non-api packages are obfuscated. This should be used in environments where other mods would like to use Baritone's features.
- **Standalone** (`LOADER/build/libs/baritone-standalone-LOADER-VERSION.jar`): Everything is obfuscated. Other mods cannot use Baritone, but you get a bit of extra performance.
- **Unoptimized** (`LOADER/build/libs/baritone-unoptimized-LOADER-VERSION.jar`): Nothing is obfuscated. This shouldn't be used in production, but is really helpful for crash reports.

- **Fabric**: Loadable as a standard Fabric mod. The fabric build may or may not work on Quilt.

If you build from source you will also find mapping files in the `mapping` directory. These contain the renamings done by ProGuard and are useful if you want to read obfuscated stack traces.

## Build it yourself
- Clone or download Baritone

  ![Image](https://i.imgur.com/kbqBtoN.png)
  - If you choose to download, make sure you download the correct branch and extract the ZIP archive.
- Follow one of the instruction sets below, based on your preference

## Command Line
On Mac OSX and Linux, use `./gradlew` instead of `gradlew`.

The recommended Java versions by Minecraft version are
| Minecraft version             | Java version  |
|-------------------------------|---------------|
| 1.12.2 - 1.16.5               | 8             |
| 1.17.1                        | 16            |
| 1.18.2 - 1.20.4               | 17            |
| 1.20.5 - 1.21.8               | 21            |
| 26.2                          | 25            |
| 26.3                          | 25            |

Download java: https://adoptium.net/

To check which java version you are using do `java -version` in a command prompt or terminal.

### Building Baritone

These tasks depend on the minecraft version, but are (for the most part) standard for building mods.

For more details, see [the build ci action](/.github/workflows/gradle_build.yml) of the branch you want to build.

For most branches `gradlew build` should build everything, but there are exceptions and this file might be out of date.

More specifically, on older branches the setup used to be that `gradlew build` builds the tweaker jar
and `gradlew build -Pbaritone.forge_build` / `gradlew build -Pbaritone.fabric_build` are needed to build
for Forge/Fabric instead. And you might have to run `setupDecompWorkspace` first.

## IntelliJ
- Open the project in IntelliJ as a Gradle project
- Refresh the Gradle project (or, to be safe, just restart IntelliJ)
- Depending on the minecraft version, you may need to run `setupDecompWorkspace` or `genIntellijRuns` in order to get everything working

## Github Actions
If this repository has a CI workflow at `.github/workflows/gradle_build.yml` and Actions are enabled, a push will build the jars. Do not download artifacts from [cabaletta/baritone's Actions](https://github.com/cabaletta/baritone/actions/workflows/gradle_build.yml) for this 26.3 fork — those are a different Minecraft version.
