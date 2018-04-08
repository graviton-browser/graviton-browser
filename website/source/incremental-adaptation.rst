Incremental adaptation
**********************

To grow a community with so little resources Graviton must be useful from the start. We cannot demand buy-in from app
devs without any distributed runtimes, yet a runtime is not useful if there are no apps.

To break this catch-22 we will support execution of ordinary Java apps that are not written for Graviton, from day one.
And to benefit from Graviton's features will involved only a set of small tweaks that can be applied in series. Developers
can stop when they feel they are happy with the user experience, or continue opting in to more parts of the platform.

We call this concept incremental adaptation.

Here are the planned phases app developers can go through.

Phase 1
=======

A JAR published to either github or a Maven repository. Github access is provided via jitpack.io, a "magic" Maven
repository that knows how to convert HTTP GET requests into git clones followed by builds. This exploits the relatively
uniform nature of the build systems in the Java ecosystem and works remarkably well. The delay on artifact fetch is
far lower than would be assumed.

Graviton either uses the MANIFEST.MF file to locate the main class, e.g. as set up by the Gradle application plugin,
or it will scan the JAR to locate a main method, or a JavaFX Application subclass. In this way an app can be published
to Graviton, with automatic updates, just by pushing to a github repository.

If a description is found in the named POM file, it's used in the shell UI.

The local Maven repository is used and this can assist during development.

The shell will close itself after the app is run, and when the app is finished Graviton will fully quit.

The user will be warned that running an app this way is equivalent to installing it i.e. all permissions are granted.

There are no signing requirements. However, applications that are not sandboxed must be distributed via one of the
pre-provided Maven repositories and coordinates may be blacklisted centrally.

.. note:: The basic assumption here is that developers who want or need to run un-sandboxed can just distribute outside
   of any browser or app store like environment. Years of trying have not caused all apps to be even signed on Mac or
   Windows, let alone sandboxed, so this is not a regression.

Phase 2
=======

Artwork or icons to identify the app can be provided. Artwork can be either square (i.e. an icon) or a rectangle that's
more like a "hero image". Hero images can be provided in SVG format or a set of bitmap layers. The idea of layering is
to enable an Apple TV inspired parallax effect. If placed in the META-INF directory these will be used instead of text
in the recent apps area of the UI, and for icons in the windowing system.

Phase 3
=======

The Graviton Maven/Gradle plugins can be used to create binary dependency tree descriptors, thus eliminating the overhead
of the POM walk, and by using pack200 compression JAR sizes are significantly reduced. All these artifacts can be
published to Maven repositories as normal and complement the existing JARs and metadata.

Phase 4
=======

Apps can opt in to sandboxing by specifying a set of permissions they need. The empty list is valid, in which case
a default set of permissions is provided that includes internet access (but not LAN access) and a generous quota of
local disk storage. However arbitrary read/write access to all areas of local disk is not provided.

The Graviton API becomes available. This is analogous to the ``javax.jnlp`` package and provides access to services
inside the sandbox. Opting into the sandbox eliminates the warning shown when starting an app for the first time.

The Graviton API exposes:

1. A UI embeddeding framework to allow apps to reside within the shell window.
2. The PowerBox pattern whereby the app can request a file open dialog that's controlled by the system, and access to the resulting file is granted.
3. Permissions are remembered and can be requested in bulk up front to give a smoother user experience.
4. Objects can be exported via DBUS or COM for other native apps to interact with, this is useful for e.g. importing
   data into MS Office.
5. Support for claiming file associations.