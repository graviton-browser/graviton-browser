package net.plan99.graviton.scheduler

import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

open class WindowsTaskScheduler : OSTaskScheduler() {
    // Because this is Microsoft, everything about the Windows Task Scheduler is broken and useless.
    //
    // We will install and remove the task using the "schtasks" command line tool. This tool has several quirks:
    //
    // - It expects the file to be encoded in UTF-8 but to claim in its XML header it's encoded in UTF-16. No other
    //   combination appears to work: actually matched header and encoding results in an inscrutable error.
    // - The obvious way to schedule is to use a logon trigger + a repetition interval. This fails with "Access denied"
    //   and no other information. You could do a UAC elevation and then there's no error anymore, but nothing happens
    //   either. Calendar triggers do NOT fail with access denied, so that's what we use.
    // - Calendar triggers don't let you specify an interval in seconds. You have to specify an interval in hours or
    //   days. The syntax is totally different depending on what time interval is picked.
    // - You have to specify a start boundary, even though the only one that makes real sense is "now".
    // - There's a <Principal> element that theoretically should be present, but trying to specify anything in it - even
    //   items exported by the Task Manager GUI tool - just yields an obscure and useless parse error indicating that
    //   something wasn't right, pointing to the last character of the file.
    private val xml = """
        <?xml version="1.0" encoding="UTF-16"?>
        <Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
          <RegistrationInfo>
            <Version>1.0</Version>
            <Description>%DESCRIPTION%</Description>
            <URI>\%TASKNAME%</URI>
          </RegistrationInfo>
          <Triggers>
            <CalendarTrigger>
              <StartBoundary>2018-04-01T20:00:00</StartBoundary>
              <Enabled>true</Enabled>
              <ScheduleByDay>
                <DaysInterval>%DAYS_INTERVAL%</DaysInterval>
              </ScheduleByDay>
            </CalendarTrigger>
          </Triggers>
          <Settings>
            <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
            <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
            <StopIfGoingOnBatteries>true</StopIfGoingOnBatteries>
            <AllowHardTerminate>true</AllowHardTerminate>
            <StartWhenAvailable>true</StartWhenAvailable>
            <RunOnlyIfNetworkAvailable>%NETWORK_SENSITIVE%</RunOnlyIfNetworkAvailable>
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
              <Command>%COMMAND_PATH%</Command>
              <Arguments>%ARGUMENTS%</Arguments>
            </Exec>
          </Actions>
        </Task>
    """.trimIndent()

    override fun register(taskName: String, task: OSScheduledTaskDefinition) {
        val windowsStyleTaskName = convertDNSNameToTaskName(taskName)
        val xml = formatXML(windowsStyleTaskName, task)
        val tmpDir = Paths.get(System.getenv("TEMP"))
        check(Files.exists(tmpDir))
        val xmlFile = tmpDir.resolve("update-task.xml")
        try {
            Files.write(xmlFile, xml.toByteArray())
            execute("schtasks.exe", "/create", "/xml", "\"${xmlFile.toAbsolutePath()}\"", "/tn", "\"$windowsStyleTaskName\"")
        } finally {
            Files.deleteIfExists(xmlFile)
        }
    }

    internal fun formatXML(windowsStyleTaskName: String, task: OSScheduledTaskDefinition): String {
        check(task.frequency.seconds == task.frequency.toDaysPart() * 86400) {
            "Currently only schedule frequencies that are whole numbers of days are supported due to quirks in the Windows Task Scheduler (see comments at the top of WindowsTaskScheduler.kt)"
        }
        return xml
                .replace("%TASKNAME%", windowsStyleTaskName)
                .replace("%DESCRIPTION%", task.description ?: "No description available")
                .replace("%NOW%", Instant.now().toString())
                .replace("%DAYS_INTERVAL%", task.frequency.toDaysPart().toString())
                .replace("%NETWORK_SENSITIVE%", task.networkSensitive.toString())
                .replace("%COMMAND_PATH%", task.executePath.toAbsolutePath().toString())
                .replace("%ARGUMENTS%", task.arguments.joinToString(" "))
    }

    // Converts from e.g. graviton.browser.app to GravitonBrowserApp
    private fun convertDNSNameToTaskName(taskName: String): String {
        var components = taskName.split('.')
        if (components[0] in setOf("com", "net", "org", "co", "io"))
            components = components.drop(1)
        return components.joinToString(" ") { it.capitalize() }
    }

    override fun deregister(taskName: String) {
        val windowsStyleTaskName = convertDNSNameToTaskName(taskName)
        try {
            execute("c:\\Windows\\System32\\schtasks.exe", "/delete", "/f", "/tn", "\"$windowsStyleTaskName\"")
        } catch (e: SubprocessException) {
            if (e.subprocessMessage.toLowerCase().contains("the system cannot find the file specified"))
                throw UnknownTaskException(e.subprocessMessage)
            else
                throw e
        }
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            WindowsTaskScheduler().register("net.plan99.graviton.browser.update", OSScheduledTaskDefinition(
                    Paths.get("c:\\Users\\mike\\AppData\\Local\\GravitonBrowser\\GravitonBrowser.exe"),
                    listOf("--a", "--b"),
                    networkSensitive = true,
                    description = "Graviton Browser online update tasks. Do not disable this, if you do the app may become out of date and insecure."
            ))
//            WindowsTaskScheduler().deregister("net.plan99.graviton.browser.update")
        }
    }
}