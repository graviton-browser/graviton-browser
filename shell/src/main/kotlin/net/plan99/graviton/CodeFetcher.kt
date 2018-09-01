package net.plan99.graviton

import kotlinx.coroutines.experimental.runBlocking
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.conscrypt.Conscrypt
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
import org.eclipse.aether.util.graph.transformer.*
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import java.nio.file.Files
import java.nio.file.Path
import java.security.Security
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.experimental.CoroutineContext

/**
 * A wrapper around the Aether library that configures it to download artifacts from Maven Central, reports progress
 * and returns calculated classpaths.
 */
open class CodeFetcher(private val coroutineContext: CoroutineContext, private val cachePath: Path) {
    companion object : Logging()

    init {
        // Ensure the local Maven repo exists.
        Files.createDirectories(cachePath)

        // Override built-in security using Open/BoringSSL via the Conscrypt provider. The index here is 1-based.
        // This essentally eliminates the overhead of enabling SSL using JSSE and doubles download performance.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
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
        // The separate checksum files are of questionable use in an SSL world and slow down the transfer quite
        // a bit, so we toss them here.
        session.checksumPolicy = RepositoryPolicy.CHECKSUM_POLICY_IGNORE
        // Graviton does its own lookup caching on top of CodeFetcher so we want to always poll the remote servers
        // when asked if there's a new version.
        session.updatePolicy = RepositoryPolicy.UPDATE_POLICY_ALWAYS
        val localRepo = LocalRepository(cachePath.toFile())
        session.localRepositoryManager = repoSystem.newLocalRepositoryManager(session, localRepo)
        session.setConfigProperties(mapOf(
                "aether.metadataResolver.threads" to 20,
                "aether.connector.basic.threads" to 20
        ))
        // Re-configure the conflict resolver. By default it uses a weird way to resolve version conflicts in a dependency
        // graph, the so-called "nearest wins" strategy which basically says versions closer to the root node in the graph
        // (of lower depth) take precedence over versions deeper in the tree. This is neither intuitive nor what Gradle does.
        // So we replace it here with one that just always picks the newest version - of course such dependency graphs are
        // inherently unstable and the best we can do is hope. One day Jigsaw might actually clean this whole mess up.
        val conflictResolver = ConflictResolver(HighestVersionSelector(), JavaScopeSelector(), SimpleOptionalitySelector(), JavaScopeDeriver())
        ChainedDependencyGraphTransformer(conflictResolver, JavaDependencyContextRefiner())
        session.dependencyGraphTransformer = conflictResolver

        session
    }

    private class HighestVersionSelector : ConflictResolver.VersionSelector() {
        override fun selectVersion(context: ConflictResolver.ConflictContext) {
            val items = context.items.toSet()
            var winner = items.first()
            if (items.size == 1) {
                context.winner = winner
                return
            }
            logger.warn("Resolving conflict for ${context.items.first().node.artifact} between ${items.map { it.node.version }}")
            for (item in items) {
                if (item.node.version > winner.node.version)
                    winner = item
            }
            // We ignore version constraints here - maybe we should use them, but it's not clear we can ever do better than highest wins.
            // If that doesn't work the app is broken.
            context.winner = winner
        }
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

        @Volatile var lastFinish: String = ""

        override fun transferStarted(event: TransferEvent) {
            info { "Transfer started: $event" }
            if (isBoring(event)) return
            totalBytesToDownload.addAndGet(event.resource.contentLength)
        }

        override fun transferSucceeded(event: TransferEvent) {
            info { "Transfer succeeded: $event" }
            lastFinish = event.resource.file.name
        }

        override fun transferProgressed(event: TransferEvent) {
            debug { "Transfer progressed: $event" }
            if (!isBoring(event)) {
                totalDownloaded.addAndGet(event.dataLength.toLong())
            }
            runBlocking(coroutineContext) {
                events?.onFetch(lastFinish, totalBytesToDownload.get(), totalDownloaded.get())
            }
        }

        override fun transferFailed(event: TransferEvent) {
            debug { "Transfer failed: $event" }
        }

        override fun transferCorrupted(event: TransferEvent) = transferFailed(event)
    }

    interface Events {
        /** Called exactly once, if we decide we need to do any network transfers of non-trivial files (like JARs). */
        suspend fun onStartedDownloading(name: String)

        suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long)

        /** If [onStartedDownloading] was called, this is called when we are finished or have failed. */
        suspend fun onStoppedDownloading()
    }

    var events: Events? = null

    /** If set to true, Aether will be configured to not use the network. */
    var offline: Boolean = false

    data class Result(val classPath: String, val name: Artifact)

    /**
     * Returns a classpath for the given resolved and dependency-downloaded package. If only two parts of the coordinate
     * are specified, the latest version is assumed.
     *
     * @param packageName Package name as a coordinate fragment.
     */
    suspend fun downloadAndBuildClasspath(packageName: String): Result {
        val name = checkLatestVersion(packageName)
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
        if ((session.transferListener as TransferListener).didDownload.get())
            events?.onStoppedDownloading()
        val classPathGenerator = PreorderNodeListGenerator()
        node.accept(classPathGenerator)
        val classPath = classPathGenerator.classPath
        info { "Classpath for ${classPath.split(':').joinToString(System.lineSeparator())}" }
        return Result(classPath, artifact)
    }

    private suspend fun checkLatestVersion(packageName: String): String {
        // Parse the coordinate fragment the user entered.
        val components = packageName.split(':').toMutableList()

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
        //
        // This might not be so useful now we use ~/.m2/repository as our cache by default when it exists.
        val m2Local = (currentOperatingSystem.homeDirectory / ".m2" / "dev-local").toUri().toString()
        repos += RemoteRepository.Builder("dev-local", "default", m2Local)
                .setPolicy(RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                .build()

        repos
    }
}