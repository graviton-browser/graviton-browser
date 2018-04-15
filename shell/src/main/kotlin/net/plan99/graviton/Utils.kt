package net.plan99.graviton

import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Casts the string to a [Path] but does not check for existence or do any other form of disk IO. */
fun String.toPath(): Path = Paths.get(this)
/** Returns true if the given path exists. */
val Path.exists: Boolean get() = Files.exists(this)
/** Allows you to write paths like "foo".toPath() / "b" / "c" and will use the correct platform specific path concatenation rules. */
operator fun Path.div(other: String): Path = resolve(other)

/** The supported operating systems we are on and OS-specific settings. */
enum class OperatingSystem {
    MAC {
        private val library = homeDirectory / "Library"
        override val appCacheDirectory: Path get() = library / "Caches" / "Graviton Browser"
        override val loggingDirectory: Path get() = library / "Logs" / "Graviton Browser"
    },
    WIN {
        override val classPathDelimiter: Char = ';'
        private val localAppData get() = System.getenv("LOCALAPPDATA").toPath()
        private val myLocalAppData get() = localAppData / "GravitonBrowser"
        // If you add new directories here, remember to update GravitonBrowser.iss to ensure the uninstaller removes them.
        override val appCacheDirectory: Path get() = myLocalAppData / "Cache"
        override val loggingDirectory: Path get() = myLocalAppData / "Logs"
    },
    LINUX {
        private val appDirectory: Path get() {
            return if (System.getenv("XDG_CACHE_HOME").isNullOrBlank()) {
                homeDirectory / ".cache" / "GravitonBrowser"
            } else {
                System.getenv("XDG_CACHE_HOME").toPath() / "GravitonBrowser"
            }
        }
        override val appCacheDirectory: Path get() = appDirectory / "repository"
        override val loggingDirectory: Path get() = appDirectory / "logs"
    },
    UNKNOWN {
        override val appCacheDirectory: Path get() = unreachable()
        override val loggingDirectory: Path get() = unreachable()
    };

    abstract val appCacheDirectory: Path
    abstract val loggingDirectory: Path
    open val classPathDelimiter: Char = ':'
    open val homeDirectory: Path = System.getProperty("user.home").toPath()
}

/** Creates the given path if necessary as a directory and returns it */
fun Path.createDirectories(): Path = Files.createDirectories(this)

/** Throws an exception indicating this code path should never be called. */
fun unreachable(): Nothing = error("Unreachable")

/** Whichever [OperatingSystem] we are executing on, based on the "os.name" property, or [OperatingSystem.UNKNOWN]. */
val currentOperatingSystem: OperatingSystem by lazy {
    val name = System.getProperty("os.name").toLowerCase()
    when {
        name.contains("win") -> OperatingSystem.WIN
        name.contains("mac") -> OperatingSystem.MAC
        name.contains("linux") -> OperatingSystem.LINUX
        else -> OperatingSystem.UNKNOWN
    }
}

/** Walks the [Throwable.cause] chain to the root. */
val Throwable.rootCause: Throwable get() {
    var t: Throwable = this
    while (t.cause != null) t = t.cause!!
    return t
}

fun Throwable.asString(): String {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return sw.toString()
}

/** Uses [System.nanoTime] to measure elapsed time and exposes it in seconds to 1/10th of a second precision. */
class Stopwatch {
    private val start = System.nanoTime()
    val elapsedInSec: Double get() = (System.nanoTime() - start) / 100000000 / 10.0
}