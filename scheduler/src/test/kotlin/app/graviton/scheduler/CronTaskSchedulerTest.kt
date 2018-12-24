package app.graviton.scheduler

import app.graviton.scheduler.internal.toCronExpression
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration

/**
 *
 * @author Anindya Chatterjee
 */
class CronTaskSchedulerTest {

    @Test
    fun testCronTabExpression() {
        assertEquals(Duration.ofSeconds(5 * 24 * 60 * 60).toCronExpression(), "0 0 */5 * *")
        assertEquals(Duration.ofSeconds(1 * 24 * 60 * 60).toCronExpression(), "0 0 */1 * *")
        assertEquals(Duration.ofSeconds(2 * 60 * 60).toCronExpression(), "0 */2 * * *")
        assertEquals(Duration.ofSeconds(60 * 60).toCronExpression(), "0 */1 * * *")
        assertEquals(Duration.ofSeconds(6 * 60).toCronExpression(), "*/6 * * * *")
        assertEquals(Duration.ofSeconds(60).toCronExpression(), "*/1 * * * *")
        assertEquals(Duration.ofSeconds(65).toCronExpression(), "*/1 * * * *")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDuration() {
        assertEquals(Duration.ofSeconds(10).toCronExpression(), "* * * * *")
    }
}
