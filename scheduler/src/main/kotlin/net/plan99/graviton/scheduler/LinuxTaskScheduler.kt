package net.plan99.graviton.scheduler

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/**
 * Schedules a task on Linux using crontab.
 *
 * @author Anindya Chatterjee
 */
class LinuxTaskScheduler : OSTaskScheduler() {
    override fun register(taskName: String, task: OSScheduledTaskDefinition) {
        require(Files.isRegularFile(task.executePath)) { "Cannot access ${task.executePath}" }
        val cronExpression = task.frequency.toCronExpression()
        execute("/bin/bash", "-c", "(crontab -l ; echo \"$cronExpression ${task.executePath}  #$taskName\") 2>&1 | grep -v \"no crontab\" | uniq | crontab -")
    }

    override fun deregister(taskName: String) {
        execute("/bin/bash", "-c", "crontab -l | grep -v '#$taskName' | uniq | crontab -")
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            LinuxTaskScheduler().register("Graviton Browser Auto Update", OSScheduledTaskDefinition(
                    Paths.get("/tmp/GravitonBrowser"),
                    listOf("--a", "--b"),
                    networkSensitive = true,
                    frequency = Duration.ofMinutes(1),
                    description = "Graviton Browser online update tasks. Do not disable this, if you do the app may become out of date and insecure."
            ))
            LinuxTaskScheduler().deregister("Graviton Browser Auto Update")
        }
    }
}

internal fun Duration.toMinutesPart(): Int = this.toMinutes().toInt() % 60
internal fun Duration.toHoursPart(): Int = this.toHours().toInt() % 24
internal fun Duration.toDaysPart(): Long = this.toDays()

/**
 * Converts a [Duration] to an unix-style crontab expression.
 *
 * */
internal fun Duration.toCronExpression(): String {
    var timeSet = false
    val expression = StringBuilder()

    if (this.seconds < 60) throw IllegalArgumentException("duration must be greater than 1 minute")

    if (this.toMinutesPart() == 0 || this.toHoursPart() != 0) {
        expression.append("0 ")
    } else {
        expression.append("*/${this.toMinutesPart()} * * * *")
        timeSet = true
    }

    if (!timeSet) {
        if (this.toHoursPart() == 0 || this.toDaysPart() != 0L) {
            expression.append("0 ")
        } else {
            expression.append("*/${this.toHoursPart()} * * *")
            timeSet = true
        }
    }

    if (!timeSet) {
        if (this.toDaysPart() != 0L) {
            expression.append("*/${this.toDaysPart()} * *")
        }
    }

    return expression.toString()
}