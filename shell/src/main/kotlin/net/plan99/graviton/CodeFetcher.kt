package net.plan99.graviton

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
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
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import org.reactfx.EventSource
import org.reactfx.EventStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A wrapper around the Aether library that configures it to download artifacts from Maven Central, reports progress
 * and returns calculated classpaths.
 */
class CodeFetcher {
    companion object : Logging()

    /** The location on disk where the local Maven repository is. */
    val cachePath: Path = currentOperatingSystem.appCacheDirectory

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
        session.transferListener = object : AbstractTransferListener() {
            fun push(event: TransferEvent, type: TransferEvent.EventType) {
                debug { "$type: $event" }
                _eventExecutor.submit {
                    _allTransferEvents.push(Event(event, type))
                }
            }
            override fun transferInitiated(event: TransferEvent) = push(event, TransferEvent.EventType.INITIATED)
            override fun transferStarted(event: TransferEvent) = push(event, TransferEvent.EventType.STARTED)
            override fun transferSucceeded(event: TransferEvent) = push(event, TransferEvent.EventType.SUCCEEDED)
            override fun transferProgressed(event: TransferEvent) = push(event, TransferEvent.EventType.PROGRESSED)
            override fun transferFailed(event: TransferEvent) = push(event, TransferEvent.EventType.FAILED)
            override fun transferCorrupted(event: TransferEvent) = push(event, TransferEvent.EventType.CORRUPTED)
        }
        session.checksumPolicy = RepositoryPolicy.CHECKSUM_POLICY_IGNORE
        val localRepo = LocalRepository(cachePath.toFile())
        session.localRepositoryManager = repoSystem.newLocalRepositoryManager(session, localRepo)
        session.setConfigProperties(mapOf(
                "aether.metadataResolver.threads" to 20,
                "aether.connector.basic.threads" to 20
        ))

        session
    }

    private val _eventExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "Aether Downloader Event Dispatch").also { it.isDaemon = true }
    }
    /** The executor on which stream events are dispatched. Use it with [EventStream.threadBridge] */
    val eventExecutor: Executor = _eventExecutor

    class Event(val data: TransferEvent, val type: TransferEvent.EventType)
    private val _allTransferEvents = EventSource<Event>()
    /** A stream of transfer events related to different downloads all mixed together. */
    val allTransferEvents: EventStream<Event> = _allTransferEvents
    /** A stream of streams. Each new stream represents a new transfer. */
    val transferStreams: EventStream<EventStream<Event>> = EventSource()
    /** If set to true, Aether will be configured to not use the network. */
    var offline: Boolean = false

    init {
        val transferEventStreams = HashMap<TransferResource, EventStream<Event>>()
        allTransferEvents.subscribe {
            val source = transferEventStreams.getOrPut(it.data.resource) { EventSource() } as EventSource<Event>
            if (it.type == TransferEvent.EventType.INITIATED)
                (transferStreams as EventSource<EventStream<Event>>).push(source)
            source.push(it)
            if (it.type == TransferEvent.EventType.FAILED || it.type == TransferEvent.EventType.CORRUPTED)
                transferEventStreams.remove(it.data.resource)
        }
    }

    fun clearCache() {
        // TODO: This should synchronise with the repository manager to ensure nothing is downloading at the time.
        info { "Clearing cache" }
        if (!cachePath.toFile().deleteRecursively())
            error { "Failed to clear disk cache" }
    }

    /**
     * Returns a classpath for the given resolved and dependency-downloaded package. If only two parts of the coordinate
     * are specified, the latest version is assumed.
     *
     * @param packageName Package name in standard groupId:artifactId:version or groupId:artifactId form.
     */
    fun downloadAndBuildClasspath(packageName: String): String {
        info { "Request to download and build classpath for $packageName" }
        // TODO: Use a VersionRange request instead of LATEST, which isn't always set.
        val name = if (packageName.split(':').size == 2) "$packageName:LATEST" else packageName
        val dependency = Dependency(DefaultArtifact(name), "runtime")
        val collectRequest = CollectRequest().apply {
            root = dependency
            configureRepositories()
        }
        val collectDependencies: CollectResult = repoSystem.collectDependencies(session, collectRequest)
        val node: DependencyNode = collectDependencies.root
        stopwatch("Dependency resolution") {
            repoSystem.resolveDependencies(session, DependencyRequest(node, null))
        }
        val classPathGenerator = PreorderNodeListGenerator()
        node.accept(classPathGenerator)
        val classPath = classPathGenerator.classPath
        info { "Classpath: $classPath" }
        return classPath
    }

    private fun CollectRequest.configureRepositories() {
        fun withRepo(id: String, url: String) = addRepository(RemoteRepository.Builder(id, "default", url).build())

        val protocol = if (useSSL) "https" else "http"
        withRepo("jcenter", "$protocol://jcenter.bintray.com/")
        withRepo("central", "$protocol://repo1.maven.org/maven2/")
        withRepo("jitpack", "$protocol://jitpack.io")
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
        addRepository(
                RemoteRepository.Builder("dev-local", "default", m2Local)
                        .setPolicy(RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                        .build()
        )
    }
}