Graviton Browser
################

This is the design site for a *prototype* app browser.

Graviton Browser is a browser-like shell for apps that run on the JVM and use classical GUI toolkits for their user interfaces,
rather than HTML. Graviton also supports command line apps, allowing developers to distribute platform independent
command line apps via an interface that works the same way on Windows, Mac and Linux. It is intended to be fully
polyglot and support apps written in all mainstream languages, including eventually C, C++ and Rust (via Sulong). It seamlessly and silently
updates both itself and apps that were recently used, taking away the distribution headaches often associated with desktop development.
It will allow apps to be sandboxed, or for the user to give them full system access in the way a desktop app would normally have.

At this time the project is not fully implemented and is missing many interesting features - this site is intended
to act as a rallying point for people interested in helping create it.

Learn more by reading the :doc:`introduction`.

Current status
**************

.. raw:: html

   <video autoplay controls><source src="_static/Graviton Initio 480p.mov" type="video/mp4"></video>

Work is in progress towards a first release. `See remaining tasks <https://github.com/mikehearn/graviton-browser/projects/1>`_.

**Shell**. There is a simple UI that lets you start apps by entering Maven coordinates. The dependencies
are downloaded in parallel and then the main class is executed. You can omit the version number and the latest version
will be used. Packages are cached locally and can run offline. After first run, startup is always immediate.

**Online update**. Graviton updates both itself and the last 20 recently used apps in the background, silently, whether or
not Graviton itself is running. It uses a Chrome-style approach in which the OS native task scheduler is used to invoke
Graviton invisibly in the background. Therefore apps run via Graviton can assume the JRE is always up to date (we currently
run on Java 8, but will upgrade to Java 11 in future), and that they are also up to date.

**Command line**. There is a ``graviton`` command line tool that lets you run apps by coordinate.

.. toctree::
   :maxdepth: 2
   :caption: Project

   introduction
   newsletters
   online-update
   command-line
   incremental-adaptation
   code-fetching
