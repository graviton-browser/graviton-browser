Roadmap
*******

Here are various feature ideas and plans broken down by area, to give an idea of where the project might go.
GitHub Issues is the canonical issue tracker for the project.

Shell UI
--------

**Beyond coordinates.** In future, other forms of app identifier beyond Maven coordinates may be considered, like git/github URLs.

**Agile**. The shell will become separated from the main browser and updatable independently, so we can rapidly push new versions
and iterate the content.

**URL handler.** Graviton will register a URL handler so it can be invoked from the web. Clicking a URL will open the
shell window with the app coordinate pre-filled but the user will still be expected to press enter to run it. This is
so the user gets a chance to opt in or out of sandboxing, and also to train the user to go straight to Graviton to run
apps (it's faster for them and reduces the risk of browser makers trying to kill the platform by blocking the URL scheme).

**App streaming.** The average web page is 2mb in size. Experimentation shows that many apps can easily be
made to fit within this size budget using pack200 compression and by not re-downloading commonly used dependencies.
Please see :doc:`code-fetching` for more information on how code is fetched and kept fresh. Various optimisations
will be implemented to move beyond the rudimentary Maven repository protocol and enable "instant on" apps.

**Custom app tile artwork.** Allow apps to customise their tile with background art, rich text, quick launch buttons.

**Tabbed UI.** Allow multiple apps to run simultaneously with a Chrome-style tabbed UI.

**Loading splash.** Apps can provide a logo image that'll be displayed whilst an app is being initialized.

**JediTerm for Windows users.** The Windows shell is notoriously not very good. Allow CLI apps to be run from the
Graviton GUI for Windows users, using JetBrains JediTerm.

App discovery
-------------

**Showcase.** The shell may create tiles for apps the user has never used before, to advertise great or interesting
desktop apps that Graviton can launch.

**mDNS/Bonjour discovery.** Be able to locate domain names and apps that are advertising themselves on the local network.
Whilst this would be ineffective for very large enterprise networks that are multi-segment and do not support broadcast,
it is sufficient for IoT devices to advertise themselves (e.g. printers, wifi hotspots, etc). It is also sufficient for
factory floor applications, smaller offices, and so on. mDNS/Bonjour names look like this "foobar.local" where the name
is chosen by the app itself.

**Enterprise internal repositories.** For larger networks, Graviton will support a variety of "well known"
(i.e. hard coded) domain names that may be pointed at internal Maven repositories. By linking continuous integration
systems to an internal Artifactory or Nexus deployment, code will be automatically pushed direct from source repositories
through to the desktops in a smooth and silent manner.

**IoT secure repositories.** A server may present a self signed certificate if it was reached via mDNS. This certificate
is then remembered and may not change in future without generating a giant red scary warning page. This is different to
how web browsers use SSL and is intended to make life easier for internal app developers and embedded devices that struggle
to obtain a web PKI certificate today.

**P2P networks.** In future the peer to peer DAT protocol may be interesting as an additional protocol, if consumer
use cases turn out to be more popular than hoped for.

**Active Directory and other SSO integration.** Internet Explorer and some other browsers allow for automatic remote
sign-in based on local credentials, when the network is properly configured. It'd be nice to enable this automatically
for apps that can benefit from it, for arbitrary remote servers and app repositories.

Versions and updates
--------------------

**Version control.** Allow the user to pin apps to a particular version (partially implemented already). Allow network
admins to do the same for both apps and the JVM itself, and also to trigger rollbacks in case of regressions.

**Don't use metered connections.** Try to detect roaming connections and don't do updates when using them.

**Target versioning.** Allow apps to declare what version of Graviton/Java they were tested against. Enable backwards
compatibility goo for older apps to keep them working, allowing developers to opt-in to potentially breaking changes
when they're ready.

**Just-in-time module downloads.** Expose Maven resolution via the Graviton API, so that apps can request plugins and
additional features be downloaded and added to their classpath on the fly.

**Better JitPack integration.** If an app request is being satisfied by jitpack.io then monitor build progress and feed
it back to the user.

**Support reproducible builds inside SGX enclaves.** Investigate using Oblivium to do what JitPack does, but in a
remotely auditable manner, so anyone can run build servers.

**Better app distribution format than JARs.** The JAR format is old and works well enough for now but pack200 showed
it's possible to improve on it significantly.

**Canary channel.** Distribute regular automated daily OpenJDK and OpenJFX builds through a canary channel to allow for
rapid-fire testing of new code.

**Delta updates.** Download only what changed between Graviton releases, to reduce bandwidth consumption and improve
reliability.

Security
--------

**Sandboxing.** Resurrect the applet sandbox and make it fit for the 21st century. Use the ``SecurityManager`` infrastructure
to implement the PowerBox pattern, in which sandboxing rules are tweaked on the fly via natural UI interactions like
using the operating system file open dialogs, drag and drop and so on. Allow an app's activity to be watched, and
allow the sandbox to have multiple levels of aggression to reflect the varying nature of trust. No code signing required.

**Dependency pinning.** Avoid problems with hacked CDNs or overlapping artifacts, by allowing JARs to specify the hashes of their entire
dependency graphs. Useful in combination with:

**Signature consistency enforcement.** If an app is signed with a public key (or sub-key), require it to continue being
signed with that key. This ensures only the original app publisher can publish updates and is similar to the Android
scheme, in which app identity is linked to the signing key. *This is not the same as CA legal identity code signing*.
Sandbox permissions would become linked to the signing keys rather than coordinates.

**JVM security upgrades with Arabica.** Arabica is a way to run some of the native libraries that come with a JVM
(like e.g. GUI libraries) inside a Google NaCL sandbox for native code. It could be integrated to provide a more robust
virtual machine.

**Execution timeouts.** Terminate an app forcibly if it goes into an infinite loop - requires Graal, see below.

Java 11+ features
-----------------

**JavaScript/LLVM.** Graviton is not Kotlin or JavaFX specific. It should come with the Graal compiler and Truffle
backends, as GraalVM itself does. In this way apps should be authorable in JavaScript, Python, Ruby, C++, Rust, Haskell
and so on, if they depend on the right runtime modules that Graal can recognise.

Graal is on the verge of offering several features that are of particular interest:

* Support for NodeJS modules.

* Ability to impose execution time limits and interrupt execution asynchronously, to break infinite loops. This is
  effectively a compiler-supported version of the deprecated Thread.stop()  and is useful for browser style code
  sandboxing. In early versions it is acceptable for Graviton to hang in the face of a DoS attack by a malicious app - it
  is unlikely to matter for the initial use cases.

* Support for Python, Ruby and LLVM. Thus Graviton programs could conceivably utilise sandboxed modules written in C/C++,
  offering an alternative to WebAssembly.

**Modules.** Graviton should assemble the module and classpaths automatically, placing modular JARs onto the module
path by default and the rest onto the classpath. Graviton may additionally split JARs into multiple layers in order
to automatically resolve version conflicts, when an app has to use two different and conflicting versions of the same
module (common with Guava). When not run in a sandbox all modules should be opened to the app, or the app should be able to
request a list of --add-opens flags, to avoid crashes due to module encapsulation.

**Auto AOT.** Graviton should pre-optimise the JVM image using ahead of time compilation for the ``java.base`` module,
and consider AOT compiling modules of apps that have opted in via a background task, for faster startup. Tools that
indicate they can support it could be automatically fed through SubstrateVM to generate small native binaries.

**JavaFX.** Bundle all JavaFX modules out of the box so app developers don't have to worry about this.

Command line tools
------------------

Graviton already provides some nice features to CLI programs, such as activating ANSI escapes on Windows and auto-update.
But there is much more we can do here.

**Aliases.** When an app is used for the first time, make a small startup script in a special directory named (by default)
after the artifact name that acts as an alias for `graviton coordinate`. This directory can be added to the end of the
PATH, allowing you to start the tool in a natural way after the first time.

**CLI store.** In the background update task, pre-populate a different special directory with launchers for popular tools you
*haven't* used yet, selected by the Graviton team. If this is at the end of the PATH, then you may find there is no need
to ever install CLI tools again: on first run the tool will be downloaded and then kept fresh for you.

**Update callbacks.** If a CLI app is Graviton-aware, run a callback if a new version is downloaded. If the app is a long
running server, it can then choose to restart itself to pick up the new version. This allows for self-updating servers.

**Start-on-boot registration.** Abstract the OS start-on-boot mechanisms e.g. systemd and Windows Services, to make
server installation and maintenance absolutely painless.

**Sandboxing.** CLI specific sandboxing, so untrusted CLI tools can be invoked. Never run ``curl ... | bash`` ever again!
For apps where it fits, command line parameter parsing can be used to auto-configure the sandbox e.g. to only include
paths and servers that were passed in.

Gaming features
---------------

**EGL and advanced graphics.** The Java game dev community is surprisingly large and successful - consider that Minecraft
came out of it. They would be a great market segment to target and a potentially enthusiastic userbase. For this to work
they need access to OpenGL contexts. A simple starting point is to let them run unsandboxed in a separate JVM instance.
Later versions of Graviton could offer an API to open a new OpenGL window and expose the handle back such that it could
be combined with JMonkeyEngine, LWJGL and other popular game engines. Chrome uses an open source layer to implement
EGL on top of Direct3D which improves support on Windows, and it could be integrated into Graviton. Once this work is
done an eGL surface for JavaFX apps should be relatively straightforward for experienced systems/graphics programmers.

**API for opening ports via UPnP.** Expose an API to allow sandboxed apps to request firewall/NAT port forwarding.

**Sandboxed full screen mode.** Provide a browser-style full screen mode which helps the user exit it, independently
of the app itself.

Desktop integration
-------------------

**Integration with native desktop IPC.** Graviton apps should be able to expose control surfaces via platform native
OO IPC mechanisms, in particular, COM and DBUS. This would allow scripting and interaction with Graviton apps from
tools like MS Office macros.

**Support cross platform file associations.** Enable registration of file extensions to Graviton apps. The user would
be prompted the first time such a registration is used (*not* registered) if they want to open the file type with that
app, so registration can be seamless and 'optimistic'.

**Single-instance mode.** Only allow a single instance of an app to be run at once, if the app has requested that.

**Connectivity callbacks.** Expose a JavaFX observable bean that indicates the current status of network connectivity,
along with a higher level API so downloads can be transparently paused and resumed when the network is back.