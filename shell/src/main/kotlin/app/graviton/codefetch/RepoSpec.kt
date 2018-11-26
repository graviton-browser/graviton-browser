package app.graviton.codefetch

import app.graviton.shell.currentOperatingSystem
import app.graviton.shell.div
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.RepositoryPolicy
import java.net.URI
import java.net.URISyntaxException

/**
 * A comma separated list of either repository aliases, or URLs to repositories. The spec can be resolved to a set of
 * configured [RemoteRepository] instances.
 */
data class RepoSpec(val spec: String, val disableSSL: Boolean) {
    inner class InvalidSpecException(message: String) : Exception("Repository spec could not be parsed: $message ($spec)")

    companion object {
        val aliases = mapOf(
                //"mike" to "://plan99.net/~mike/maven/",
                "central" to "://repo1.maven.org/maven2/",
                "jcenter" to "://jcenter.bintray.com/",
                "jitpack" to "://jitpack.io"
        )
    }

    fun resolve(repoSystem: RepositorySystem, session: RepositorySystemSession): List<RemoteRepository> {
        val protocol = if (disableSSL) "http" else "https"
        val units = spec.split(',')
        if (spec.isEmpty()) throw InvalidSpecException("Empty")

        // input -> (id, url)
        fun processURI(input: String): Pair<String, String> {
            val s = if (disableSSL && input.startsWith("https://")) input.replaceFirst("https://", "http://") else input
            try {
                return Pair(URI(s).host, s)
            } catch (e: URISyntaxException) {
                throw InvalidSpecException(e.message ?: "Bad URL")
            }
        }

        val expanded = units.mapNotNull {
            when (it) {
                "dev-local" -> null
                in aliases -> Pair(it, protocol + aliases[it]!!)
                else -> processURI(it)
            }
        }.toMutableList()

        val repos = expanded.map { (id, url) -> RemoteRepository.Builder(id, "default", url).build() }.toMutableList()

        if ("dev-local" in units) {
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
            // or for Gradle users
            //
            //   publishing {
            //     repositories {
            //       maven {
            //         url = "/Users/mike/.m2/dev-local"
            //       }
            //     }
            //   }
            //
            // Packages placed here are always re-fetched, bypassing the local cache.
            //
            // This might not be so useful now we use ~/.m2/repository as our cache by default when it exists.
            val m2Local = (currentOperatingSystem.homeDirectory / ".m2" / "dev-local").toUri().toString()
            repos += RemoteRepository.Builder("dev-local", "default", m2Local)
                    .setPolicy(RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_IGNORE))
                    .build()
        }

        // We have to pass the repos through this step to ensure proxy and authentication settings apply.
        return repoSystem.newResolutionRepositories(session, repos)
    }
}
