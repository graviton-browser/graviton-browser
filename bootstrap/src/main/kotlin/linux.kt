import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.PATH_MAX
import platform.posix.getpid
import platform.posix.readlink

/** Returns the path to the executing binary. */
val fullBinaryPath: String
    get() {
        memScoped {
            val length = PATH_MAX.toLong()
            val pathBuf = allocArray<ByteVar>(length)
            val myPid = getpid()
            val res = readlink("/proc/$myPid/exe", pathBuf, length)
            if (res < 1)
                throw RuntimeException("/proc/$myPid/exe failed: $res")

            return pathBuf.toKString().chop()
        }
    }

val exeFile: String get() = "GravitonBrowser"