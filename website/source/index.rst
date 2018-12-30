Graviton
########

Graviton is an app browser and cross-platform software manager.

.. raw:: html

   <center><a href="_static/download.html"><button class="button button2">Download now</button></a></center><br><br>

.. raw:: html

   <center>
   <iframe width="700" height="393" src="https://www.youtube.com/embed/cyBN_ILkAtg" frameborder="0" allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture" allowfullscreen></iframe>
   </center><br>


It gives you:

* A **browser-like shell** that downloads and runs desktop apps, written in languages that can run on the JVM.
* **Silent upgrades** via a regular scheduled background task, like in Chrome. Apps, dependencies, the JVM and
  Graviton itself are all upgraded regularly, whether or not the user is currently running an app.
* First class support for **cross platform command line apps**. They also smoothly upgrade, even if the app is running at the time (good for servers). Graviton enables colour terminal handling on Windows 10, so ANSI escapes can be used on any platform with confidence.
* **Publish software simply** using GitHub, GitLab or BitBucket releases! The repository is cloned on a remote server
  in a sandbox, compiled, packaged, downloaded and this process repeats in the background on regular intervals to keep
  your app up to date. Push new versions to your users by simply making a new GitHub release.
* **Apps can be written in any language** with a JVM backend, such as Java, `JavaScript <https://www.youtube.com/watch?v=OUo3BFMwQFo>`_, Python, Scala, Kotlin, `Haskell <https://eta-lang.org/>`_, `Ruby <https://github.com/oracle/truffleruby>`_, with support for sandboxed C, C++ and Rust coming in future via Sulong.
* **Detection of proxy settings** from the OS or browsers, and can handle proxy auto-config files. Apps automatically benefit.
* Use advanced designed-for-apps GUI toolkits like JavaFX with visual designers, or embed WebKit and use HTML.

Learn more by reading the :doc:`introduction`.

Get involved
************

The project is alpha software and could benefit from your help.

* Talk to us on the `graviton-dev mailing list <https://groups.google.com/forum/#!forum/graviton-dev>`_
* Or on `the #graviton-browser channel on the Kotlin Slack <https://surveys.jetbrains.com/s3/kotlin-slack-sign-up>`_.
* Or on `our GitHub repository <https://github.com/graviton-browser/graviton-browser>`_.

Graviton needs you! We're looking for people with:

* Web design skills, to make a nicer site for the project (these pages would remain as developer docs).
* Evangelism skills, to find projects that can run on the JVM and convince them to use Graviton as a distribution mechanism.
* Developer skills:
    * Journeyman level, `to create a beautiful showcase for both GUI and CLI apps <https://github.com/graviton-browser/graviton-browser/issues/79>`_. This would be a good student project.
    * Intermediate level, `to improve the start page shell GUI <https://github.com/graviton-browser/graviton-browser/issues?q=is%3Aissue+is%3Aopen+label%3Ashell>`_.
    * Expert level, to work on improved app streaming formats, JVM isolation, OpenGL, network discovery, OS integration tasks and more. See the :doc:`roadmap` for ideas.

**Commercial version?** Would you like a Pro/Enterprise level version of Graviton? `Let me know! <https://docs.google.com/forms/d/e/1FAIpQLSdl-5xSHdspPLWjMRBjqiCEPlzDsr4CNHoNRarp9nGC6UY5JA/viewform?usp=sf_link>`_

.. toctree::
   :maxdepth: 2
   :caption: Project

   introduction
   newsletters
   roadmap

.. toctree::
   :maxdepth: 2
   :caption: Tutorials

   graviton-apps
   tutorial-graphical-hello-world

.. toctree::
   :maxdepth: 2
   :caption: Design docs

   online-update
   command-line
   code-fetching
   testing

