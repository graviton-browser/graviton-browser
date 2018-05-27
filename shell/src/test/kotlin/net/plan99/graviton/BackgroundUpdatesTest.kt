package net.plan99.graviton

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
            withServer(root / "unused") { baseUrl ->
                // Firstly check the version number was understood.
                val client = OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                val results: BackgroundUpdates.ControlFileResults = BackgroundUpdates.fetchControlData(baseUrl.uri(), 99, client)
                assertEquals(3, results.verNum)
                // But, it's not on the server.
                assertFailsWith<HTTPRequestException> {
                    doCheck(baseUrl, 1)
                }
            }
        }
    }

    @Test
    fun serveUpdate() {
        withOverriddenOperatingSystem(OperatingSystem.MAC) {
            withServer(testUpdate.jar) { baseUrl ->
                assertEquals(BackgroundUpdates.Result.AlreadyFresh, doCheck(baseUrl, 2))
                val result = doCheck(baseUrl, 1)
                assertEquals(BackgroundUpdates.Result.UpdatedTo(2), result)
                assertTrue(Files.exists(root / "install" / "Contents" / "2" / "Contents" / "MacOS" / "Graviton Browser"))
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
        val pre = BackgroundUpdates.requiredFreeSpaceMB
        try {
            BackgroundUpdates.requiredFreeSpaceMB = 8000  // JimFS default size is 4 GB
            withServer(testUpdate.jar) { baseUrl ->
                assertEquals(BackgroundUpdates.Result.InsufficientDiskSpace, doCheck(baseUrl, 1))
            }
        } finally {
            BackgroundUpdates.requiredFreeSpaceMB = pre
        }
    }

    private fun startServer(updatePath: Path): Pair<HttpServer, HttpUrl> {
        val port = 8888
        val httpServer: HttpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
        val baseUrl = HttpUrl.Builder()
                .host(httpServer.address.hostString)
                .port(port)
                .scheme("http")
                .build()
        Files.write(root / "control-file-mac", "Latest-Version-URL: /2.mac.update.jar".toByteArray())
        Files.write(root / "control-file-win", "Latest-Version-URL: /3.win.update.jar".toByteArray())
        Files.write(root / "control-file-bad", "<html>some garbage that won't parse</html".toByteArray())
        serveFile(httpServer, root / "control-file-mac", "/mac/control")
        serveFile(httpServer, root / "control-file-win", "/win/control")
        serveFile(httpServer, root / "control-file-bad", "/linux/control")
        serveFile(httpServer, updatePath, "/2.mac.update.jar")
        // Oops, we forgot to put the latest version for Windows on the server, let's test that case.
        serveFile(httpServer, updatePath, "/2.win.update.jar")
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

    private fun doCheck(baseUrl: HttpUrl, currentVersion: Int): BackgroundUpdates.Result {
        return BackgroundUpdates.checkForGravitonUpdate(
                currentVersion,
                (root / "install" / "Contents").createDirectories(),
                baseURL = baseUrl.uri(),
                signingPublicKey = pub1
        )
    }

    private fun withServer(target: Path, body: (HttpUrl) -> Unit) {
        val (server: HttpServer, baseUrl: HttpUrl) = startServer(target)
        try {
            body(baseUrl)
        } finally {
            server.stop(5 /* seconds */)
        }
    }
}