package net.plan99.graviton.scheduler

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class MacTaskSchedulerTest {
    private val jimfs = Jimfs.newFileSystem(Configuration.osX())
    private val rootPath = jimfs.rootDirectories.first()
    private val appPath: Path = rootPath.resolve("/Applications/Foobar.app/Contents/Home/Foobar")

    init {
        // Create a dummy executable to satisfy the assertions.
        Files.createDirectories(appPath.parent)
        Files.write(appPath, "Hi there".toByteArray())
    }

    private val expectedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>Label</key><string>test.name</string>
                <key>ProgramArguments</key>
                <array>
                    <string>/Applications/Foobar.app/Contents/Home/Foobar</string>
                    <string>--background-launch</string>
                    <string>--foo-bar</string>
                </array>

                <key>RunAtLoad</key><true/>

                <key>StartInterval</key><integer>86400</integer>

                <key>WorkingDirectory</key><string>/Applications/Foobar.app/Contents/Home</string>
            </dict>
            </plist>
        """.trimIndent()


    @Test
    fun checkXML() {
        val xml = MacTaskScheduler().formatXML("test.name", Duration.ofDays(1), appPath,
                listOf("--background-launch", "--foo-bar"))
        assertEquals(expectedXml, xml)
    }

    @Test
    fun register() {
        val scheduler = object : MacTaskScheduler(rootPath) {
            override val homeDirectory: String = "/Users/testuser"
            override fun execute(vararg commandLine: String) {
                assertEquals(listOf("launchctl", "load", "-w", "/Users/testuser/Library/LaunchAgents/test.name.plist"), commandLine.toList())
            }
        }
        scheduler.register("test.name", OSScheduledTaskDefinition(appPath, listOf("--background-launch", "--foo-bar")))
        val xml = String(Files.readAllBytes(rootPath.resolve("/Users/testuser/Library/LaunchAgents/test.name.plist")))
        assertEquals(expectedXml, xml)
    }

    @Test
    fun deregister() {
        lateinit var actualLaunch: List<String>
        val scheduler = object : MacTaskScheduler(rootPath) {
            override val homeDirectory: String = "/Users/testuser"
            override fun execute(vararg commandLine: String) {
                actualLaunch = commandLine.toList()
            }
        }
        scheduler.register("test.name", OSScheduledTaskDefinition(appPath, listOf("--background-launch", "--foo-bar")))
        scheduler.deregister("test.name")
        assertEquals(listOf("launchctl", "unload", "-w", "/Users/testuser/Library/LaunchAgents/test.name.plist"), actualLaunch)
    }
}