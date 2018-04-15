package net.plan99.graviton

import kotlinx.coroutines.experimental.runBlocking
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.collection.CollectResult
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.RepositoryPolicy
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.CoroutineContext

/**
 * A wrapper around the Aether library that configures it to download artifacts from Maven Central, reports progress
 * and returns calculated classpaths.
 */
open class CodeFetcher(private val coroutineContext: CoroutineContext) {
    companion object : Logging()

    /** The location on disk where the local Maven repository is. */
    private val cachePath: Path = currentOperatingSystem.appCacheDirectory

    init {
        // Ensure the local Maven repo exists.
        Files.createDirectories(cachePath)

        // TODO: Use ~/.m2 if it exists rather than our Graviton-specific repository location.
        // ~/.m2 is not really following normal OS conventions on most platforms, but Java devs should be able to reuse
        // their existing package caches if they have one.
    }

    /** Disabling SSL fetches from Maven repos can double the speed of downloading :( */
    var useSSL: Boolean = true

    private val repoSystem: RepositorySystem by lazy {
        val locator: DefaultServiceLocator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        locator.getService(RepositorySystem::class.java)
    }

    private val session: RepositorySystemSession by lazy {
        val session: DefaultRepositorySystemSession = MavenRepositorySystemUtils.newSession()
        session.isOffline = offline
        session.transferListener = TransferListener()
        session.checksumPolicy = RepositoryPolicy.CHECKSUM_POLICY_IGNORE
        val localRepo = LocalRepository(cachePath.toFile())
        session.localRepositoryManager = repoSystem.newLocalRepositoryManager(session, localRepo)
        session.setConfigProperties(mapOf(
                "aether.metadataResolver.threads" to 20,
                "aether.connector.basic.threads" to 20
        ))

        session
    }

    private inner class TransferListener : AbstractTransferListener() {
        val didDownload = AtomicBoolean(false)
        private val totalBytesToDownload = AtomicLong(0L)
        private val totalDownloaded = AtomicLong(0L)

        private fun isBoring(event: TransferEvent): Boolean {
            val name = event.resource.resourceName
            return name.endsWith(".xml") || name.endsWith(".pom") || name.endsWith(".md5") || name.endsWith(".sha1")
        }

        override fun transferInitiated(event: TransferEvent) {
            // Don't notify about maven-metadata.xml files as they are frequently checked as part of version probes.
            if (event.resource.resourceName.endsWith(".xml")) return

            if (!didDownload.getAndSet(true)) {
                runBlocking(coroutineContext) {
                    events?.onStartedDownloading(event.resource.file.name)
                }
            }
        }

        override fun transferStarted(event: TransferEvent) {
            info { "Transfer started: $event" }
            if (isBoring(event)) return
            totalBytesToDownload.addAndGet(event.resource.contentLength)
        }

        override fun transferSucceeded(event: TransferEvent) {
            info { "Transfer succeeded: $event" }
        }

        override fun transferProgressed(event: TransferEvent) {
            debug { "Transfer progressed: $event" }
            if (!isBoring(event)) {
                totalDownloaded.addAndGet(event.dataLength.toLong())
            }
            runBlocking(coroutineContext) {
                events?.onFetch(event.resource.file.name, totalBytesToDownload.get(), totalDownloaded.get())
            }
        }

        override fun transferFailed(event: TransferEvent) {
            debug { "Transfer failed: $event" }
        }

        override fun transferCorrupted(event: TransferEvent) = transferFailed(event)
    }

    interface Events {
        /** Called exactly once, if we decide we need to do any network transfers of non-trivial files (like JARs). */
        suspend fun onStartedDownloading(name: String) {}

        suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {}

        /** If [onStartedDownloading] was called, this is called when we are finished or have failed. */
        suspend fun onStoppedDownloading() {}
    }

    var events: Events? = null

    /** If set to true, Aether will be configured to not use the network. */
    var offline: Boolean = false

    fun clearCache() {
        // TODO: This should synchronise with the repository manager to ensure nothing is downloading at the time.
        info { "Clearing cache" }
        if (!cachePath.toFile().deleteRecursively())
            error { "Failed to clear disk cache" }
    }

    data class Result(val classPath: String, val name: Artifact)

    /**
     * Returns a classpath for the given resolved and dependency-downloaded package. If only two parts of the coordinate
     * are specified, the latest version is assumed.
     *
     * @param packageName Package name as a coordinate fragment.
     */
    suspend fun downloadAndBuildClasspath(packageName: String): Result {
        val name = calculateFullyQualifiedCoordinate(packageName)
        info { "Request to download and build classpath for $name" }
        val artifact = DefaultArtifact(name)
        val dependency = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest()
        collectRequest.root = dependency
        defaultRepositories.forEach { collectRequest.addRepository(it) }
        lateinit var node: DependencyNode
        background {
            stopwatch("Dependency resolution") {
                val collectDependencies: CollectResult = repoSystem.collectDependencies(session, collectRequest)
                node = collectDependencies.root
                repoSystem.resolveDependencies(session, DependencyRequest(node, null))
            }
        }
        if ((session.transferListener as TransferListener).didDownload.get()) {
            events?.onStoppedDownloading()
        }
        val classPathGenerator = PreorderNodeListGenerator()
        node.accept(classPathGenerator)
        val classPath = classPathGenerator.classPath
        info { "Classpath: $classPath" }
        return Result(classPath, artifact)
    }

    private suspend fun calculateFullyQualifiedCoordinate(packageName: String): String {
        // Parse the coordinate fragment the user entered.
        val components = packageName.split(':').toMutableList()

        // TODO: Detect if the group ID was entered the "wrong" way around (i.e. normal web style).
        // We can do this using the TLD suffix list. If the user did that, let's flip it first.

        // If there's no : anywhere in it, it's just a reverse domain name, then assume the artifact ID is the
        // same as the last component of the group ID.
        if (components.size == 1) {
            components += components[0].split('.').last()
        }

        // If there's no version, perform a version range request to find the latest version.
        if (components.size == 2) {
            // [0,) is magic syntax meaning: any version from 0 to infinity, i.e. all versions.
            val artifact = DefaultArtifact((components + "[0,)").joinToString(":"))
            val request = VersionRangeRequest(artifact, defaultRepositories, null)
            val result: VersionRangeResult = background {
                stopwatch("Latest version lookup for " + components.joinToString(":")) {
                    repoSystem.resolveVersionRange(session, request)
                }
            }
            if (result.highestVersion == null)
                throw result.exceptions.first()
            components += result.highestVersion.toString()
        }

        return components.joinToString(":")
    }

    private val defaultRepositories: ArrayList<RemoteRepository> by lazy {
        val repos = arrayListOf<RemoteRepository>()
        fun repo(id: String, url: String) {
            repos += RemoteRepository.Builder(id, "default", url).build()
        }

        val protocol = if (useSSL) "https" else "http"
        repo("jcenter", "$protocol://jcenter.bintray.com/")
        repo("central", "$protocol://repo1.maven.org/maven2/")
        repo("jitpack", "$protocol://jitpack.io")

        // Add a local repository that users can deploy to if they want to rapidly iterate on an installation.
        // This repo is not the same thing as a "local repository" confusingly enough, they have slightly
        // different layouts and metadata. To use, add something like this to your pom:
        //
        //     <distributionManagement>
        //        <snapshotRepository>
        //            <id>dev-local</id>
        //            <url>file:///Users/mike/.m2/dev-local</url>
        //            <name>My local deployment repository</name>
        //        </snapshotRepository>
        //    </distributionManagement>
        //
        // Packages placed here are always re-fetched, bypassing the local cache.
        val m2Local = (currentOperatingSystem.homeDirectory / ".m2" / "dev-local").toUri().toString()
        repos += RemoteRepository.Builder("dev-local", "default", m2Local)
                .setPolicy(RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                .build()

        repos
    }
}