# Building and releasing

## Requirements

- **JDK 21 or newer** to run Gradle. Gradle downloads a JDK 21 for its toolchain if needed.
- No Android SDK, Kotlin compiler or IDE is needed to build or run the CLI.

## Project layout

| File | What it does |
|---|---|
| `src/main/kotlin/…/cli/Main.kt` | Entry point: commands, path and glob expansion, image files, text or JSON output |
| `…/cli/Options.kt` | Command line parsing: options, sizes and the classpath |
| `…/cli/PreviewService.kt` | The core shared by the CLI and the MCP server: lists, checks and renders a file's previews |
| `…/cli/Workspace.kt` | What previews see around a file: other files, resources and library jars |
| `…/cli/Renderer.kt` | Off-screen rendering with Compose's `ImageComposeScene`, on a thread of its own |
| `…/cli/Report.kt` | Problems, previews and images, as text and JSON |
| `…/cli/McpServer.kt` | The MCP server: JSON-RPC over stdio and the three tools |
| `src/test/kotlin/…` | Tests for options, paths, reports, the service and the MCP server |
| `build.gradle.kts` | Targets, the bundled runtime, the icons shrinker and the start scripts |
| `.github/workflows/build.yml` | Tests, a zip per system, releases |

## Commands

| Command | Result |
|---|---|
| `./gradlew test` | Runs the tests; reports in `build/reports/tests/test/` |
| `./gradlew installDist` | The CLI in `build/install/fast-compose/` |
| `./gradlew distZip` | The release zip in `build/distributions/fast-compose-<version>-<target>.zip` |
| `./gradlew distTar` | The same as a tar |
| `./gradlew run --args="check path/to/File.kt"` | Runs the CLI without installing it |

After `installDist`, run `build/install/fast-compose/bin/fast-compose` (`fast-compose.bat` on Windows).

The tests render previews off-screen, so they need no display; they run on CI machines too.

## Distribution

```
fast-compose-0.3.0/
  bin/
    fast-compose          start script for macOS and Linux
    fast-compose.bat      start script for Windows
  lib/                    the CLI, the engine, Compose and their libraries
  runtime/                a trimmed Java 21 runtime
```

### Targets

Each build is for one system, which picks Skia's native library (Skiko). By default it's the machine's own; `-Ptarget`
picks another:

| Target | Skiko native library |
|---|---|
| `windows-x64`, `windows-arm64` | about 10 MB |
| `macos-x64`, `macos-arm64` | about 18 MB |
| `linux-x64`, `linux-arm64` | about 12 MB |

```bash
./gradlew distZip -Ptarget=linux-arm64
```

### Bundled runtime

`jlinkRuntime` builds a Java runtime with only the modules the CLI and common libraries need, from the toolchain's
JDK 21:

```
java.base, java.desktop, java.logging, java.management, jdk.unsupported, jdk.crypto.ec,
java.net.http, java.naming, jdk.zipfs
```

It's stripped of debug information, headers and man pages, and compressed: about 49 MB. The start scripts use
`runtime/` when it's there, before `JAVA_HOME` and the `PATH`.

jlink builds a runtime only for the machine it runs on, so a build with `-Ptarget` for another system leaves
`runtime/` out; that installation needs Java 21 on the machine it runs on.

### Shrunk icons

The Material icons library is 38 MB with thousands of icons. `shrinkIcons` copies it with only the icon classes the
engine's bindings use, found by scanning the engine's classes, and the icon classes those use. The distribution ships
the copy, under the original jar name; tests use the full jar.

The jar goes from about 37 MB to 1.5 MB, and the build logs how many icon classes it kept. Library code loaded with
`--classpath` that uses an icon the engine doesn't bind fails on that call.

### Start scripts

`startScripts` adjusts Gradle's scripts:

- The Windows script loads every jar in `lib` with a wildcard instead of listing them, since their full paths exceed
  Windows' command line length.
- Both scripts use the bundled runtime when there is one.

### Sizes

On Windows x64, an installation is about 102 MB: 49 MB for `runtime/` and 53 MB for `lib/`. On Linux and macOS,
`lib/` is 2–8 MB larger, from their Skiko libraries. The zips are about 15 MB smaller than the installations.

## Local engine

To build against a local checkout of the [engine](https://github.com/fast-compose/fast-compose) instead of the
version on Maven Central:

```bash
./gradlew installDist -PlocalEngine=../fast-compose
```

Or set it in `local.properties`, which IDE syncs read too:

```properties
localEngine=../fast-compose
```

## Versions

| | Where |
|---|---|
| The CLI | `cliVersion` in `local.properties` (not in git); CI writes it from the release tag. Without it the version is `dev` |
| The engine | `latest.release` in `gradle/libs.versions.toml`: the newest Fast Compose release on Maven Central |
| Compose, Material 3, coroutines, serialization | `gradle/libs.versions.toml` |

Every build takes the newest engine release. Gradle checks for a newer one once a day; `--refresh-dependencies`
checks now. To pin a version, set `fastCompose` to it, e.g. `"0.3.0"`.

`fast-compose version` prints the CLI's version, from the jar's manifest, with the engine and Compose versions it was
built with, from the engine's `FastComposeInfo`:

```
Fast Compose CLI 0.3.0 (Fast Compose 0.3.0, Jetpack Compose 1.12.2)
```

## CI and releases

| Event | What runs |
|---|---|
| Pull request to `main` | Build and tests on Linux |
| Tag `v*` | Build and tests, then a zip per system, each built on that system, then a GitHub release with the six zips |

Pushes and merges to `main` run nothing.

| Target | Runner |
|---|---|
| `windows-x64` | `windows-latest` |
| `windows-arm64` | `windows-11-arm` |
| `macos-x64` | `macos-15-intel` |
| `macos-arm64` | `macos-latest` |
| `linux-x64` | `ubuntu-latest` |
| `linux-arm64` | `ubuntu-24.04-arm` |

Each packaging job fails if its zip has no `runtime/bin/java`, since that means it was built on the wrong system.

To release, push a tag: `git tag v0.3.0 && git push origin v0.3.0`. Each packaging job writes `cliVersion=0.3.0` to
`local.properties` before building, so the zips and `fast-compose version` show the tag's version. The release is
named after the version, with notes generated from the merged pull requests.

For a local build with a version, add it to `local.properties`:

```properties
cliVersion=0.3.0
```
