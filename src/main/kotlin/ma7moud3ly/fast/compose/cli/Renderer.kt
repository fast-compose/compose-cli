package ma7moud3ly.fast.compose.cli

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.io.Closeable
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import ma7moud3ly.fast.compose.bindings.foundation.LocalPreviewDarkTheme
import ma7moud3ly.fast.compose.bindings.foundation.LocalPreviewWindowSize
import ma7moud3ly.fast.compose.bindings.resources.LocalResourceEnvironment
import ma7moud3ly.fast.compose.bindings.resources.ResourceEnvironment
import ma7moud3ly.fast.compose.bindings.resources.ResourceProvider
import ma7moud3ly.fast.compose.interpreter.ExternalResolver
import ma7moud3ly.fast.compose.interpreter.LocalExternalResolver
import ma7moud3ly.fast.compose.interpreter.Registry
import ma7moud3ly.fast.compose.interpreter.SourceProvider
import ma7moud3ly.fast.compose.parser.Diagnostic
import ma7moud3ly.fast.compose.ui.ComposeCode
import ma7moud3ly.fast.compose.ui.FastComposeContent
import ma7moud3ly.fast.compose.ui.LogLine
import ma7moud3ly.fast.compose.ui.PreviewConfig
import ma7moud3ly.fast.compose.ui.rememberComposeCode
import ma7moud3ly.fast.compose.ui.rememberFullRegistry
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface as SkiaSurface

/** One preview to render: [entry] of [file] with [config], in a window of [device] size. */
class RenderRequest(
    val file: SourceText,
    val entry: String,
    val config: PreviewConfig,
    val device: DeviceSize,
    val density: Float,
    val waitMs: Long,
    val sources: SourceProvider?,
    val resources: ResourceProvider,
    val externals: ExternalResolver?,
    val capture: Boolean,
)

/**
 * What rendering a preview gave: a PNG when captured, the file's parse [diagnostics], [logs] with runtime errors and
 * `println` output, and a [crash] thrown by Compose while composing, measuring or drawing.
 */
class RenderResult(
    val png: ByteArray?,
    val widthPx: Int,
    val heightPx: Int,
    val diagnostics: List<Diagnostic>,
    val logs: List<LogLine>,
    val crash: Throwable?,
    val timedOut: Boolean,
)

/**
 * Renders previews off-screen with Compose's [ImageComposeScene], one scene per preview, on a thread of its own: the
 * scene's effects, such as loading the files a preview uses, run there between frames.
 */
class Renderer : Closeable {

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "fast-compose-render").apply { isDaemon = true } }
    private val dispatcher = executor.asCoroutineDispatcher()

    /**
     * The engine's full API: foundation, Material 3, icons, resources and Coil. What building it prints, e.g. Compose's
     * warning about `Locale("und")`, goes to stderr, so it can't mix with JSON output.
     */
    val registry: Registry by lazy {
        val stdout = System.out
        System.setOut(System.err)
        try {
            runBlocking(dispatcher) {
                var registry: Registry? = null
                val scene = ImageComposeScene(1, 1, Density(1f), dispatcher) { registry = rememberFullRegistry() }
                try {
                    scene.render(0)
                } finally {
                    scene.close()
                }
                checkNotNull(registry)
            }
        } finally {
            System.setOut(stdout)
        }
    }

    /**
     * Renders [request]: frames until the preview's files are loaded, then for [RenderRequest.waitMs] more so effects,
     * animations and images can settle. A preview without `widthDp`/`heightDp` wraps its content up to the device size,
     * and the image is cropped to it.
     */
    fun render(request: RenderRequest): RenderResult {
        val registry = registry
        return runBlocking(dispatcher) { renderOnThread(request, registry) }
    }

    private suspend fun renderOnThread(request: RenderRequest, registry: Registry): RenderResult {
        val config = request.config
        val window = DpSize((config.widthDp ?: request.device.widthDp).dp, (config.heightDp ?: request.device.heightDp).dp)
        val widthPx = ceil(window.width.value * request.density).toInt().coerceAtLeast(1)
        val heightPx = ceil(window.height.value * request.density).toInt().coerceAtLeast(1)
        var code: ComposeCode? = null
        var contentSize = IntSize.Zero
        var crash: Throwable? = null
        var timedOut = false
        var image: Image? = null

        val scene = try {
            ImageComposeScene(widthPx, heightPx, Density(request.density, config.fontScale), dispatcher) {
                PreviewFrame(config, window, onSize = { contentSize = it }) {
                    CompositionLocalProvider(LocalExternalResolver provides request.externals) {
                        val current = rememberComposeCode(request.file.text, request.file.path, request.sources)
                        code = current
                        FastComposeContent(current, entry = request.entry, registry = registry, resources = request.resources)
                    }
                }
            }
        } catch (e: Exception) {
            return RenderResult(null, 0, 0, emptyList(), emptyList(), e, timedOut = false)
        }
        try {
            val start = System.nanoTime()
            var readyAt: Long? = null
            var framesSinceReady = 0
            while (true) {
                Snapshot.sendApplyNotifications()
                val now = System.nanoTime() - start
                try {
                    image = scene.render(now)
                } catch (e: Exception) {
                    crash = e
                    break
                }
                val current = code
                if (readyAt == null && current != null && !current.isLoading) readyAt = now
                if (readyAt != null) {
                    framesSinceReady++
                    if (framesSinceReady >= MIN_FRAMES && now - readyAt >= request.waitMs * 1_000_000) break
                } else if (now >= LOAD_TIMEOUT_NS) {
                    timedOut = true
                    break
                }
                delay(FRAME_MS)
            }
            // Log lines are added after the frame that logged them.
            delay(FRAME_MS)
            Snapshot.sendApplyNotifications()
            val png = if (request.capture && crash == null) image?.let { crop(it, contentSize) } else null
            return RenderResult(
                png = png,
                widthPx = if (png != null) contentSize.width.coerceIn(1, widthPx) else 0,
                heightPx = if (png != null) contentSize.height.coerceIn(1, heightPx) else 0,
                diagnostics = code?.diagnostics.orEmpty(),
                logs = code?.logs?.toList().orEmpty(),
                crash = crash,
                timedOut = timedOut,
            )
        } finally {
            runCatching { scene.close() }
        }
    }

    /** The top-left [size] of [image] as PNG bytes; the whole image when [size] is empty. */
    private fun crop(image: Image, size: IntSize): ByteArray {
        val width = size.width.takeIf { it > 0 }?.coerceAtMost(image.width) ?: image.width
        val height = size.height.takeIf { it > 0 }?.coerceAtMost(image.height) ?: image.height
        val surface = SkiaSurface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.drawImage(image, 0f, 0f)
            val data = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)
                ?: error("Could not encode the preview as PNG")
            return data.bytes
        } finally {
            surface.close()
        }
    }

    override fun close() {
        dispatcher.close()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val MIN_FRAMES = 2
        const val LOAD_TIMEOUT_NS = 20_000_000_000L
    }
}

/**
 * A preview as the IDE plugin frames it: Material colors for its theme, its background, and the composition locals
 * `@Preview` sets. Without `widthDp`/`heightDp` it wraps its content up to [window]; [onSize] gets the size in pixels.
 */
@Composable
private fun PreviewFrame(config: PreviewConfig, window: DpSize, onSize: (IntSize) -> Unit, content: @Composable () -> Unit) {
    val dark = config.darkTheme
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        val background = when {
            config.showBackground -> config.backgroundColor ?: MaterialTheme.colorScheme.background
            dark -> MaterialTheme.colorScheme.background
            else -> Color.Transparent
        }
        val size = Modifier
            .then(if (config.widthDp != null) Modifier.width(window.width) else Modifier.widthIn(max = window.width))
            .then(if (config.heightDp != null) Modifier.height(window.height) else Modifier.heightIn(max = window.height))
        Box(Modifier.wrapContentSize(Alignment.TopStart)) {
            Surface(size.onSizeChanged(onSize), color = background) {
                CompositionLocalProvider(
                    LocalResourceEnvironment provides ResourceEnvironment(config.locale, dark),
                    LocalPreviewDarkTheme provides dark,
                    LocalInspectionMode provides true,
                    LocalPreviewWindowSize provides window,
                    content = content,
                )
            }
        }
    }
}
