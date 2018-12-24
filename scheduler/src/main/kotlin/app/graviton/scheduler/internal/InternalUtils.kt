package app.graviton.scheduler.internal

import java.time.Duration

/**
 * Converts a [Duration] to an unix-style crontab expression.
 */
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

// Java 8 compatibility
fun Duration.toMinutesPart(): Int = this.toMinutes().toInt() % 60
fun Duration.toHoursPart(): Int = this.toHours().toInt() % 24
fun Duration.toDaysPart(): Long = this.toDays()