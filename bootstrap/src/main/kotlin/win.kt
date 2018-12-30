import kotlinx.cinterop.*
import platform.windows.*
import platform.posix.*
import kotlin.math.max

typealias WSTR = CPointer<UShortVar>

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
    val wstr: WSTR = nativeHeap.allocArray<UShortVar>(MAX_PATH)
    GetModuleFileNameW(hmodule, wstr, MAX_PATH)
    // Strip the filename leaving just the directory.
    PathRemoveFileSpecW(wstr)
    wstr.toKString()
}

fun error(message: String) {
    MessageBoxW(null, message, "Error", MB_OK.toUInt() or MB_ICONEXCLAMATION.toUInt())
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
    memScoped {
        val highestVersionFound = findHighestVersion()
        val execDir = "$fullBinaryPath\\$highestVersionFound"
        val execPath = "$execDir\\GravitonBrowser.exe"
        putenv("GRAVITON_PATH=$fullBinaryPath")
        putenv("GRAVITON_EXE=$execPath")
        putenv("GRAVITON_VERSION=$highestVersionFound")

        // Enable VT-100 ANSI escape sequence handling on Windows 10.
        // This enables coloured terminal output for all apps.
        val stdout = GetStdHandle((-11).toUInt())
        var dwMode = alloc<UIntVar>()
        GetConsoleMode(stdout, dwMode.ptr)
        SetConsoleMode(stdout, dwMode.value or 0x0004u or 0x0008u)

        // Start up the versioned binary.
        val startupInfo = alloc<_STARTUPINFOW>()
        startupInfo.cb = sizeOf<_STARTUPINFOW>().toUInt()
        val processInfo = alloc<_PROCESS_INFORMATION>()
        val argStr = "\"$execPath\" " + args.joinToString(" ")

        val result = CreateProcessW(null, argStr.wcstr.ptr, null, null, 1, 0, null, null, startupInfo.ptr, processInfo.ptr)
        if (result == 0) {
            val lastError = GetLastError()
            error("CreateProcess returned $lastError")
        }

        // We may want to wait for it, if there's an attached console. Otherwise we should
        // quit to ensure this binary can be updated in-place by a background update. There's
        // no point in waiting if there's no cmd.com to keep hanging around.
        if (GetConsoleWindow() != null)
            WaitForSingleObject(processInfo.hProcess, INFINITE)
    }
}