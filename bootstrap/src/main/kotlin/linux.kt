import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.*

/** Returns the path to the executing binary. */
val fullBinaryPath: String
    get() {
        memScoped {
            val length = PATH_MAX.toULong()
            val pathBuf = allocArray<ByteVar>(length.toInt())
            val myPid = getpid()
            val res = readlink("/proc/$myPid/exe", pathBuf, length)
            if (res < 1)
                throw RuntimeException("/proc/$myPid/exe failed: $res")

            return pathBuf.toKString().chop()
        }
    }

val exeFile: String get() = "graviton"

fun prepare() {
    // There is a bug in Conscrypt in which it isn't linked with -pthread as it should be. This is a workaround.
    //
    // https://github.com/google/conscrypt/issues/600
    val current: String = getenv("LD_PRELOAD")?.toKString() ?: ""
    val libs = current.split(':') + listOf("libpthread.so.0")
    val newLDPreload = libs.joinToString(":")
    setenv("LD_PRELOAD", newLDPreload, 1)
}