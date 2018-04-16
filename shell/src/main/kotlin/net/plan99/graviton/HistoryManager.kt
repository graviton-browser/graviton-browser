package net.plan99.graviton

import com.esotericsoftware.yamlbeans.YamlConfig
import com.esotericsoftware.yamlbeans.YamlReader
import com.esotericsoftware.yamlbeans.YamlWriter
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread

/**
 * A [HistoryManager] handles history tracking, background version refresh and completion of coordinates to more precise
 * forms based on history and locally resolved versions. It's a part of how we keep Graviton apps fast; we pre-fetch
 * the latest versions of recently used apps in batch jobs in the background, invoked by the OS scheduler.
 *
 * The history file format is Yaml and we make a bit of effort to make it easy to read and hand edit, as it's so
 * simple. Normally I'd just use a binary format and object serialisation but for a small structure like this it's
 * no big deal - we don't expect it to get huge unless one day we take over the world!
 */
class HistoryManager(storagePath: Path,
                     private val refreshInterval: Duration = Duration.ofHours(24),
                     val maxHistorySize: Int = 20,
                     var clock: Clock = Clock.systemDefaultZone()) {
    companion object : Logging() {
        fun create(): HistoryManager = HistoryManager(currentOperatingSystem.appCacheDirectory)

        // If we don't do this then we get ugly and unnecessary !java.util.LinkedHashMap type tags, or
        // we have to use HashMap and then the keys are stored in random order.
        private val yamlConfig = YamlConfig().also {
            it.writeConfig.setWriteClassname(YamlConfig.WriteClassName.NEVER)
            it.setAllowDuplicates(false)
        }
    }

    // Sorted newest to oldest.
    private val _history = arrayListOf<HistoryEntry>()
    val history: List<HistoryEntry> get() = _history.toList()

    // Version 1, Yaml format, .txt to make it easy to double click.
    private val historyFile = storagePath / "history.1.yaml.txt"

    // TODO: YamlBeans isn't so great in the end. Switch to a similar format that's home grown.

    init {
        if (Files.exists(historyFile)) {
            val yaml = String(Files.readAllBytes(historyFile))
            val reader = YamlReader(yaml, yamlConfig)
            while (true) {
                @Suppress("UNCHECKED_CAST")
                val m = reader.read() as? Map<String, *> ?: break
                try {
                    val userInput = m["coordinate"]!! as String
                    val lastRunTime = Instant.parse(m["last refresh time"]!! as String)
                    val resolvedArtifact = DefaultArtifact(m["resolved artifact"]!! as String)
                    val classPathEntries = m["classpath"]!! as String
                    _history += HistoryEntry(userInput, lastRunTime, resolvedArtifact, classPathEntries)
                    if (_history.size == maxHistorySize) break
                } catch (e: Throwable) {
                    warn { "Skipping un-parseable map, probably there's a missing key: $e" }
                }
            }
        }
    }

    /**
     * Inserts or updates the history table for the given entry. If the user input matches an existing entry it's
     * details are changed to reflect the given entry, the timestamp is set to the current time, and it's moved to
     * the front. Otherwise the new entry pushes the oldest entry off the list.
     */
    fun recordHistoryEntry(entry: HistoryEntry): HistoryEntry {
        val toWrite = maybeTouchExistingEntry(entry) ?: entry
        info { "Recording history entry: $toWrite" }

        _history.add(0, toWrite)
        if (_history.size > maxHistorySize) {
            val removed = _history.removeAt(_history.lastIndex)
            info { "Forgetting old history entry $removed because we have more than $maxHistorySize entries" }
        }

        // Do the writing on a background thread to get out of the way of startup.
        val snapshot = history
        thread {
            writeToFile(snapshot)
        }
        return toWrite
    }

    private fun writeToFile(snapshot: List<HistoryEntry>) {
        val stringWriter = StringWriter()
        val yaml = YamlWriter(stringWriter, yamlConfig)
        for (e in snapshot) {
            fun <K, V> map(vararg pairs: Pair<K, V>): Map<K, V> = pairs.toMap(HashMap(pairs.size))
            yaml.write(map(
                    "coordinate" to e.coordinateFragment,
                    "last refresh time" to DateTimeFormatter.ISO_INSTANT.format(e.lastRefreshTime),
                    "resolved artifact" to e.resolvedArtifact.toString(),
                    "classpath" to e.classPath
            ))
        }
        yaml.close()
        val str = stringWriter.toString()
        Files.write(historyFile, str.toByteArray())
    }

    private fun maybeTouchExistingEntry(entry: HistoryEntry): HistoryEntry? {
        val i = _history.indexOfFirst { it.coordinateFragment == entry.coordinateFragment }
        if (i == -1) return null
        val copy = HistoryEntry(entry.coordinateFragment, clock.instant(), entry.resolvedArtifact, entry.classPath)
        _history.removeAt(i)
        return copy
    }

    /**
     * Given a user input, matches it against the history list to locate the last fully resolved artifact coordinates
     * we used.
     *
     * @return the artifact, or null if not found or if the history entry is too old.
     */
    fun search(packageName: String): HistoryEntry? {
        info { "Searching for a cached resolution in our history list for '$packageName'" }
        val sameCoordinates = history.find {
            val otherCli = GravitonCLI.parse(it.coordinateFragment)
            try {
                otherCli.packageName!![0] == packageName
            } catch (e: Exception) {
                false
            }
        } ?: return null
        val (age, tooOld) = ageCheck(sameCoordinates)
        if (tooOld) {
            info { "Found a history entry match for $packageName but it's too old (${age.toSeconds()} secs)" }
            return null
        }
        return sameCoordinates
    }

    private fun ageCheck(entry: HistoryEntry): Pair<Duration, Boolean> {
        val age = Duration.between(clock.instant(), entry.lastRefreshTime).abs()
        return Pair(age, age > refreshInterval)
    }

    /**
     * Re-resolves every item in the history list, to check for and download updates.
     */
    suspend fun refreshRecentlyUsedApps(codeFetcher: CodeFetcher) {
        for ((index, entry) in history.withIndex()) {
            val (age, old) = ageCheck(entry)
            if (old) {
                info { "Refreshing entry $index: $entry" }
                try {
                    val fetch: CodeFetcher.Result = codeFetcher.downloadAndBuildClasspath(entry.coordinateFragment)
                    val newEntry = entry.copy(lastRefreshTime = clock.instant(), resolvedArtifact = fetch.name, classPath = fetch.classPath)
                    _history[index] = newEntry
                } catch (e: Exception) {
                    logger.error("Failed to refresh ${entry.coordinateFragment}, skipping", e)
                    continue
                }
                writeToFile(_history)
            } else {
                info { "We refreshed ${entry.coordinateFragment} ${age.seconds} seconds ago, skipping" }
            }
        }
    }
}

/**
 * An entry in the history list.
 *
 * @property coordinateFragment The coordinate the user actually typed in, may be incomplete or mangled in some way.
 * @property lastRefreshTime When the servers were last polled for the latest version.
 * @property resolvedArtifact What we fully resolved the user's input to last time.
 * @property classPath Separated by the OS local separator.
 */
data class HistoryEntry(
        val coordinateFragment: String,
        val lastRefreshTime: Instant,
        val resolvedArtifact: Artifact,
        val classPath: String
) {
    private val splitCP get() = classPath.split(currentOperatingSystem.classPathDelimiter)
    override fun toString() = "$coordinateFragment -> $resolvedArtifact @ $lastRefreshTime (${splitCP.size} cp entries)"
}