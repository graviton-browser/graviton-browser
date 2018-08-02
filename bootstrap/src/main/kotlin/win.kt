import kotlinx.cinterop.*
import platform.windows.*
import platform.posix.*
import kotlin.math.max

typealias WSTR = CPointer<ShortVar>

private fun WSTR.toKString(): String = memScoped {
    // Figure out how much memory we need after UTF-8 conversion.
    val sz = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, this@toKString, -1, null, 0, null, null)
    // Now convert to UTF-8 and from there, a String.
    val utf8 = allocArray<ByteVar>(sz)
    val r = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, this@toKString, -1, utf8, sz, null, null)
    if (r == 0) throw RuntimeException("Could not convert to UTF-8")
    utf8.toKString()
}

private val fullBinaryPath: String by lazy {
    // Get the path to the EXE.
    val hmodule = GetModuleHandleW(null)
    val wstr: WSTR = nativeHeap.allocArray<ShortVar>(MAX_PATH)
    GetModuleFileNameW(hmodule, wstr, MAX_PATH)
    // Strip the filename leaving just the directory.
    PathRemoveFileSpecW(wstr)
    wstr.toKString()
}

fun error(message: String) {
    MessageBoxW(null, message, "Error", MB_OK or MB_ICONEXCLAMATION)
    throw RuntimeException(message)
}

fun findHighestVersion(): Int = memScoped {
    val path = "$fullBinaryPath\\*"
    val findData = alloc<_WIN32_FIND_DATAW>()
    var hFind = FindFirstFileW(path, findData.ptr)
    if (hFind.rawValue.toLong() == -1L) {
        error("Could not list contents of directory $path")
    }
    var highestVersion = 0
    try {
        do {
            val entryName = findData.cFileName.toKString()
            if (entryName.any { !it.isDigit() })
                continue
            val num: Int = entryName.toInt()
            highestVersion = max(highestVersion, num)
        } while (FindNextFileW(hFind, findData.ptr) != 0)
    } finally {
        FindClose(hFind)
    }
    return highestVersion
}

fun main(args: Array<String>) {
    val highestVersionFound = findHighestVersion()
    val execDir = "$fullBinaryPath\\$highestVersionFound"
    val execPath = "$execDir\\GravitonBrowser.exe"
    putenv("GRAVITON_PATH=$fullBinaryPath")
    putenv("GRAVITON_VERSION=$highestVersionFound")
    // TODO: Switch to using CreateProcessEx and inherit the console.
    val r: CPointer<HINSTANCE__>? = ShellExecuteW(null, null, execPath, args.joinToString(" "), execDir, SW_SHOW)
    if (r!!.toLong() <= 32) {
        error("ShellExecuteW returned error code $r: $execPath")
    }
}