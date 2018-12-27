@file:Suppress("EXTENSION_SHADOWED_BY_MEMBER")

package app.graviton.scheduler

import app.graviton.scheduler.internal.toCronExpression
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/**
 * Schedules a task on Linux using crontab.
 *
 * @author Anindya Chatterjee
 */
class CronTaskScheduler : OSTaskScheduler() {
    override fun register(taskName: String, task: OSScheduledTaskDefinition) {
        require(Files.isRegularFile(task.executePath)) { "Cannot access ${task.executePath}" }
        val cronExpression = task.frequency.toCronExpression()
        val arguments = task.arguments.joinToString(" ")
        execute("/bin/bash", "-c",
                "(crontab -l ; echo \"$cronExpression ${task.executePath} $arguments     # $taskName - ${task.description}\") 2>&1 | grep -v \"no crontab\" | uniq | crontab -"
        )
    }

    override fun deregister(taskName: String) {
        execute("/bin/bash", "-c", "crontab -l | grep -v '# $taskName' | uniq | crontab -")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CronTaskScheduler().register("Graviton Browser Auto Update", OSScheduledTaskDefinition(
                    Paths.get("/tmp/GravitonBrowser"),
                    listOf("--a", "--b"),
                    networkSensitive = true,
                    frequency = Duration.ofMinutes(1),
                    description = "Graviton Browser online update tasks. Do not disable this, if you do the app may become out of date and insecure."
            ))
            CronTaskScheduler().deregister("Graviton Browser Auto Update")
        }
    }
}