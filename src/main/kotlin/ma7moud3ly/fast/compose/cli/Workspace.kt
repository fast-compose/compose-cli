package ma7moud3ly.fast.compose.cli

import java.io.Closeable
import java.io.File
import kotlinx.coroutines.runBlocking
import ma7moud3ly.fast.compose.bindings.resources.ChainResources
import ma7moud3ly.fast.compose.bindings.resources.FileStorage
import ma7moud3ly.fast.compose.bindings.resources.MemoryResources
import ma7moud3ly.fast.compose.bindings.resources.ResourceProvider
import ma7moud3ly.fast.compose.interpreter.ExternalResolver
import ma7moud3ly.fast.compose.interpreter.LoadedSources
import ma7moud3ly.fast.compose.interpreter.SourceLoader
import ma7moud3ly.fast.compose.interpreter.SourceProvider
import ma7moud3ly.fast.compose.interpreter.jvm.GradleJars
import ma7moud3ly.fast.compose.interpreter.jvm.JarResolver
import ma7moud3ly.fast.compose.parser.KotlinParser
import ma7moud3ly.fast.compose.parser.ParseResult
import ma7moud3ly.fast.compose.project.DiskFiles
import ma7moud3ly.fast.compose.project.FolderSources
import ma7moud3ly.fast.compose.project.GradleLayout
import ma7moud3ly.fast.compose.project.KotlinSources

/**
 * A Kotlin file to preview: its absolute [path] with `/` separators and its [text]. A [virtual] file, e.g. code an
 * agent sent without a path, has no project around it.
 */
class SourceText(val path: String, val text: String, val virtual: Boolean = false) {
    val parsed: ParseResult by lazy { KotlinParser.parse(text, path) }

    companion object {
        fun read(file: File) = SourceText(DiskFiles.pathOf(file), file.readText())

        /** [text] as the file at [file] when given, e.g. unsaved code; otherwise a virtual file in the working folder. */
        fun of(text: String, file: File?) =
            if (file != null) SourceText(DiskFiles.pathOf(file), text)
            else SourceText(DiskFiles.pathOf(File("Preview.kt")), text, virtual = true)
    }
}

/**
 * What previews see around a file, from [settings]: other files of its project (by Gradle's folder layout), its
 * resource folders, and real library code from the classpath. Files are read from disk on each call, so a long-running
 * host such as the MCP server sees changes.
 */
class Workspace(private val settings: ProjectSettings) : Closeable {

    private val kotlinSources = KotlinSources(DiskFiles)
    private val folders = mutableMapOf<String, FolderResources>()

    /**
     * Runs library code without a binding: the classpath's jars, or only the JDK, Kotlin and coroutines without one.
     * Compose, Kotlin and coroutines always come from the CLI itself.
     */
    val externals: ExternalResolver get() = resolver.value

    private val resolver = lazy { JarResolver(GradleJars.jvmJars(settings.classpath)) }

    /** Finds declarations of [file]'s project in other files; null for a virtual file or with sources turned off. */
    fun sources(file: SourceText): SourceProvider? {
        if (!settings.sources || file.virtual) return null
        val extra = settings.sourceRoots.map { DiskFiles.pathOf(it) }
        return FolderSources(kotlinSources, file.path, (kotlinSources.folderRoots(file.path) + extra).distinct())
    }

    /** The files [file] uses, with names imported from the project that no file declares; null without sources. */
    fun load(file: SourceText): LoadedSources? {
        val sources = sources(file) ?: return null
        return runBlocking { SourceLoader(sources).load(file.parsed.program) }
    }

    /**
     * Resources for [file]: the folders given with `--res` first, then its own by Gradle layout, most specific first.
     * Each folder is read fully and kept; changed files are read again on the next call.
     */
    fun resources(file: SourceText): ResourceProvider {
        val own = if (file.virtual) emptyList() else GradleLayout.resourceFolders(DiskFiles, file.path)
        val paths = (settings.resourceFolders.map { DiskFiles.pathOf(it) } + own).distinct()
        if (paths.isEmpty()) return ResourceProvider.Empty
        val providers = runBlocking {
            paths.map { path -> folders.getOrPut(path) { FolderResources(File(path)) }.also { it.refresh() }.files }
        }
        return ChainResources(providers)
    }

    /** Clears cached files, e.g. after many changes. */
    fun clear() {
        kotlinSources.clear()
        folders.clear()
    }

    override fun close() {
        if (resolver.isInitialized()) resolver.value.close()
    }
}

/** A resource folder held in memory; [refresh] reads files that changed since the last call. */
private class FolderResources(root: File) {
    private val storage = FileStorage(root)
    private val versions = mutableMapOf<String, Long>()
    val files = MemoryResources()

    suspend fun refresh() {
        val listed = storage.list()
        val paths = listed.mapTo(mutableSetOf()) { it.path }
        for (path in versions.keys - paths) {
            files.remove(path)
            versions.remove(path)
        }
        for (file in listed) {
            if (versions[file.path] == file.version) continue
            val bytes = storage.read(file.path) ?: continue
            files.put(file.path, bytes)
            versions[file.path] = file.version
        }
    }
}
