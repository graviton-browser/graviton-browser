package app.graviton.shell

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Files.newInputStream
import java.nio.file.Files.size
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackgroundUpdatesTest : TestWithFakeJRE() {
    @Test
    fun checkWinVer() {
        withOverriddenOperatingSystem(OperatingSystem.WIN) {
            withServer(root / "unused", windowsV3Missing = true) { baseUrl ->
                // Firstly check the version number was understood.
                val client = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                val updates = BackgroundUpdates()
                val results: BackgroundUpdates.ControlFileResults = updates.fetchControlData(baseUrl.uri(), 99, client)
                assertEquals(3, results.verNum)
                // But, it's not on the server.
                assertFailsWith<HTTPRequestException> {
                    doCheck(updates, baseUrl, 1)
                }
            }
        }
    }

    @Test
    fun serveUpdateMac() {
        withOverriddenOperatingSystem(OperatingSystem.MAC) {
            withServer(testUpdate.jar) { baseUrl ->
                val updates = BackgroundUpdates()
                assertEquals(BackgroundUpdates.Result.AlreadyFresh, doCheck(updates, baseUrl, 2))
                val result = doCheck(updates, baseUrl, 1)
                assertEquals(BackgroundUpdates.Result.UpdatedTo(2), result)
                assertTrue(Files.exists(root / "install" / "Contents" / "2" / "Contents" / "MacOS" / "Graviton Browser"))
            }
        }
    }

    @Test
    fun serveUpdateWin() {
        // We'll just use a dummy file instead of a real EXE and mock out the execution here.
        val fs = Jimfs.newFileSystem(Configuration.windows())
        val path = fs.rootDirectories.first() / "dummy-file"
        Files.write(path, listOf("nothing here"))

        withOverriddenOperatingSystem(OperatingSystem.WIN) {
            withServer(path) { baseUrl ->
                val updates = object : BackgroundUpdates(fs = fs) {
                    override fun executeProcess(pb: ProcessBuilder): Int {
                        val cmd = pb.command().joinToString(" ")
                        assertTrue(cmd.endsWith(".update.exe /VERYSILENT /DIR=\"C:\\Users\\Bob Smith\\AppData\\Local\\Graviton\" /NORESTART /NOICONS /SUPPRESSMSGBOXES"), cmd)
                        return 0
                    }
                }
                assertEquals(BackgroundUpdates.Result.AlreadyFresh, doCheck(updates, baseUrl, 3))
                val result = updates.checkForGravitonUpdate(
                        2,
                        (fs.rootDirectories.first() / "Users" / "Bob Smith" / "AppData" / "Local" / "Graviton").createDirectories(),
                        baseURL = baseUrl.uri(),
                        signingPublicKey = pub1
                )
                assertEquals(BackgroundUpdates.Result.UpdatedTo(3), result)
            }
        }
    }

    @Test
    fun canParseMikesKey() {
        BackgroundUpdates.mikePubKey
    }

    @Test
    fun diskSpaceCheck() {
        // We could re-create the jimfs with a small size, but it's easier to just change the required disk space
        // free threshold and then re-run the check.
        val updates = BackgroundUpdates(requiredFreeSpaceMB = 8000)  // JimFS default size is 4 GB
        withServer(testUpdate.jar) { baseUrl ->
            assertEquals(BackgroundUpdates.Result.InsufficientDiskSpace, doCheck(updates, baseUrl, 1))
        }
    }

    private fun startServer(updatePath: Path, windowsV3Missing: Boolean): Pair<HttpServer, HttpUrl> {
        val port = 8888
        val httpServer: HttpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
        val baseUrl = HttpUrl.Builder()
                .host(httpServer.address.hostString)
                .port(port)
                .scheme("http")
                .build()
        Files.write(root / "control-file-mac", "Latest-Version-URL: /2.mac.update.jar".toByteArray())
        Files.write(root / "control-file-win", "Latest-Version-URL: /3.win.update.exe".toByteArray())
        Files.write(root / "control-file-bad", "<html>some garbage that won't parse</html".toByteArray())
        serveFile(httpServer, root / "control-file-mac", "/mac/control")
        serveFile(httpServer, root / "control-file-win", "/win/control")
        serveFile(httpServer, root / "control-file-bad", "/linux/control")
        serveFile(httpServer, updatePath, "/2.mac.update.jar")

        if (windowsV3Missing) {
            // Oops, we forgot to put the latest version for Windows on the server, let's test that case.
            serveFile(httpServer, updatePath, "/2.win.update.exe")
        } else {
            serveFile(httpServer, updatePath, "/3.win.update.exe")
        }

        httpServer.executor = Executors.newSingleThreadExecutor()
        httpServer.start()
        return Pair(httpServer, baseUrl)
    }

    private fun serveFile(httpServer: HttpServer, updatePath: Path, url: String) {
        httpServer.createContext(url) { exchange ->
            try {
                exchange.sendResponseHeaders(200, size(updatePath))
                newInputStream(updatePath).use { stream ->
                    stream.copyTo(exchange.responseBody)
                }
            } finally {
                exchange.close()
            }
        }
    }

    private fun doCheck(updates: BackgroundUpdates, baseUrl: HttpUrl, currentVersion: Int): BackgroundUpdates.Result {
        return updates.checkForGravitonUpdate(
                currentVersion,
                (root / "install" / "Contents").createDirectories(),
                baseURL = baseUrl.uri(),
                signingPublicKey = pub1
        )
    }

    private fun withServer(target: Path, windowsV3Missing: Boolean = false, body: (HttpUrl) -> Unit) {
        val (server: HttpServer, baseUrl: HttpUrl) = startServer(target, windowsV3Missing)
        try {
            body(baseUrl)
        } finally {
            server.stop(0 /* seconds */)
        }
    }
}