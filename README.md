# Graviton Browser

This repository contains design ideas, notes and experiments related to the creation of a prototype application browser
for multi-language JVM based GUI and command line apps.

**[Visit our design website to learn more](http://graviton-browser.readthedocs.io/en/latest/)**

We discuss these ideas in the #graviton-browser channel of the [Kotlin Slack](http://slack.kotlinlang.org/).

# What's implemented?

1. The foundations of a silent Chrome-style daily auto update are laid for macOS and Windows (we assume Linux users will
   sort themselves out via snap, flatpak, apt-get etc). A bootstrap program written in Kotlin/Native scans the install
   directory for versioned directories, picks the highest, and invokes the real program within. This means that it's
   possible to upgrade the browser whilst it's running, thus all upgrades can be perceived as instant, as with Chrome.
2. Packaging using javapackager is also provided for Mac and Windows. Unfortunately we can't use jlink yet, although 
   the Windows image is optimised slightly using AppCDS.
3. An abstraction over the Mac and Windows OS task schedulers, which will be used to implement online update.
4. A command line interface that lets you start any program in Maven Central by coordinate. The goal of this is to both
   be useful, and to test out the speed of downloading things from Central.

# Usage from the CLI

If you want to try the command line launcher:

```
gradle installDist
cd build/install/graviton/bin
./graviton org.jetbrains.kotlin:kotlin-compiler -help
```

Any Maven coordinate that has a main class and identifies it in the JAR manifest should work. You can also specify
the version. If it's omitted the latest version will be used. Try:

`graviton com.github.ricksbrown:cowsay -f tux --cowthink moo!`

or

`graviton com.github.spotbugs:spotbugs`

to see it run a GUI app.

# GUI shell

There is a graphical shell that lets you start apps and see their output. Start it by running Graviton with no arguments.
You can then provide a command line like those above in the edit and press enter to run it.



# Next steps

* A nice icon
* Registration of background update tasks
* Implementation of the background updater