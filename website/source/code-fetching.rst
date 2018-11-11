Code fetching
*************

A big part of the browser experience is how code is downloaded and kept fresh, whilst being as fast as possible.

Repositories
============

To help us get started quickly and be useful right away, Graviton currently fetches code only from Maven repositories.
It comes pre-configured with the following repos:

1. Maven Central
2. Bintray
3. Jitpack.io - this one conveniently turns github repositories into Maven repositories on the fly, see below for more info.
4. A local repository in ~/.m2/dev-local which you can deploy to directly. See below.

To make an app available it and all its dependencies must be findable via one of these four sources. In future we will
add support for auto-discovery of other repositories on the local network using registry keys, mDNS, and so on.

Because of this decision, all code modules in Graviton are identified by Maven "GAV" coordinates (group ID, artifact ID,
version). An "artifact" in Maven-speak is just a file. A group ID is a Java-style reverse DNS name. And a version is
self explanatory (if you need more info here is `a good article on Maven version numbers <https://docs.oracle.com/middleware/1212/core/MAVEN/maven_version.htm>`_.

Future support
==============

It's not intended to use Maven repositories forever. Over time we could develop better formats with higher security
and performance. For example, apps could commit to the hashes of their dependency tree for more security against
compromised repository mirrors, pack200 could be brought back from the grave, and classes from many modules could be
multiplexed together into a single stream with a 'watermark' where execution should begin to allow apps to be started
before they've fully downloaded.

Jitpack
=======

You can run apps directly from GitHub by using jitpack.io - just use coordinates of the following form::

    com.github.username:repositoryname:commit-hash

For example, ``com.github.mikehearn:tictactoe:master-SNAPSHOT``.

Local development repository
============================

Graviton will not use your ``~/.m2/repository`` directory if you have one, both for internal technical reasons and to
keep your development environment separated. However it will

Optimisations
=============

Making an app start as fast as a web page is partly about download optimisation and we have many planned or already implemented:

1. Parallel resolution of the dependency tree (DONE).
2. Local caching of artifacts, so commonly used libraries are not re-downloaded repeatedly (DONE).
3. Only re-check for new versions once a day (DONE).
4. Cache the resolved classpath so it doesn't have to be re-calculated on each use (DONE).
5. Pre-generation of a dependency tree file, so a POM walk isn't necessary.
6. Proxies for common Maven repos that respond to failed download attempts by fetching the requested artifacts and
   recompressing with pack200, thus automatically optimising distribution of apps that are being frequently requested.
7. Early launch - monitoring a "training run" of the app and observing when classloading activity pauses for a few
   seconds. Any modules accessed before that time are assumed to be needed and will be resolved before startup, any
   modules accessed after that will be downloaded whilst the app is running. An attempt to access a class in a module
   that wasn't loaded yet will hang until loading completes. In this way apps can be adjusted to stream features in
   the background.
8. Pre-fetch of commonly used libraries so apps don't pay any download cost for them.
9. Identification of artifacts by secure hash rather than just coordinates.

All the app-specific adaptations can be made easy with Maven and Gradle plugins.

