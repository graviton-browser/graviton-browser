Graviton
########

This is the design site for a *prototype* app browser and cross-platform software manager.

Graviton gives you:

* A browser-like shell that downloads and runs desktop apps that target the JVM.
* Silent upgrades via a regular scheduled background task, like in Chrome. Apps, dependencies, the JVM and
  Graviton itself are all upgraded regularly, whether or not the user is currently running an app.
* Apps can be written in any language with a JVM backend, such as Java, `JavaScript <https://www.youtube.com/watch?v=OUo3BFMwQFo>`_, Python, Scala, Kotlin, `Haskell <https://eta-lang.org/>`_, `Ruby <https://github.com/oracle/truffleruby>`_
   * ... with support for sandboxed C, C++ and Rust coming in future via Sulong.
* First class support for cross platform command line apps that also smoothly upgrade, even if the app is running
  at the time (good for servers).
   * Graviton enables colour terminal handling on Windows 10, so ANSI escapes can be used on any platform.
   * Graviton automatically detects proxy settings from the OS or browsers, and can handle proxy auto-config files.
* Apps can use advanced designed-for-apps GUI toolkits like JavaFX with visual designers, or embed WebKit and use HTML.
* Run apps directly from GitHub, GitLab and BitBucket repositories. The repository is cloned on a remote server
  in a sandbox, compiled, packaged, downloaded and this process repeats in the background on regular intervals to keep
  your app up to date. Push new versions to your users simply by doing GitHub releases.

Learn more by reading the :doc:`introduction`.

Current status
**************

.. raw:: html

   <video autoplay controls><source src="_static/Graviton Initio 480p.mov" type="video/mp4"></video>

The project is alpha software and could benefit from your help. We are currently preparing for our first distributed
binaries, which we hope to reach by the end of the year. `See remaining tasks <https://github.com/mikehearn/graviton-browser/projects/1>`_.

**Shell**. There is a simple UI that lets you start apps by entering Maven coordinates. The dependencies
are downloaded in parallel and then the main class is executed. You can omit the version number and the latest version
will be used. Packages are cached locally and can run offline. After first run, app startup is always immediate.

**Online update**. Graviton updates both itself and the last 20 recently used apps in the background, silently, whether or
not Graviton itself is running. It uses a Chrome-style approach in which the OS native task scheduler is used to invoke
Graviton invisibly in the background. Therefore apps run via Graviton can assume the JRE is always up to date (we currently
run on Java 8, but will upgrade to Java 11 in future), and that they are also up to date.

**Command line**. There is a ``graviton`` command line tool that lets you run apps by coordinate.

**Cross platform**. It all runs on Windows, Mac and Linux. On Windows 10, command line apps benefit from support for colour
terminal output.

.. toctree::
   :maxdepth: 2
   :caption: Project

   introduction
   newsletters
   graviton-apps

.. toctree::
   :maxdepth: 2
   :caption: Tutorials

   tutorial-graphical-hello-world

.. toctree::
   :maxdepth: 2
   :caption: Design docs

   online-update
   command-line
   code-fetching
   testing

