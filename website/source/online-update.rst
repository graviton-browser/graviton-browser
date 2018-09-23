Online update
*************

One of the most annoying tasks with distributing desktop apps is keeping them up to date. This is something browsers
excel at and, along with sandboxing and a smooth developer on-ramp, can largely explain the web's success as an app
platform.

Goals
=====

* Chrome-style silent upgrade of the JVM, of Graviton and of the apps.
* Updates can be applied whilst the browser is running, without any user intervention or disruption.
* Updates can be applied even when the browser is not running.
* Most updates do not require platform-specific rebuilds.

Non-goals
=========

* In this first iteration, network administrator control is out of scope. It can be tackled in future.
* Linux support - Linux users have their own platform level update mechanisms that can be reused.

Design
======

.. note:: This design is **implemented**.

The browser uses Java 8 and the javapackager tool, which produces a directory layout and native installer for each
platform, with a small native startup tool that loads JVM parameters from a config file.

We modify the directory layout before the native installers are produced to replace the startup program with our own,
the bootstrap program, which selects a 'sub install' to run. In this way new versions can be unpacked alongside the
current running version. Apps are downloaded to a Maven cache stored in the OS specific cache directories, which uses
the same design of versioned directories.

The operating system task scheduler on each platform is used to invoke Graviton at 6 hour intervals. It checks for new
versions of itself, and uses a history file that records the last 20 app invocations the user made. It then uses Maven
Resolver to re-resolve each in turn - if the app had no version specified this will download the latest version (otherwise
it's a no-op unless the cache was cleared).

Bootstrap
---------

The directory structure of the browser app looks like this::

    /graviton
    /1/bin/graviton
    /1/lib/...
    /2/bin/graviton
    /2/lib/...

In other words, there is a native executable at the root location, and then several sub-installations identified by
sub-directories with integer names. The native executable at the root scans the directory list at startup and selects
the directory with the highest name before re-executing the binary found under that location with the same set of
command line arguments. The native executable is called the *bootstrap* and it does very little, so will need to change
rarely. It is be written in Kotlin/Native and there are different versions for macOS and Windows.

This design means that it's possible for a running instance of the browser to download and unpack new versions of itself
whilst running. Partially unpacked installations should be put in a directory with a name that starts with a letter,
and then the directory can be renamed to the final integer name when unpacking and preparation is finished. The browser
can monitor this directory to see if a new version has become available whilst running and notify the user with an
unobtrusive hint in the user interface, as Chrome does it.

It is assumed the user is either an admin user (on macOS) or that the app was installed to the user's home directory.
On Windows the installer we provide does not offer any customisation options, thus, it will always be in the user's
home directory (under the hidden AppData folder).

The ``javapackager`` tool is used to assemble the final download.

Update tracking and batched updates
-----------------------------------

The browser stores its current integer version (all versions are integers in the update system) in a file called
``last-run-version``. If the browser starts and discovers its hard-coded current version is different to the version
stored in the file, it knows it has been updated.

There may be situations where the user has been offline for a long time or uninstalled the browser without removing its
data files. The browser may thus end up skipping versions, where the last run version was e.g. 5 but the current version
is 10. The browser must be designed to be able to iteratively upgrade its data files from older versions.

We use this design rather than allow the browser to only have to upgrade from the last version of itself because, whilst
the update system could step through each update in sequence, this would mean no way to recover from botched releases.
More importantly, it would mean if the user uninstalled the app and reinstalled it a long time later, we'd have to
download and run all the old versions to step through the upgrade sequence, which would be bad. So instead we insist that
the browser be able to read and upgrade data files from any prior version of itself.

Background updates
------------------

The browser runtime accepts a command line flag ``--background-update`` which causes it to poll a remote server
to see if new versions are available instead of starting the normal codepaths. If the running version is the latest
version, it exits silently. Otherwise it begins a background download of an update, and proceeds to create a new directory
with the new version inside it so it will be picked up next time the browser is started.

On each platform the operating system task scheduler is used to trigger this process. A new library, *Graviton Scheduler*
has been created to abstract the OS task scheduling functionality. On Windows, the Task Scheduler is configured to
run this program once a day by running ``c:\Windows\System32\Schtasks.exe`` with an XML file `like this one <https://msdn.microsoft.com/en-us/library/windows/desktop/aa446863(v=vs.85).aspx>`_.
This will take place on first run. On macOS there is a similar process, in which an XML file is dropped into the
``~/Library/LaunchAgents`` directory, and on Linux the user-local cron is used.

In this way the browser is guaranteed to be up to date (secure) even if the user hasn't used it for a while, which will
be typical in the early days when there aren't many apps.

Update security
---------------

Updates take two forms.

On macOS and Linux, it is a platform specific JAR file that's unpacked to the target directory by the update process
itself. It is not a platform specific installer. Tasks that are needed will have to be done on first-run (identified
by an old or missing ``last-run-version`` file). The JAR does not contain class files. Instead we use it only for its
signing capability.

We sign the update pack with the ``jarsigner`` tool. There may be multiple signatures required from different parties,
to provide a more secure multi-signature update scheme (everyone reproduces the build and a quorum of developers signs it).

The unpacking code verifies each signature against a hard coded list of public keys. If any file fails to present the
right list of signatures, the update is discarded and will be retried.

.. note:: This means if a bad update is pushed users will keep trying to re-download it until it's fixed.

On Windows, the update is the same NSIS installer that users download the first time they install the browser. It is
run with special command line flags that make it run invisibly in the background, and ignore files that are the same
version or newer (i.e. comparing the PE headers). Because of the numbered install directories, this installer can be
run whilst Graviton itself is running.

Update protocol
---------------

The updater requests the URL ``https://update.graviton.app/<osname>/control?c=5`` where 5 is the current version of the app and
"osname" is either "mac" or "win". The control file is a properties file that must have at least one key, "Latest-Update-URL" which
contains a relative URL to the update pack. The value of this key will be interpreted as if it were an HTML link, so, you can use either
absolute URLs or a path like "/foo/bar" in it.

The filename must be of the form "5.something.whatever", i.e. a dot separated name where the first component is the integer version
number. It will be downloaded and unpacked only if the version number in the filename is higher than the currently executing version. The
other components are arbitrary and ignored.

The signed pack will be downloaded, verified and either unpacked into the numbered directory indicated by the file name,
or executed. On UNIX systems the execute bit is set on a hard-coded OS specific path to ensure the main executable can
be invoked. Once this is done the update is complete.

Updating the updater
--------------------

Because the update process is performed by the app itself, triggered by a command line flag, the update process also by
implication update the updater. In the unlikely event that the bootstrap program needs to be changed, that can
also be handled by special case code, assuming the user can write to that program. However given it does so little the
hope is it never needs to be updated once created.

This mechanism can be used to change the signing keys that are authorised to push upgrades, as the set of developers
evolves over time.

JRE minimisation
----------------

Java 9+ introduces a nice feature; the jlink and javapackager tools can now minimise the JRE by stripping out modules
that aren't needed. Unfortunately it comes with a huge caveat - this only works for fully modularised apps, and the
tooling, Gradle and Kotlin support for this is half baked. Building and jlinking a modular Kotlin app is still far from
easy. For now we will punt this to later in the hope that the ecosystem eventually catches up.

Preparing an update
===================

Make sure you have a keystore.p12 file that contains an PKCS#12 key store. You can create a signing key like this::

    openssl ecparam -out ec_key.pem -name secp256r1 -genkey
    openssl req -new -key ec_key.pem -nodes -x509 -days 3650 -out update_cert.pem
    # Enter some plausible sounding details here. The cert details don't matter.
    openssl pkcs12 -inkey ec_key.pem -in update_cert.pem  -export -out keystore.p12 -alias $USER

After running these commands, you will have a p12 file that contains an elliptic curve private key and certificate,
under the alias of your current username.

The procedure for pushing an update to the browser and runtime is as follows.

1. Increase the version number in the root build.gradle file (it's represented as a string but must be an integer value)
2. Run the package-osname script in the root directory for each OS in turn. For instance ``package-mac.sh``
3. This will run the procedure to generate a native installer, unpack it, and then output a signed JAR of the update in the current directory.
4. Upload this JAR to the server.
5. Update the control file.

.. warning:: On macOS make sure you don't have any prior disk images mounted when running, as it can interfere with the build process.