package net.plan99.graviton

import java.nio.file.Path
import java.nio.file.Paths

fun String.toPath(): Path = Paths.get(this)
operator fun Path.div(other: String): Path = resolve(other)

enum class OperatingSystem {
    MAC {
        override val appCacheDirectory: Path
            get() = Paths.get(System.getProperty("user.home"), "Library", "Caches", "Graviton Browser")
    },
    WIN {
        override val appCacheDirectory: Path
            get() = Paths.get(System.getenv("LOCALAPPDATA"), "GravitonBrowser", "Cache")
    },
    LINUX {
        override val appCacheDirectory: Path
            get() = Paths.get(System.getProperty("user.home"), ".graviton", "Cache")
    },
    UNKNOWN {
        override val appCacheDirectory: Path
            get() = error("Unreachable")
    };

    abstract val appCacheDirectory: Path
}

val currentOperatingSystem: OperatingSystem by lazy {
    val name = System.getProperty("os.name").toLowerCase()
    when {
        name.contains("win") -> OperatingSystem.WIN
        name.contains("mac") -> OperatingSystem.MAC
        name.contains("linux") -> OperatingSystem.LINUX
        else -> OperatingSystem.UNKNOWN
    }
}

val Throwable.rootCause: Throwable get() {
    var t: Throwable = this
    while (t.cause != null) t = t.cause!!
    return t
}