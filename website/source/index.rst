Graviton Browser
################

This is the design site for a *proposed* project called Graviton.

Graviton Browser is a browser-like shell for apps that run on the JVM and use GUI toolkits for their user interfaces,
rather than HTML. Graviton also supports command line apps, allowing developers to distribute platform independent
command line apps via an interface that works the same way on Windows, Mac and Linux. It is intended to be fully
polyglot and support apps written in all mainstream languages, including eventually C/C++ (via Sulong).

At this time the project is not fully implemented and is missing most features that are needed - this site is intended
to act as a rallying point for people interested in helping create it.

Learn more by reading the :doc:`introduction`.

Current status
**************

.. raw:: html

   <video autoplay controls><source src="_static/Graviton Initio 480p.mov" type="video/mp4"></video>

**Shell**. There is a simple shell app that lets you start apps by entering their Maven coordinates. The dependencies
are downloaded in parallel and then the main class is executed. You can omit the version number and the latest version
will be used. Packages are cached locally.

**Online update**. There are native packages (DMG, EXE and DEB) for macOS, Windows and Linux respectively. These are
laid out such that the install can be updated whilst Graviton is running, using a small native bootstrap executable
that locates the highest version numbered install and selects it at startup. A module that can register tasks with
the Mac, Windows and Linux task schedulers is implemented but not yet used (Launch Services, Windows Task Scheduler
and cron respectively).

.. note:: **Next steps**. Wiring up the task scheduler to the native packages so they are invoked daily. Downloading and
   unpacking refreshed native packages in the background so we can update the JRE in the background, Chrome style. More
   download performance optimisations. Selecting a logo and icon.

.. toctree::
   :maxdepth: 2
   :caption: Design docs

   introduction
   browser-update
   command-line
   incremental-adaptation
