# Commands and options

```
fast-compose <command> [paths] [options]
```

Paths and options can come in any order. Options take their value as `--name value` or `--name=value`.

## Commands

| Command | What it does |
|---|---|
| `check [paths]` | Runs every preview off-screen and reports parse errors, unknown names, binding mismatches and runtime errors |
| `render [paths]` | Does what `check` does and writes a PNG per preview |
| `list [paths]` | Lists the previews with their settings, without running them |
| `mcp` | Runs the [MCP server](MCP.md) over stdin and stdout |
| `version` (`-v`, `--version`) | Prints the CLI's version with the Fast Compose engine and Compose versions it runs |
| `help` (`-h`, `--help`) | Prints the version, commands and options; also what `fast-compose` prints without arguments |

### check

```bash
fast-compose check app/src/commonMain/kotlin/app/ui/HomeScreen.kt
```

```
app/src/commonMain/kotlin/app/ui/HomeScreen.kt
  warning  Theme.kt:60  'SyntaxColors' (io.nemo.model) has no binding; a stand-in is used, so the preview may differ  [HomeLight]
  error    Toolbar.kt:100  Unresolved reference 'Expanded'. Did you mean 'expandIn'?  [HomeDesktop]
1 file(s), 1 error(s), 1 warning(s)
```

Each preview runs once, at its size, and the problems of all of them are listed together. A problem hit by several
previews is listed once, tagged with the first preview that hit it. Problems can be in other files the preview uses,
such as `Theme.kt` above.

What it finds:

| Kind | Example |
|---|---|
| Parse errors | `Expected ')'` |
| Unknown names | `Unresolved reference 'Txt'. Did you mean 'Text'?` |
| Names imported from the project that no file declares | `Unresolved reference 'Card' (imported from app.ui)` |
| Binding mismatches | `Unknown parameter 'indication' for combinedClickable` |
| Library calls without a binding, run as stand-ins | `'EditorTheme' (io.nemo.model) has no binding; a stand-in is used, …` |
| Runtime errors | `Index 3 out of bounds for length 3` |
| Invalid `@Preview` arguments | `@Preview widthDp: …` |
| Crashes while composing, measuring or drawing | `Preview crashed: IllegalStateException: …` |
| Files that don't finish loading in 20 seconds | `Timed out loading the files the preview uses` |

A file with parse errors isn't run: only its parse errors are reported.

### render

```bash
fast-compose render app/src/commonMain/kotlin/app/ui -o previews
```

```
app/src/commonMain/kotlin/app/ui/HomeScreen.kt
  preview  Light (HomePreview)  line 42  →  previews/HomeScreen-Light.png (822 × 1782 px)
  preview  Dark (HomePreview)  line 43  ·  dark  →  previews/HomeScreen-Dark.png (822 × 1782 px)
1 file(s), 0 error(s), 0 warning(s)
```

- Images are named `<File>-<Preview>.png`, from the file name without `.kt` and the preview's name. Characters other
  than letters, digits, `-`, `_` and `.` become `_`. Names that repeat in one run get `-2`, `-3`, …
- Existing images with the same name are overwritten. The output folder is created when needed.
- A preview with `widthDp` and `heightDp` is rendered at that size.
- A preview without them is laid out in a window of `--size` (a phone by default) and wraps its content: the image is
  cropped to what the preview draws, so a button gives a button-sized image and a `Scaffold` fills the window.
- Without `showBackground` or a dark theme, the background is transparent.
- A preview that crashes gives no image; its crash is reported as an error.

### list

```bash
fast-compose list -r app/src
```

```
app/src/commonMain/kotlin/app/ui/HomeScreen.kt
  preview  Light (HomePreview)  line 42
  preview  Tablet (HomePreview)  line 44  ·  1280 × 800 dp
  preview  Arabic (HomePreview)  line 45  ·  ar
1 file(s), 0 error(s), 0 warning(s)
```

`list` reads each preview's `@Preview` arguments but runs nothing else, so it's fast. It reports parse errors, names
imported from the project that no file declares, and invalid `@Preview` arguments.

## Paths

| Path | Files taken |
|---|---|
| `HomeScreen.kt` | That file, whether or not it has previews |
| `ui` (a folder) | The `.kt` files in that folder with `@Preview` functions, not those in subfolders |
| `-r ui` | The `.kt` files with `@Preview` functions in that folder and all its subfolders |
| No path, `.` or `*` | The current folder, as for a folder |
| `ui/*.kt` | Files in `ui` matching the glob, with `@Preview` functions |
| `ui/*/*.kt` | Files one folder below `ui` |
| `ui/**/*Screen.kt` | Files at any depth below `ui` whose names end with `Screen.kt` |
| `Card?.kt` | `?` matches one character |

- Several paths can be given; a file reached twice is used once.
- Folders named `build`, and folders whose names start with `.`, are skipped when searching.
- A file that isn't `.kt` is ignored, so `*` expanded by a shell is safe.
- Globs work in every shell: in `cmd.exe`, PowerShell and quoted arguments the CLI expands them itself. Quote globs
  with `**` in bash and zsh so the shell doesn't expand them first: `"ui/**/*Screen.kt"`.
- A path that doesn't exist, and isn't a glob, is a command line error (exit code 2).
- When nothing matches, the CLI prints `No Kotlin files with @Preview functions found` to stderr and exits with 0.

A file is said to have previews when it has a `@Preview` annotation, written `@Preview` or fully qualified. Comments,
strings and imports don't count. Only `@Composable` functions with `@Preview` are previews; a function with several
`@Preview` annotations gives one preview per annotation.

## Options

### Choosing files and previews

| Option | Effect |
|---|---|
| `-r`, `--recursive` | Folders, `*` and the current folder are searched with their subfolders |
| `-p`, `--preview NAME` | Only previews whose `name` or function name is `NAME` |

```bash
fast-compose render HomeScreen.kt -p Dark
fast-compose render HomeScreen.kt --preview HomePreview
```

### Output

| Option | Effect |
|---|---|
| `-o`, `--output DIR` | Folder for `render`'s images; default: the current folder |
| `--json` | Prints [JSON](OUTPUT.md#json) instead of text; nothing else goes to stdout |
| `--ignore-warnings` | Leaves warnings out of the output and the totals; only errors are reported |
| `-q`, `--quiet` | Leaves out problems, `println` output and messages; the totals and the exit code still count them |

`--quiet` keeps the file and preview lines and the totals. With `--json`, `problems` and `output` are empty arrays
and `errors` and `warnings` still have the real counts. Nothing is printed to stderr during the run, including
messages from Compose and libraries. Command line errors are still printed.

### Rendering

| Option | Default | Effect |
|---|---|---|
| `--size WxH` | `411x891` | Window size in dp for previews without `widthDp`/`heightDp` |
| `--size DEVICE` | `phone` | The same, by device name |
| `--dark` | | Renders every preview in the dark theme |
| `--light` | | Renders every preview in the light theme |
| `--font-scale N` | the preview's | Font scale for every preview, e.g. `1.5` |
| `--density N` | `2` | Pixels per dp in images; `1` gives images at dp size, `3` sharper ones |
| `--wait MS` | `300` | Time to let effects, animations and network images settle after the preview loads |

| Device | Size in dp |
|---|---|
| `phone` | 411 × 891 |
| `foldable` | 673 × 841 |
| `tablet` | 1280 × 800 |
| `desktop` | 1920 × 1080 |

`--size` affects `check` too: previews are laid out and run at that size, so code that depends on the window size,
such as `BoxWithConstraints` or window size classes, runs the same branch as in the images.

`--dark` and `--light` set the Material theme, `isSystemInDarkTheme()`, and the resources' `-night` variants. Without
them, each preview's own `uiMode` decides.

How a preview is captured:

1. The preview's file is parsed and the files it uses are loaded.
2. Frames are drawn about every 16 ms, with the real time passing, until loading is done.
3. Frames continue for `--wait` milliseconds, at least two frames. `LaunchedEffect`s run, animations move and network
   images load meanwhile.
4. The last frame is the image.

`--wait 0` captures as soon as the preview is loaded; useful for static previews and fast CI runs. A longer wait helps
previews whose `LaunchedEffect` changes state after a delay, or that show images from the network.

### Project

| Option | Effect |
|---|---|
| `--no-sources` | Doesn't read other project files; the file must declare everything it uses besides bindings |
| `--source-root DIR` | Another source root to look declarations up in, after the file's own (repeatable) |
| `--res DIR` | Another `res/` or `composeResources/` folder, before the file's own (repeatable) |
| `-cp`, `--classpath CP` | Library jars or class folders for real library code (repeatable) |

`--classpath` takes entries separated by the system's path separator (`;` on Windows, `:` elsewhere). An entry
`@file` reads more entries from a file, one or more per line. See [Projects](PROJECTS.md).

```bash
fast-compose check Screen.kt --res ../design/src/main/res --classpath @build/preview-classpath.txt
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | No errors; warnings may have been found |
| `1` | At least one error: a parse error, an unknown name, a runtime error or a crash |
| `2` | A wrong command line: an unknown command or option, a missing value, a path that doesn't exist |

`--ignore-warnings` and `--quiet` don't change the exit code.

## Environment

| Variable | Effect |
|---|---|
| `FAST_COMPOSE_OPTS` | JVM options for the CLI, e.g. `-Xmx2g` for large projects |
| `JAVA_OPTS` | JVM options, also read by the start scripts |
| `JAVA_HOME` | The Java to run the CLI with, when the installation has no bundled runtime |

The CLI runs headless (`java.awt.headless=true`): it opens no windows and works on servers and CI machines without a
display.

## Recipes

Fail a CI job when previews have errors:

```bash
fast-compose check -r app/src --ignore-warnings
```

Render every screen of a module in the dark theme on a tablet:

```bash
fast-compose render "app/src/**/*Screen.kt" --dark --size tablet -o build/previews
```

Render quickly at dp size, for screenshots in docs:

```bash
fast-compose render ui/components --density 1 --wait 0 -o docs/images
```

Get only the JSON, for a script:

```bash
fast-compose render -r ui --json --quiet -o previews > previews.json
```

Check unsaved code from another tool: use the MCP server's `check` with `code` ([MCP](MCP.md)).
