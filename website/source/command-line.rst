Command line apps
*****************

A big part of Graviton is "instant on" streaming apps, which keep themselves fully up to date. This capability is just
as useful for command line apps as it is for GUI apps. All major operating systems have command line package managers
now: Linux paved the way with tools like apt-get and yum, macOS has brew, Windows has chocolately.

Unfortunately it's tough to write cross platform command line apps. Java apps are ugly from the command line as you
must set up the classpath manually, and prefix commands with "java -jar programname.jar" which is unnatural. And that
doesn't help you keep them up to date, or manage dependencies. On the other hand, few other platforms have robust
operating system abstractions (e.g. Go isn't that great on Windows). If you want to distribute a cross platform command
line tool you're pretty much restricted to Python and pip, which isn't ideal for more complex apps where static typing
or high performance is desirable.

Graviton can move into this niche if it has great support for command line tools.

Goals
=====

* Natural, easy access to programs from the command line.
* Apps are identified by Maven coordinates, stored in Maven repositories and dependencies work correctly.
* Apps update automatically in the background unless pinned.
* Minimal OS overhead in the common case of no work for Graviton to do.
* One-liner to obtain new apps.
* Optional sandboxing.

Non-Goals
=========

* Exposing new services or APIs for command line apps, like an argument parsing API.

Design
======

Either at install time or on first run, Graviton will either add itself to the path (on Windows) or symlink itself to
``/usr/local/bin/graviton`` (on Mac/Linux). The user can run an app like this:

``graviton org.jetbrains.kotlin:kotlin-compiler -help``

If an app will be used frequently, a symlink can be requested with the expected name:

``graviton --alias org.jetbrains.kotlin:kotlin-compiler=kotlinc``

If there's a ``Command-Line-Name`` entry in the MANIFEST.MF and if there is no other command on the path with that name,
an alias to that name will be set up the first time the app is run. From that point on the short name can be used.

Graviton remembers which packages were requested via this command line interface versus downloaded as part of dependency
resolution.

If a version number was specified in the coordinate when first installed, that version is pinned. If a range was
specified, or no version was specified at all, then Graviton will update these apps during its regular background
update check. Because Maven repositories are immutable, this will not disturb any running applications.

Every so often, old versions of apps that were installed via the CLI are removed and the local Maven repository garbage
collected to free up space.