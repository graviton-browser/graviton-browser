import kotlinx.cinterop.*
import platform.posix.*
import kotlin.math.max

fun main(args: Array<String>) {
    val highestVersionFound = findHighestVersion()
    val exeDir = "$fullBinaryPath/$highestVersionFound"
    val exePath = "$exeDir/$exeFile"
    setenv("GRAVITON_PATH", fullBinaryPath, 1)
    setenv("GRAVITON_EXE", exePath, 1)
    setenv("GRAVITON_VERSION", "$highestVersionFound", 1)
    if (execl(exePath, exePath, *args, null) == -1) {
        printf("bootstrap: Could not start $exePath\n")
        perror("bootstrap")
    }
}

fun String.chop(): String {
    val pathSepIndex = lastIndexOf('/')
    if (pathSepIndex == -1)
        throw RuntimeException("Could not locate containing directory of $this")
    return subSequence(0, pathSepIndex).toString()
}

private fun findHighestVersion(): Int {
    // Search the current directory for directories that are pure integer names.
    val d = opendir(fullBinaryPath) ?: throw RuntimeException("Could not open current directory")
    var highestVersionFound = 0
    while (true) {
        val entry = (readdir(d) ?: break).pointed
        // Skip entries that don't have integer names. We can't use the d_type field because it's not
        // always set on every filesystem.
        val entryName = entry.d_name.toKString()
        if (entryName.any { !it.isDigit() }) continue
        // Is this higher than any version number found so far?
        val versionNumber = entryName.toInt()
        highestVersionFound = max(highestVersionFound, versionNumber)
    }
    closedir(d)
    check(highestVersionFound > 0) { "Could not locate versioned directories" }
    return highestVersionFound
}
