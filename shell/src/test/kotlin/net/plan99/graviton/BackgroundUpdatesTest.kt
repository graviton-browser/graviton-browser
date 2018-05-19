package net.plan99.graviton

import com.sun.net.httpserver.HttpServer
import okhttp3.HttpUrl
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Files.newInputStream
import java.nio.file.Files.size
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackgroundUpdatesTest : TestWithFakeJRE() {
    private fun startServer(updatePath: Path): Pair<HttpServer, HttpUrl> {
        val port = 8888
        val httpServer: HttpServer = HttpServer.create(InetSocketAddress("localhost", port), 0)
        val baseUrl = HttpUrl.Builder()
                .host(httpServer.address.hostString)
                .port(port)
                .encodedPath("/latest")
                .scheme("http")
                .build()
        httpServer.createContext("/latest") { exchange ->
            try {
                exchange.responseHeaders["Location"] = baseUrl.newBuilder("/2.update.jar")!!.build().toString()
                exchange.sendResponseHeaders(307, 0)
            } finally {
                exchange.close()
            }
        }
        httpServer.createContext("/2.update.jar") { exchange ->
            try {
                exchange.sendResponseHeaders(200, size(updatePath))
                newInputStream(updatePath).use { stream ->
                    stream.transferTo(exchange.responseBody)
                }
            } finally {
                exchange.close()
            }
        }
        httpServer.executor = Executors.newSingleThreadExecutor()
        httpServer.start()
        return Pair(httpServer, baseUrl)
    }

    @Test
    fun serveUpdate() {
        val target = root / "2.update.jar"
        RuntimeUpdate.createFrom(fakeJreDir, priv1, target)
        val (server: HttpServer, baseUrl: HttpUrl) = startServer(target)
        try {
            val verNum = BackgroundUpdates.checkForGravitonUpdate(
                    1,
                    (root / "install" / "Contents").createDirectories(),
                    baseURL = baseUrl.uri(),
                    signingPublicKey = pub1
            )
            assertEquals(2, verNum)
            assertTrue(Files.exists(root / "install" / "Contents" / "2" / "Contents" / "MacOS" / "Graviton Browser"))
        } finally {
            server.stop(5 /* seconds */)
        }
    }
}