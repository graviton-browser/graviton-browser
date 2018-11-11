package app.graviton.scheduler

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * Schedules the given task on macOS using Launch Services. Tasks are identified using Java-style reverse DNS names,
 * using your package or module name as an identifier would be a good idea.
 */
open class MacTaskScheduler(private val rootPath: Path = Paths.get("/")) : OSTaskScheduler() {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key><string>%TASK_NAME%</string>
            <key>ProgramArguments</key>
            <array>
                <string>%BINARY_PATH%</string>
                %ARGUMENTS%
            </array>

            <key>RunAtLoad</key><true/>

            <key>StartInterval</key><integer>%INTERVAL_SECS%</integer>

            <key>WorkingDirectory</key><string>%BINARY_DIR%</string>
        </dict>
        </plist>
    """.trimIndent()

    protected open val homeDirectory: String get() = System.getProperty("user.home").also { check(it.isNotBlank()) }
    private val agentsPath: Path get() = rootPath.resolve("$homeDirectory/Library/LaunchAgents")

    internal fun formatXML(taskName: String, frequency: Duration, executePath: Path, arguments: List<String>): String {
        val validName = taskName.all { (it == '.' || it.isLetterOrDigit()) && !it.isWhitespace() }
        require(validName) { "taskName should be a reverse DNS name like net.plan99.foo.bar, was '$taskName'" }
        val argXml = arguments.joinToString("\n        ") { "<string>$it</string>" }
        return xml
                .replace("%TASK_NAME%", taskName)
                .replace("%BINARY_PATH%", executePath.toAbsolutePath().toString())
                .replace("%INTERVAL_SECS%", frequency.seconds.toString())
                .replace("%BINARY_DIR%", executePath.toAbsolutePath().parent.toString())
                .replace("%ARGUMENTS%", argXml)
    }

    override fun register(taskName: String, task: OSScheduledTaskDefinition) {
        require(Files.isRegularFile(task.executePath)) { "Cannot access ${task.executePath}" }
        val xml = formatXML(taskName, task.frequency, task.executePath, task.arguments)
        Files.createDirectories(agentsPath)
        val toPath = agentsPath.resolve("$agentsPath/$taskName.plist")
        Files.write(toPath, xml.toByteArray())
        execute("launchctl", "load", "-w", toPath.toAbsolutePath().toString())
    }

    override fun deregister(taskName: String) {
        val path = agentsPath.resolve("$taskName.plist")
        if (!Files.isRegularFile(path))
            throw UnknownTaskException("Specified task name is not registered: $taskName: $path")
        execute("launchctl", "unload", "-w", path.toAbsolutePath().toString())
        Files.delete(path)
    }
}