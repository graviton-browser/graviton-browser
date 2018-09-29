Command line apps
*****************

A big part of Graviton is "instant on" streaming apps, which keep themselves fully up to date. This capability is just
as useful for command line apps as it is for GUI apps. All major operating systems have command line package managers
now. Linux paved the way with tools like apt-get and yum, macOS has brew, Windows has chocolately.

Unfortunately, it sucks to write cross platform CLI apps. Java apps are ugly from the command line as you
must set up the classpath manually, and prefix commands with "java -jar programname.jar" which is unnatural. And that
doesn't help you keep them up to date, or manage dependencies. On the other hand, few other platforms have robust
operating system abstractions (e.g. Go isn't that great on Windows). If you want to distribute a cross platform command
line tool you're pretty much restricted to Python and pip: it's fragile and isn't ideal for more complex apps where
static typing or high performance is desirable.

Finally, many tools make assumptions that aren't valid on Windows, e.g. by using ANSI terminal escapes.

Graviton can move into this niche if it has great support for command line tools.

Goals
=====

* Natural, easy access to programs from the command line.
* Apps are identified by Maven coordinates, stored in Maven repositories and dependencies work correctly.
* Apps update automatically in the background unless pinned.
* Minimal OS overhead in the common case of no work for Graviton to do.
* One-liner to obtain new apps.
* Optional sandboxing.
* Support ANSI escapes.

Non-Goals
=========

* Exposing new services or APIs for command line apps, like an argument parsing API.

Design
======

Usage
-----

Either at install time or on first run, Graviton will either add itself to the path (on Windows) or symlink itself to
``/usr/local/bin/graviton`` (on Mac/Linux). The user can run an app like this:

``graviton org.jetbrains.kotlin:kotlin-compiler --help``

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

Windows
-------

Windows requires special support. EXE files have to be marked in the header as either GUI or console apps. GUI apps
can't print to the console if launched from the command prompt and automatically go into the background. Console apps
pop up a console window if launched from the GUI, regardless of whether or not the app uses it, which is ugly. Finally,
although Windows 10 added support for ANSI colour and cursor escape codes, the console ignores them by default. Enabling
them requires Win32 API calls.

We solve this with a somewhat complex bootstrap:

1. The Graviton bootstrapper tool (which is a native app) is compiled twice, once in GUI mode and once in console mode.
2. The console mode version enables ANSI escapes support and then starts the main Graviton app and waits for it to finish.
3. The GUI app starts. At this point the Windows kernel has detached it from the console and closed its input/output
   handles. We re-attach to the parent console and then re-open the input/output handles, repeating the part of the JVM
   startup sequence that initialises System.{in,out,err}
