import java.util.Properties
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    application
}

group = "io.github.fast-compose"
// The CLI's version: `cliVersion` in local.properties, which CI writes from the release tag; "dev" without it.
val localProperties: String? = providers.fileContents(layout.projectDirectory.file("local.properties")).asText.orNull
val cliVersion: String? = localProperties?.let { text ->
    Properties().apply { load(text.reader()) }.getProperty("cliVersion")
}
version = cliVersion?.takeIf { it.isNotBlank() } ?: "dev"

val desktopTargets = listOf("windows-x64", "windows-arm64", "macos-x64", "macos-arm64", "linux-x64", "linux-arm64")

// This machine's OS and CPU, e.g. windows-x64.
val hostTarget: String = run {
    val os = System.getProperty("os.name").lowercase()
    val name = when {
        "win" in os -> "windows"
        "mac" in os -> "macos"
        else -> "linux"
    }
    val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
    "$name-$arch"
}

// The desktop OS and CPU the build is for, which picks Skiko's native library: -Ptarget=windows-x64, or this machine's.
val target: String = providers.gradleProperty("target").orNull?.takeIf { it.isNotBlank() } ?: hostTarget
require(target in desktopTargets) { "Unknown target '$target': use one of ${desktopTargets.joinToString()}" }

// The distribution bundles a trimmed Java runtime, which jlink builds only for this machine's OS and CPU; builds for
// another target leave it out, and need an installed Java 21.
val bundleRuntime = target == hostTarget

// JDK modules the CLI uses (jdeps), plus small ones library code loaded with --classpath often needs.
val runtimeModules = listOf(
    "java.base", "java.desktop", "java.logging", "java.management", "jdk.unsupported", "jdk.crypto.ec",
    "java.net.http", "java.naming", "jdk.zipfs",
)

kotlin {
    jvmToolchain(21)
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(libs.fast.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // Shipped for library code previews often use; see BundledLibraries.
    implementation(libs.kotlinx.datetime)
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$target:${libs.versions.composeMultiplatform.get()}")

    testImplementation(kotlin("test-junit"))
}

application {
    mainClass = "ma7moud3ly.fast.compose.cli.MainKt"
    applicationName = "fast-compose"
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

/**
 * Copies the Material icons jar with only the icon classes the engine's bindings use, and the icon classes those use.
 * The output jar keeps the original's name, so start scripts that list jars by name still find it.
 */
abstract class ShrinkIcons : DefaultTask() {

    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun shrink() {
        val files = classpath.files
        val engine = files.single { it.name.startsWith("fast-compose-jvm") }
        val icons = files.single { it.name.startsWith(ICONS_JAR) }
        val iconClasses = ZipFile(icons).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }
                .associate { it.name.removeSuffix(".class") to zip.getInputStream(it).readBytes() }
        }
        val kept = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        fun use(bytes: ByteArray) {
            for (match in ICON_CLASS.findAll(String(bytes, Charsets.ISO_8859_1))) {
                val name = match.value
                if (name in iconClasses && kept.add(name)) queue += name
            }
        }
        ZipFile(engine).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".class") }.forEach { use(zip.getInputStream(it).readBytes()) }
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            use(iconClasses.getValue(name))
            // Lambdas and nested classes of a kept class.
            iconClasses.keys.filter { it.startsWith("$name$") && kept.add(it) }.forEach { queue += it }
        }

        val output = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }.resolve(icons.name)
        ZipFile(icons).use { zip ->
            JarOutputStream(output.outputStream()).use { jar ->
                for (entry in zip.entries()) {
                    val isClass = entry.name.endsWith(".class")
                    if (isClass && entry.name.removeSuffix(".class") !in kept) continue
                    jar.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }
        logger.lifecycle("Kept ${kept.size} of ${iconClasses.size} icon classes: ${icons.length() / 1024} KB → ${output.length() / 1024} KB")
    }

    companion object {
        const val ICONS_JAR = "material-icons-extended-desktop"
        val ICON_CLASS = Regex("androidx/compose/material/icons/[A-Za-z0-9_/$]+")
    }
}

val shrinkIcons = tasks.register<ShrinkIcons>("shrinkIcons") {
    classpath.from(configurations.runtimeClasspath)
    outputDir = layout.buildDirectory.dir("shrunk-icons")
}

// A trimmed Java 21 runtime with only runtimeModules, built by the toolchain's jlink.
val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    val jdk = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }.get().metadata.installationPath
    val jlink = jdk.file(if (hostTarget.startsWith("windows")) "bin/jlink.exe" else "bin/jlink").asFile
    val output = layout.buildDirectory.dir("runtime")
    inputs.property("modules", runtimeModules)
    inputs.property("jdk", jdk.asFile.path)
    outputs.dir(output)
    executable = jlink.path
    args(
        "--add-modules", runtimeModules.joinToString(","),
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6",
        "--output", output.get().asFile.path,
    )
    // jlink fails when its output folder exists.
    doFirst { output.get().asFile.deleteRecursively() }
}

// The distribution ships the shrunk icons jar in place of the full one, and the Java runtime when it is built.
distributions.main {
    contents {
        val shrunk = layout.buildDirectory.dir("shrunk-icons").get().asFile
        from(shrinkIcons) { into("lib") }
        eachFile {
            if (name.startsWith(ShrinkIcons.ICONS_JAR) && !file.startsWith(shrunk)) exclude()
        }
        if (bundleRuntime) {
            from(jlinkRuntime) { into("runtime") }
            filesMatching(listOf("runtime/bin/**", "runtime/lib/jspawnhelper")) {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
}

tasks.distZip { archiveClassifier = target }
tasks.distTar { archiveClassifier = target }

tasks.startScripts {
    doLast {
        // Listing every jar makes the Windows script's command line too long; Java expands lib\* to the same jars.
        // Both scripts use the bundled runtime when the distribution has one, before JAVA_HOME or the PATH.
        val windows = windowsScript
        windows.writeText(
            windows.readText()
                .replace(Regex("(?m)^set CLASSPATH=.*$"), Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\*"))
                .replace(
                    "@rem Find java.exe",
                    "@rem Use the bundled Java runtime when there is one.\r\n" +
                        "if exist \"%APP_HOME%\\runtime\\bin\\java.exe\" set JAVA_HOME=%APP_HOME%\\runtime\r\n\r\n" +
                        "@rem Find java.exe",
                ),
        )
        val unix = unixScript
        unix.writeText(
            unix.readText().replace(
                "# Determine the Java command to use to start the JVM.",
                "# Use the bundled Java runtime when there is one.\n" +
                    "if [ -x \"\$APP_HOME/runtime/bin/java\" ] ; then JAVA_HOME=\$APP_HOME/runtime ; fi\n\n" +
                    "# Determine the Java command to use to start the JVM.",
            ),
        )
    }
}

tasks.jar {
    manifest {
        attributes("Implementation-Title" to "fast-compose", "Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
