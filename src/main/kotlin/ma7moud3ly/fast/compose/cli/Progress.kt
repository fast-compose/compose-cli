package ma7moud3ly.fast.compose.cli

import java.io.PrintStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A progress bar on one line of [out], redrawn in place like tqdm:
 * `Rendering  45%|█████████░░░░░░░░░░░| 53/117 [00:21<00:25, 2.5 previews/s] ForYouScreen.kt · Loading - tablet`.
 * The bar uses `#` and `-` when [out]'s encoding can't show block characters.
 */
class Progress(
    private val label: String,
    private val total: Int,
    private val unit: String = "previews",
    private val out: PrintStream = System.err,
) {

    private val start = System.nanoTime()
    private var done = 0
    private var shown = 0
    private val blocks = encodes("█░")

    /** Shows that [item] starts, after the items done so far. */
    fun start(item: String) = draw(item)

    /** Counts one more item as done. */
    fun step() {
        done = (done + 1).coerceAtMost(total)
    }

    /** Clears the line, so later output starts on a clean one. */
    fun finish() {
        if (shown > 0) out.print("\r" + " ".repeat(shown) + "\r")
        out.flush()
        shown = 0
    }

    private fun draw(item: String) {
        val line = line(item).take(width() - 1)
        out.print("\r" + line + " ".repeat((shown - line.length).coerceAtLeast(0)))
        out.flush()
        shown = line.length
    }

    /** The line for [item]: label, percent, bar, count, elapsed and remaining time, speed in [unit] and [item]. */
    internal fun line(item: String, elapsedSeconds: Double = (System.nanoTime() - start) / 1e9): String {
        val fraction = if (total == 0) 1.0 else done.toDouble() / total
        val percent = (fraction * 100).toInt().toString().padStart(3)
        val filled = (fraction * BAR).roundToInt()
        val bar = if (blocks) "█".repeat(filled) + "░".repeat(BAR - filled) else "#".repeat(filled) + "-".repeat(BAR - filled)
        val rate = if (elapsedSeconds > 0) done / elapsedSeconds else 0.0
        val remaining = if (done > 0 && rate > 0) time((total - done) / rate) else "?"
        val speed = if (done > 0) "%.1f".format(Locale.ROOT, rate) else "?"
        return "$label $percent%|$bar| $done/$total [${time(elapsedSeconds)}<$remaining, $speed $unit/s] $item"
    }

    private fun encodes(text: String): Boolean = runCatching {
        val name = System.getProperty("stderr.encoding") ?: Charset.defaultCharset().name()
        Charset.forName(name).newEncoder().canEncode(text)
    }.getOrDefault(false)

    private fun width(): Int = System.getenv("COLUMNS")?.toIntOrNull()?.takeIf { it > 40 } ?: DEFAULT_WIDTH

    companion object {
        private const val BAR = 20
        private const val DEFAULT_WIDTH = 100

        /** [seconds] as `mm:ss`, or `h:mm:ss` from an hour. */
        internal fun time(seconds: Double): String {
            val total = seconds.toLong().coerceAtLeast(0)
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%02d:%02d".format(Locale.ROOT, m, s)
        }
    }
}
