import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.osx.PROC_PIDPATHINFO_MAXSIZE
import platform.osx.proc_pidpath
import platform.posix.getpid

/** Returns the path to the executing binary. */
val fullBinaryPath: String
    get() {
        memScoped {
            val length = PROC_PIDPATHINFO_MAXSIZE
            val pathBuf = allocArray<ByteVar>(length)
            val myPid = getpid()
            val res = proc_pidpath(myPid, pathBuf, length)
            if (res < 1)
                throw RuntimeException("proc_pidpath failed: $res")
            // TODO: Do we need to use realpath here to resolve symlinks?
            //
            // We go up two levels because we expect to be found in
            // "Graviton.app/Contents/MacOS/Graviton Browser" and we want
            // to search from the Contents directory.
            return pathBuf.toKString().chop().chop()
        }
    }

val exeFile: String get() = "Contents/MacOS/Graviton Browser"