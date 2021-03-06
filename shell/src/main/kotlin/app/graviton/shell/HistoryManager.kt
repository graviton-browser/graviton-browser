package app.graviton.shell

import app.graviton.codefetch.CodeFetcher
import com.esotericsoftware.yamlbeans.YamlConfig
import com.esotericsoftware.yamlbeans.YamlReader
import com.esotericsoftware.yamlbeans.YamlWriter
import javafx.collections.FXCollections
import javafx.collections.ObservableList
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
                     var clock: Clock = Clock.systemUTC(),
                     val blocking: Boolean = false) {
    companion object : Logging() {
        fun create(): HistoryManager = HistoryManager(commandLineArguments.cachePath.toPath())

        // If we don't do this then we get ugly and unnecessary !java.util.LinkedHashMap type tags, or
        // we have to use HashMap and then the keys are stored in random order.
        private val yamlConfig = YamlConfig().also {
            it.writeConfig.setWriteClassname(YamlConfig.WriteClassName.NEVER)
            it.setAllowDuplicates(false)
        }
    }

    // Sorted newest to oldest.
    val history: ObservableList<HistoryEntry> = FXCollections.observableArrayList()

    // Version 1, Yaml format, .txt to make it easy to double click.
    private val historyFile = storagePath / "history.1.yaml.txt"

    // TODO: YamlBeans isn't so great in the end. Switch to a similar format that's home grown.

    init {
        info { "Graviton cache is $storagePath" }
        if (Files.exists(historyFile)) {
            try {
                readFromFile(historyFile)
                info { "Read ${history.size} entries from the history list" }
            } catch (e: Exception) {
                logger.warn("Failed to read history file", e)     // Not ideal but we don't want to brick ourselves.
            }
        }
    }

    private fun readFromFile(historyFile: Path) {
        val yaml = String(Files.readAllBytes(historyFile))
        readFromFile(yaml)
    }

    private fun readFromFile(yaml: String) {
        val reader = YamlReader(yaml, yamlConfig)
        while (true) {
            @Suppress("UNCHECKED_CAST")
            val m = reader.read() as? Map<String, *> ?: break
            try {
                val userInput = m["coordinate"]!! as String
                val lastRunTime = Instant.parse(m["last refresh time"]!! as String)
                val resolvedArtifact = m["resolved artifact"]!! as String
                val classPathEntries = m["classpath"]!! as String
                val name = m["name"]!! as String
                val description = m["description"] as? String?
                history += HistoryEntry(userInput, lastRunTime, resolvedArtifact, classPathEntries, name, description)
                if (history.size == maxHistorySize) break
            } catch (e: Throwable) {
                warn { "Skipping un-parseable map, probably there's a missing key: $e" }
            }
        }
    }

    private fun writeToFile(snapshot: List<HistoryEntry>) {
        val stringWriter = StringWriter()

        if (!snapshot.isEmpty()) {
            // TODO Use try-with-resources or similar
            val yaml = YamlWriter(stringWriter, yamlConfig)
            for (e in snapshot) {
                fun <K, V> map(vararg pairs: Pair<K, V>): HashMap<K, V> = pairs.toMap(HashMap(pairs.size))
                val map = map(
                        "coordinate" to e.coordinateFragment,
                        "last refresh time" to DateTimeFormatter.ISO_INSTANT.format(e.lastRefreshTime),
                        "resolved artifact" to e.resolvedArtifact,
                        "classpath" to e.classPath,
                        "name" to e.name
                )
                if (e.description != null)
                    map["description"] = e.description
                yaml.write(map)
            }
            yaml.close()
        }
        val str = stringWriter.toString()
        historyFile.parent.createDirectories()
        Files.write(historyFile, str.toByteArray())
    }

    /**
     * Inserts or updates the history table for the given entry. If the user input matches an existing entry it's
     * details are changed to reflect the given entry, the timestamp is set to the current time, and it's moved to
     * the front. Otherwise the new entry pushes the oldest entry off the list.
     */
    fun recordHistoryEntry(entry: HistoryEntry): HistoryEntry {
        // Maybe remove the existing entry from the list if found, so we can replace it with the new one.
        indexFromUserInput(entry.coordinateFragment)?.let { history.removeAt(it) }

        info { "Recording history entry: $entry" }

        history.add(0, entry)
        if (history.size > maxHistorySize) {
            val removed = history.removeAt(history.lastIndex)
            info { "Forgetting old history entry $removed because we have more than $maxHistorySize entries" }
        }

        writeHistory()

        return entry
    }

    private fun writeHistory() {
        // Do the writing on a background thread to get out of the way of startup.
        val snapshot = history.toList()
        if (blocking) {
            writeToFile(snapshot)
        } else {
            thread {
                writeToFile(snapshot)
            }
        }
    }

    fun removeEntry(entry: HistoryEntry) {
        val found = history.remove(entry)
        if (found) {
            writeHistory()
        }
    }

    data class LookupResult(val entry: HistoryEntry, val old: Boolean, val age: Duration)

    /**
     * Given a user input, matches it against the history list to locate the last fully resolved artifact coordinates
     * we used.
     *
     * @return the artifact, or null if not found or if the history entry is too old.
     */
    fun search(packageName: String): LookupResult? {
        info { "Searching for a cached resolution in our history list for '$packageName'" }
        val i = indexFromUserInput(packageName) ?: return null
        val sameCoordinates: HistoryEntry = history[i]!!
        val (age, tooOld) = ageCheck(sameCoordinates)
        return LookupResult(sameCoordinates, tooOld, age)
    }

    private fun indexFromUserInput(packageName: String): Int? {
        val lowerCase = packageName.toLowerCase()
        val i = history.indexOfFirst {
            try {
                val otherCli = GravitonCLI.parse(it.coordinateFragment)
                otherCli.packageName!![0].toLowerCase() == lowerCase
            } catch (e: Exception) {
                false
            }
        }
        return if (i == -1) null else i
    }

    private fun ageCheck(entry: HistoryEntry): Pair<Duration, Boolean> {
        val age = Duration.between(clock.instant(), entry.lastRefreshTime).abs()
        return Pair(age, age > refreshInterval)
    }

    /**
     * Re-resolves every item in the history list, to check for and download updates.
     */
    fun refreshRecentlyUsedApps(appLauncher: AppLauncher) {
        for ((index, entry) in history.withIndex()) {
            val (age, old) = ageCheck(entry)
            if (old) {
                info { "Refreshing entry $index: $entry" }
                try {
                    refresh(appLauncher, entry)
                } catch (e: Exception) {
                    logger.error("Failed to refresh ${entry.coordinateFragment}, skipping", e)
                }
            } else {
                info { "We refreshed ${entry.coordinateFragment} ${age.seconds} seconds ago, skipping" }
            }
        }
    }

    /**
     * Refreshes the given entry in the history list, updating it and saving the new history file to disk. Blocks
     * the current thread.
     */
    fun refresh(appLauncher: AppLauncher, entry: HistoryEntry): HistoryEntry {
        // TODO: Do we need to take a file lock here to stop a background update happening concurrently, or is a race OK here?
        val index = history.indexOf(entry)
        // Go via the AppLauncher because it knows how to turn the user's input (coordinate fragment) into a full
        // coordinate.
        val fetch: CodeFetcher.Result = appLauncher.lookupOrDownload(entry.coordinateFragment, true)
        val newEntry = entry.copy(lastRefreshTime = clock.instant(), resolvedArtifact = fetch.artifact.toString(), classPath = fetch.classPath)
        history[index] = newEntry
        writeToFile(history)
        return newEntry
    }

    fun clearCache() {
        // TODO: This should synchronise with the repository manager to ensure nothing is downloading at the time.
        val path = commandLineArguments.cachePath.toPath()
        // A bit of sanity checking before we delete stuff.
        check(path !in path.fileSystem.rootDirectories) { "$path is a root directory!" }
        check(!(path / ".bash_history").exists) { "$path appears to be a home directory" }
        info { "Clearing cache: $path" }
        if (!path.toFile().deleteRecursively())
            error { "Failed to clear disk cache" }
        // Now re-write the history data to disk, as it will have been erased. This is a temp hack: we should really
        // be storing the history file and other important user data in a different location to transient caching.
        writeToFile(history)
    }
}

/**
 * An entry in the history list.
 *
 * @property coordinateFragment The coordinate the user actually typed in, may be incomplete or mangled in some way.
 * @property lastRefreshTime When the servers were last polled for the latest version.
 * @property resolvedArtifact What we fully resolved the user's input to last time.
 * @property classPath Separated by the OS local separator.
 * @property name Human readable name of the application or the artifact ID if not specified by the developer.
 * @property description Human readable description of the application, or null if not specified by the developer.
 */
data class HistoryEntry(
        val coordinateFragment: String,
        val lastRefreshTime: Instant,
        val resolvedArtifact: String,
        val classPath: String,
        val name: String,
        val description: String?
) {
    constructor(coordinateFragment: String, fetch: CodeFetcher.Result) :
            this(coordinateFragment, fetch.refreshTime, fetch.artifact.toString(), fetch.classPath, fetch.name, fetch.description)

    private val splitCP get() = classPath.split(currentOperatingSystem.classPathDelimiter)
    override fun toString() = "$coordinateFragment -> $resolvedArtifact @ $lastRefreshTime (${splitCP.size} cp entries)"

    val artifact: Artifact get() = DefaultArtifact(resolvedArtifact).withNameAndDescription(name, description)
}