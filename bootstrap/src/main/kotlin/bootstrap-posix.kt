import kotlinx.cinterop.*
import platform.posix.*
import kotlin.math.max

fun main(args: Array<String>) {
    var highestVersionFound = findHighestVersion()
    val exePath = "$fullBinaryPath/$highestVersionFound/Contents/MacOS/Graviton Browser"
    setenv("GRAVITON_PATH", fullBinaryPath, 1)
    setenv("GRAVITON_VERSION", "$highestVersionFound", 1)
    if (execl(exePath, exePath, *args, null) == -1)
        perror("bootstrap")
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
        // Skip entries that aren't directories.
        if (entry.d_type != DT_DIR.toByte()) continue
        // Skip entries that don't have integer names.
        val entryName = entry.d_name.toKString()
        if (entryName.any { !it.isDigit() }) continue
        // Is this higher than any version number found so far?
        val versionNumber = entryName.toInt()
        highestVersionFound = max(highestVersionFound, versionNumber)
    }
    closedir(d)
    return highestVersionFound
}
