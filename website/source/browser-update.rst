Browser update
**************

One of the most annoying tasks with distributing desktop apps is keeping them up to date. This is something browsers
excel at and, along with sandboxing and a smooth developer on-ramp, can largely explain the web's success as an app
platform.

Goals
=====

* Chrome-style silent upgrade of the browser platform.
* The browser can be upgraded whilst it's running, without any user intervention or disruption.
* Updates can be applied even when the browser is not running.
* Most updates do not require platform-specific rebuilds.

Non-goals
=========

* This section does not discuss updates of apps that run in the browser.
* In this first iteration, network administrator control is out of scope. It can be tackled in future.

Design
======

The browser uses Java 10+ and is shipped as a jlink-ed image, with a selection of modules included out of the box.
We use all applicable optimisations such as AOT and AppCDS to help the browser start as fast as possible. The actual
entrypoint to the app is not, however, the one jlink produces.

Bootstrap
---------

Instead the directory structure of the browser app looks like this::

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

.. note:: This section is **implemented**.

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
will be created to abstract the OS task scheduling functionality. On Windows, the Task Scheduler will be configured to
run this program once a day by running ``c:\Windows\System32\Schtasks.exe`` with an XML file `like this one <https://msdn.microsoft.com/en-us/library/windows/desktop/aa446863(v=vs.85).aspx>`_.
This will take place on first run. On macOS there is a similar process, in which an XML file is dropped into the
``/Library/LaunchAgents`` directory or ``~/Library/LaunchAgents`` directory, and on Linux the user-local cron can be
used.

In this way the browser is guaranteed to be up to date (secure) even if the user hasn't used it for a while, which will
be typical in the early days when there are hardly any apps available for it.

Update security
---------------

Updates take the form of a platform specific JAR file, which is unpacked to the target directory by the update process
itself. It is not a platform specific installer. Tasks that are needed will have to be done on first-run (identified
by an old or missing ``last-run-version`` file). The JAR does not contain class files. Instead we use it only for its
signing capability.

We sign the update pack with the ``jarsigner`` tool. There may be multiple signatures required from different parties,
to provide a more secure multi-signature update scheme (everyone reproduces the build and a quorum of developers signs it).

The unpacking code verifies each signature against a hard coded list of certificates. If any file fails to present the
right list of signatures, the update is discarded and will be retried.

.. note:: This means if a bad update is pushed users will keep trying to re-download it until it's fixed.

Updating the updater
--------------------

Because the update process is performed by the app itself, triggered by a command line flag, the update process also by
implication updates the updater. In the unlikely event that the bootstrap program needs to be changed, that can
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