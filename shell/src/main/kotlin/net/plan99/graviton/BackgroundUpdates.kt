package net.plan99.graviton

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.util.*

object BackgroundUpdates : Logging() {
    private const val cacheSizeMegaBytes = 250
    private const val signingPubKeyB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAElI2KSzGh1b9KYsYXAU/YYtOqsm1aFcwkuj1VhjNmaVo/0SKUZvdApP5N9+wa2KvQS0UoXdD4XvHKxiEtJ0/QiA=="
    val mikePubKey: PublicKey by lazy {
        val spec = PKCS8EncodedKeySpec(Base64.getDecoder().decode(signingPubKeyB64))
        KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun doBackgroundUpdate(cachePath: Path, currentVersion: Int?, currentInstallDir: Path?) {
        runBlocking {
            // TODO: Skip if disk space is low.
            stopwatch("Background update") {
                refreshRecentApps(cachePath)
            }
            // We won't check for online updates unless run from the main install image, as otherwise we may not have
            // a version or installation path.
            if (currentVersion != null && currentInstallDir != null) {
                stopwatch("Graviton update") {
                    checkForGravitonUpdate(currentVersion, cachePath, currentInstallDir)
                }
            }
        }
    }

    /**
     * Returns the version number that we were upgraded to, or null if no upgrade was performed.
     */
    fun checkForGravitonUpdate(currentVersion: Int, cachePath: Path, currentInstallDir: Path,
                               baseURL: URI = URI.create("https://update.graviton.app/latest"),
                               signingPublicKey: PublicKey = mikePubKey): Int? {
        try {
            val url = HttpUrl.get(baseURL)!!
                    .newBuilder()
                    .addEncodedQueryParameter("c", currentVersion.toString())
                    .build()

            logger.info("GET $url")
            val client = OkHttpClient.Builder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build()
            client.newCall(Request.Builder().url(url).build())
                    .execute()
                    .use { response ->
                        logger.info("Received response from server: $response")
                        if (response.isRedirect) {
                            val (verNum, redirectUrl) = extractLatestVersionFrom(response)
                            logger.info("Latest version is $verNum")
                            // Do we need to update at all? Usually not.
                            if (verNum <= currentVersion) {
                                logger.info("We are up to date.")
                                return null
                            }
                            val jarFetch = client.newCall(Request.Builder().url(redirectUrl).build()).execute()
                            if (jarFetch.isSuccessful) {
                                downloadAndInstallUpgrade(cachePath, jarFetch, currentInstallDir, verNum, signingPublicKey)
                            } else {
                                logger.error("Followed redirect but download was not successful: ${jarFetch.code()} ${jarFetch.message()}")
                            }
                            return verNum
                        } else
                            throw Exception("Response was not a redirect. Protocol error, not upgrading: ${response.code()} ${response.message()}")
                    }
        } catch (e: Exception) {
            logger.error("Failed to download update", e)
        }
        return null
    }

    private fun extractLatestVersionFrom(response: Response): Pair<Int, HttpUrl> {
        val redirect = HttpUrl.parse(response.header("Location")!!) ?: throw Exception("Could not parse redirect URL")
        logger.info("Redirected to: $redirect")
        // We expect the redirect to take us to a file of the form X.jar where X is an integer
        val remoteFileName = redirect.pathSegments().last()
        val verNum = remoteFileName.split(".")[0].toInt()
        return Pair(verNum, redirect)
    }

    private fun downloadAndInstallUpgrade(cachePath: Path, response: Response, currentInstallDir: Path, verNum: Int, signingPublicKey: PublicKey) {
        val updateJarPath = cachePath / "$verNum.jar.tmp"
        Files.newOutputStream(updateJarPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            response.body()!!.byteStream().copyTo(out)
        }
        logger.info("Saved update JAR to $updateJarPath")
        val targetInstallDir = currentInstallDir / verNum.toString()
        logger.info("Unpacking to $targetInstallDir")
        RuntimeUpdate(updateJarPath, signingPublicKey).install(targetInstallDir)
    }

    suspend fun CoroutineScope.refreshRecentApps(cachePath: Path) {
        try {
            val codeFetcher = CodeFetcher(coroutineContext, cachePath)
            val historyManager = HistoryManager(currentOperatingSystem.appCacheDirectory, refreshInterval = Duration.ofHours(12))
            historyManager.refreshRecentlyUsedApps(codeFetcher)
        } catch (e: Exception) {
            logger.error("App refresh failed", e)
            // Fall through.
        }
    }
}
