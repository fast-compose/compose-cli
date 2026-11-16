# Projects

A preview rarely stands alone: it uses composables, themes and models from other files, images and strings from
`R` or `Res`, and classes from libraries. The CLI finds all three from the folder layout, with no Gradle sync and no
IDE.

| What | Where it comes from | Options |
|---|---|---|
| [Other files](#other-files) | The file's source root and its module's shared source sets; every module with `--modules` | `--modules`, `--no-sources`, `--source-root` |
| [Resources](#resources) | The file's `res/` and `composeResources/` folders; every module's with `--modules` | `--modules`, `--res` |
| [Library code](#library-code) | Bindings built into the engine, then jars from the classpath, then stand-ins | `--classpath` |

## Other files

A name the previewed file uses but doesn't declare is looked up as Kotlin does: an explicit import first, then the
file's own package, then star imports. Files that declare a name are loaded, and so are the files their declarations
use in turn. Only what the previews reach is loaded, so a large project costs no more than the files a screen uses.

Where files are looked up:

1. **The file's source root**, found from its package and folders: for `app/src/main/kotlin/app/ui/Card.kt` in package
   `app.ui`, that's `app/src/main/kotlin`.
2. **Its module's shared source sets**: `src/commonMain/kotlin` (and `java`) for Kotlin Multiplatform, `src/main/kotlin`
   (and `java`) for Android and JVM modules.
3. **Folders given with `--source-root`**, in order.
4. **With `--modules`, every module of the project**: their `src/main` and `src/commonMain` source roots.

```
shared/
  src/
    commonMain/kotlin/app/ui/Theme.kt         ← found from a file in androidMain or jvmMain
    commonMain/kotlin/app/ui/Card.kt
    androidMain/kotlin/app/ui/Card.android.kt
    jvmMain/kotlin/app/ui/Platform.jvm.kt     ← an `actual`, used for an `expect` in commonMain
```

- **Packages:** a package with a folder under these roots belongs to the project, and its names are looked up in
  files. Other packages are libraries, and their names come from bindings and the classpath. `androidx`, `android`,
  `kotlin`, `kotlinx`, `java`, `javax`, `org.jetbrains.compose` and Coil are always libraries.
- **`expect` and `actual`:** an `expect` declaration is replaced by its `actual` from the module's other source sets,
  JVM ones (`jvmMain`, `desktopMain`) first, then Android, then others. Default arguments written only on the
  `expect fun` still apply.
- **Unknown names:** a name imported from a project package that no file declares is reported as a warning:
  `Unresolved reference 'Card' (imported from app.ui)`.
- **Errors in other files:** parse errors in a file the preview uses are reported against that file, and its last
  version without errors is used where possible.
- **Other modules:** files of other Gradle modules aren't found by the file's own layout. `--modules` adds every
  module of the project: the CLI finds the project's root (the nearest folder above the file with a
  `settings.gradle.kts` or `settings.gradle`) and reads each module's `src/main` and `src/commonMain` sources and
  resources. `build` and hidden folders are skipped.

  ```bash
  fast-compose render -r nowinandroid --modules -o previews
  ```

  Or name only the modules needed with `--source-root`:

  ```bash
  fast-compose render app/src/main/kotlin/app/ui -o previews \
    --source-root core/designsystem/src/main/kotlin \
    --source-root core/model/src/commonMain/kotlin
  ```

- **Generated sources:** `BuildConfig`, BuildKonfig and other generated files can be added the same way, e.g.
  `--source-root app/build/generated/source/buildConfig/debug`.
- **`--no-sources`:** reads only the given file. Faster, and useful for self-contained samples.

Files are read from disk on each run (and on each MCP call), so the CLI always sees saved changes.

## Resources

`R.drawable.logo`, `Res.string.title`, `painterResource`, `stringResource`, fonts and colors come from the resource
folders next to the file, by Gradle layout, most specific first:

1. Folders given with `--res`, in order.
2. The file's own source set: `src/androidMain/res`, `src/jvmMain/composeResources`, …
3. Its module's shared source sets: `src/commonMain/composeResources`, `src/main/res`.
4. With `--modules`, every module's `src/main` and `src/commonMain` resource folders.

The first folder with a matching resource wins. Within a folder, variants are picked like on a device:

| Variant | Picked by |
|---|---|
| `values-ar`, `values-pt-rBR`, `values-b+sr+Latn` | the preview's `locale` |
| `drawable-night`, `values-night` | the preview's `uiMode`, or `--dark` / `--light` |
| `drawable-hdpi` … `drawable-xxxhdpi` | the closest to `--density` |

- Folders read: `drawable*`, `mipmap*`, `values*`, `font*` and `files*`. Hidden files and files over 20 MB are
  skipped.
- Supported files: PNG, JPEG, WebP, BMP and vector drawable XML images (not SVG); `strings.xml` with format arguments
  such as `%s` and `%1$d`; `colors.xml`; TTF and OTF fonts. See the engine's
  [resources guide](https://github.com/fast-compose/fast-compose/blob/main/docs/RESOURCES.md) for details.
- Resources of other modules, such as a shared design system, come with `--modules`, or one by one with `--res`:

  ```bash
  fast-compose render feature/home -o previews --res core/designsystem/src/main/res
  ```

## Library code

A name from a library package is resolved in this order:

1. **Bindings:** Compose foundation, layouts, Material 3, Material icons, animation, resources and Coil are built into
   the engine and run real Compose code.
2. **The classpath:** with `--classpath`, the library's real code runs from its jars.
3. **Stand-ins:** otherwise the call runs as a placeholder value, with a warning: `'EditorTheme' (io.nemo.model) has
   no binding; a stand-in is used, so the preview may differ`. The preview still renders, but values from the stand-in
   are empty or defaults.

Even without `--classpath`, the JDK, the Kotlin standard library and coroutines run for real, e.g. `java.io.File`,
`LocalDate.now()` or `UUID.randomUUID()`. So does kotlinx-datetime 0.7, which the CLI ships: `Clock.System.now()`,
`TimeZone.currentSystemDefault()`, `toJavaZoneId()` and `toJavaInstant()` work out of the box. A project's own jars
from `--classpath` come first, so its version is used when given.

Projects on kotlinx-datetime 0.6 use `kotlinx.datetime.Instant` and `kotlinx.datetime.Clock`, which 0.7 replaced with
`kotlin.time.Instant` and `kotlin.time.Clock`; with the bundled 0.7 they run as stand-ins. Pass the project's own
kotlinx-datetime JVM jar, e.g. from Gradle's cache or the `previewClasspath` task below:

```bash
fast-compose render -r . --modules -o previews --classpath ~/.gradle/caches/modules-2/files-2.1/org.jetbrains.kotlinx/kotlinx-datetime-jvm/0.6.1/<hash>/kotlinx-datetime-jvm-0.6.1.jar
```

### The classpath

`--classpath` takes jars and class folders, separated by the system's path separator (`;` on Windows, `:` elsewhere).
An entry `@file` reads entries from a file, one or more per line.

```bash
fast-compose check Screen.kt --classpath "libs/nemo-editor-jvm-2.0.0.jar;libs/kotlinx-datetime-jvm-0.7.1.jar"
fast-compose check Screen.kt --classpath @app/build/preview-classpath.txt
```

The easiest way to get a module's classpath is a Gradle task that writes it to a file:

```kotlin
// In the module's build.gradle.kts
tasks.register("previewClasspath") {
    // jvmRuntimeClasspath or desktopRuntimeClasspath for KMP, runtimeClasspath for JVM modules,
    // debugRuntimeClasspath for Android ones.
    val classpath: FileCollection = configurations["jvmRuntimeClasspath"]
    val output = layout.buildDirectory.file("preview-classpath.txt")
    inputs.files(classpath)
    outputs.file(output)
    doLast { output.get().asFile.writeText(classpath.files.joinToString("\n")) }
}
```

```bash
./gradlew :shared:previewClasspath
fast-compose render shared/src/commonMain/kotlin/app/ui -o previews --classpath @shared/build/preview-classpath.txt
```

How the classpath is used:

- **Host libraries:** Kotlin, kotlinx.coroutines, Compose, Skia and the engine always come from the CLI, so values pass
  between the engine and library code. Their jars on the classpath are skipped.
- **Project outputs:** jars and folders under a `build/` folder are skipped; the project's own code comes from its
  sources.
- **Kotlin Multiplatform:** a library's `-metadata` jar or `.klib` is replaced by its JVM jar (`-jvm` or `-desktop`)
  from the same Gradle cache, when downloaded.
- **Isolation:** the jars load in a class loader of their own. Jars stay open while the CLI runs; on Windows they can't
  be deleted meanwhile, so prefer Gradle's caches, which don't change.
- **What runs:** classes and objects, constructors with named and default arguments, top-level functions and
  properties, extension functions, members of returned values, varargs and lambdas. Library composables run too, such
  as a third-party editor or chart.

Limits:

| Case | Result |
|---|---|
| Android-only libraries (`.aar`, `android.*` APIs) | Can't run on the desktop JVM; stand-ins are used |
| `inline` functions with `reified` types | Can't be called through reflection; stand-ins are used |
| Libraries built for another Kotlin or Compose version | May fail to link; the failure is reported and a stand-in used |
| `suspend` library functions | Not supported yet |
| JDK modules outside the bundled runtime, such as `java.sql` or `java.xml` | Fail to load; run the CLI with a full JDK through `JAVA_HOME` and an installation without `runtime/` |

A failure inside library code is reported as an error at the line that called it, and the call gives a stand-in.

## What previews see

Every preview runs with the composition locals `@Preview` would give it:

| Local | Value |
|---|---|
| `LocalInspectionMode` | `true` |
| Material theme | Light or dark color scheme, from `uiMode`, `--dark` or `--light` |
| `isSystemInDarkTheme()` | The same |
| Window size | The preview's `widthDp` × `heightDp`, or `--size` |
| `LocalDensity` | `--density`, with the preview's `fontScale` or `--font-scale` |
| Resources' locale | The preview's `locale` |

`@Preview` arguments read: `name`, `group`, `widthDp`, `heightDp`, `locale`, `fontScale`, `showBackground`,
`backgroundColor`, `uiMode` (night mode) and `device` (`spec:width=…dp,height=…dp` and `Devices.PHONE`, `TABLET`,
`FOLDABLE`, `DESKTOP`, `PIXEL_4`, `PIXEL_7`, `NEXUS_5`, `PIXEL_TABLET`). `apiLevel`, `showSystemUi` and `wallpaper`
are ignored.

Multi-preview annotations (a project's own, such as `@DevicePreviews`, and AndroidX's `@PreviewLightDark`,
`@PreviewScreenSizes` and `@PreviewFontScale`) give one preview per `@Preview` they stand for. A
`@PreviewParameter(Provider::class)` parameter gives one preview per value of the provider; the provider class is
read from the project's files like any other declaration. See [Commands](COMMANDS.md#paths).
