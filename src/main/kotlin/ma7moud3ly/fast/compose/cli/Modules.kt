package ma7moud3ly.fast.compose.cli

import java.io.File
import ma7moud3ly.fast.compose.project.DiskFiles
import ma7moud3ly.fast.compose.project.GradleLayout

/** The source roots and resource folders of every module of a Gradle project, as absolute paths with `/`. */
class ModuleFolders(val sourceRoots: List<String>, val resourceFolders: List<String>)

/** Finds the modules of a Gradle project by folder layout, for `--modules`. */
object GradleModules {

    private val settingsFiles = listOf("settings.gradle.kts", "settings.gradle")

    /** Folders never searched for modules: build outputs, caches, IDE and VCS folders. */
    private val skipped = setOf("build", "node_modules", "out")

    /** The Gradle project holding [file]: the nearest folder above it with a `settings.gradle(.kts)`; null outside one. */
    fun rootOf(file: File): File? =
        generateSequence(file.absoluteFile.let { if (it.isDirectory) it else it.parentFile }) { it.parentFile }
            .firstOrNull { dir -> settingsFiles.any { File(dir, it).isFile } }

    /**
     * Every module's shared source sets under [root] (`src/main` and `src/commonMain`): their `kotlin` and `java`
     * source roots and their `res` and `composeResources` folders, in path order. Build outputs and hidden folders are
     * skipped.
     */
    fun scan(root: File): ModuleFolders {
        val sources = mutableListOf<String>()
        val resources = mutableListOf<String>()
        root.walkTopDown()
            // Folders inside a source set (`src/main/kotlin/…`) hold no other modules.
            .onEnter { it == root || !it.name.startsWith(".") && it.name !in skipped && it.parentFile?.parentFile?.name != "src" }
            .filter { it.isDirectory && it.name in GradleLayout.SHARED_SOURCE_SETS && it.parentFile?.name == "src" }
            .sortedBy { it.path }
            .forEach { sourceSet ->
                GradleLayout.SOURCE_ROOTS.map { File(sourceSet, it) }.filter { it.isDirectory }
                    .forEach { sources += DiskFiles.pathOf(it) }
                GradleLayout.RESOURCE_FOLDERS.map { File(sourceSet, it) }.filter { it.isDirectory }
                    .forEach { resources += DiskFiles.pathOf(it) }
            }
        return ModuleFolders(sources, resources)
    }
}
