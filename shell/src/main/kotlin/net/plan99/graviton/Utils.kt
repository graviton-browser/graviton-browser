package net.plan99.graviton

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
        override val appCacheDirectory: Path
            get() = Paths.get(System.getProperty("user.home"), "Library", "Caches", "Graviton Browser")
    },
    WIN {
        override val classPathDelimiter: Char = ';'
        override val appCacheDirectory: Path
            get() = Paths.get(System.getenv("LOCALAPPDATA"), "GravitonBrowser", "Cache")
    },
    LINUX {
        override val appCacheDirectory: Path
            get() {
                return if (System.getenv("XDG_CACHE_HOME").isNullOrBlank()) {
                    Paths.get(System.getProperty("user.home"), ".cache", "GravitonBrowser")
                } else {
                    Paths.get(System.getenv("XDG_CACHE_HOME"), "GravitonBrowser")
                }
            }
    },
    UNKNOWN {
        override val appCacheDirectory: Path
            get() = error("Unreachable")
    };

    abstract val appCacheDirectory: Path
    open val classPathDelimiter: Char = ':'
}

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

/** Uses [System.nanoTime] to measure elapsed time and exposes it in seconds to 1/10th of a second precision. */
class Stopwatch {
    private val start = System.nanoTime()
    val elapsedInSec: Double get() = (System.nanoTime() - start) / 100000000 / 10.0
}