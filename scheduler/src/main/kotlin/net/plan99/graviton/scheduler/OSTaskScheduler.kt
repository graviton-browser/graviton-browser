package net.plan99.graviton.scheduler

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * An [OSTaskScheduler] allows you to register programs to be executed at a chosen frequency by the operating system.
 */
abstract class OSTaskScheduler {
    /**
     * Takes a task name in reverse DNS form (e.g. net.foo.bar) and an [OSScheduledTaskDefinition], and registers it
     * with the operating system task scheduler. Note that not all features in [OSScheduledTaskDefinition] are supported
     * on every operating system.
     */
    abstract fun register(taskName: String, task: OSScheduledTaskDefinition)

    /**
     * De-registers the given task name, or throws if the task isn't known.
     */
    @Throws(UnknownTaskException::class)
    abstract fun deregister(taskName: String)

    companion object {
        /** Returns an [OSTaskScheduler] for the current OS or null if this OS is not supported. */
        @JvmStatic
        fun get(): OSTaskScheduler? {
            val osName = System.getProperty("os.name")
            return when {
                osName.contains("win") -> WindowsTaskScheduler()
                osName.contains("mac") -> MacTaskScheduler()
                else -> null
            }
        }
    }

    protected open fun execute(vararg commandLine: String) {
        val proc = ProcessBuilder(*commandLine).redirectErrorStream(true).start()
        if (!proc.waitFor(10, TimeUnit.SECONDS))
            throw TimeoutException("Timeout waiting for execution of: " + commandLine.joinToString(" "))
        if (proc.waitFor() != 0)
            throw SubprocessException(commandLine.joinToString(" "), String(proc.inputStream.readAllBytes()))
    }
}

/** Thrown if the given task name wasn't found on de-registration. */
class UnknownTaskException(message: String) : Exception(message)

/**
 * A structure describing a scheduled task. Not all feature are supported on all operating systems.
 *
 * @property executePath  A [Path] to a file to be executed, which must exist.
 * @property arguments A list of command line parameters to pass to the executed program.
 * @property frequency How often to run the given scheduled task. Not all frequencies are supported on all operating systems. Default is daily execution.
 * @property description Will appear in the task scheduler UI of the operating system, if there is one.
 * @property networkSensitive If true, the task won't run if there's no network access. Only supported on Windows.
 */
data class OSScheduledTaskDefinition(
        val executePath: Path,
        val arguments: List<String> = emptyList(),
        val frequency: Duration = Duration.ofDays(1),
        val description: String? = null,
        val networkSensitive: Boolean = false
)

/** Thrown if one of the command line tools that's being used fails */
class SubprocessException(val commandLine: String, val subprocessMessage: String) : RuntimeException("Error during execution of $commandLine: $subprocessMessage")