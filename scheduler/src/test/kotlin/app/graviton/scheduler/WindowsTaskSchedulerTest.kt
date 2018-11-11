package app.graviton.scheduler

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path
import java.time.Duration

class WindowsTaskSchedulerTest {
    private val jimfs = Jimfs.newFileSystem(Configuration.windows())
    private val rootPath = jimfs.rootDirectories.first()
    private val appPath: Path = rootPath.resolve("c:\\Program Files\\Foo\\Foobar.exe")

    @Test
    fun checkXML() {
        val task = OSScheduledTaskDefinition(appPath, listOf("--a", "--b", "c"), description = "A task description", frequency = Duration.ofDays(2))
        val xml = format(task)
        assertEquals("""
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo>
            <Version>1.0</Version>
            <Description>A task description</Description>
            <URI>\Graviton Browser Update</URI>
          </RegistrationInfo>
          <Triggers>
            <CalendarTrigger>
              <StartBoundary>2018-04-01T20:00:00</StartBoundary>
              <Enabled>true</Enabled>
              <ScheduleByDay>
                <DaysInterval>2</DaysInterval>
              </ScheduleByDay>
            </CalendarTrigger>
          </Triggers>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>true</StopIfGoingOnBatteries>
            <AllowHardTerminate>true</AllowHardTerminate>
            <StartWhenAvailable>true</StartWhenAvailable>
            <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
            <IdleSettings>
              <StopOnIdleEnd>true</StopOnIdleEnd>
              <RestartOnIdle>false</RestartOnIdle>
            </IdleSettings>
            <AllowStartOnDemand>true</AllowStartOnDemand>
            <Enabled>true</Enabled>
            <Hidden>false</Hidden>
            <RunOnlyIfIdle>false</RunOnlyIfIdle>
            <WakeToRun>false</WakeToRun>
            <ExecutionTimeLimit>PT72H</ExecutionTimeLimit>
            <Priority>7</Priority>
          </Settings>
          <Actions Context="Author">
            <Exec>
              <Command>c:\Program Files\Foo\Foobar.exe</Command>
              <Arguments>--a --b c</Arguments>
            </Exec>
          </Actions>
        </Task>
        """.trimIndent(), xml)
    }

    private fun format(task: OSScheduledTaskDefinition) =
            WindowsTaskScheduler().formatXML("Graviton Browser Update", task)

    @Test(expected = IllegalStateException::class)
    fun daysMustBeWhole() {
        format(OSScheduledTaskDefinition(appPath, frequency = Duration.ofHours(2)))
    }

    // The rest was tested manually, as it's tough to mock the task scheduler.
}