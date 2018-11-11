# Graviton Browser

This repository contains a prototype application browser for multi-language JVM based GUI and command line apps.

**[Visit graviton.app to learn more](https://graviton.app/)**

We discuss these ideas in the #graviton-browser channel of the [Kotlin Slack](http://slack.kotlinlang.org/).

# What's implemented?

1. A Chrome-style silent background auto update, that registers with the operating system task scheduler so the runtime
   can be kept up to date regardless of whether it's in use or not. This ensures the user never sees an update prompt or delay.
2. DMG/EXE packaging using javapackager is also provided for Mac and Windows. Unfortunately we can't use jlink yet.
3. A simple graphical shell that lets you start programs by Maven coordinate. It provides a basic history list.
4. A command line interface that lets you start any program by Maven coordinate.

# GUI shell

There is a graphical shell that lets you start apps and see their output. Start it by running Graviton with no arguments.

The coordinate bar actually accepts any command line arguments that you could pass to the Graviton CLI (see below).

Here are some to try:

* `plan99.net:tictactoe` - The actual coordinate is `net.plan99:tictactoe` but Graviton will reverse the group ID for you
  to give a more natural looking address.
* `com.github.edvin.tornadofx-samples:charts` - shows use of JitPack.io to automatically build and load apps from a
  Github repository.

# Usage from the CLI

If you want to try the command line launcher:

```
gradle installDist
cd build/install/graviton/bin
./graviton com.github.ricksbrown:cowsay "A fat cow is a happy cow!"
```

Any Maven coordinate that has a main class and identifies it in the JAR manifest should work. You can also specify
the version. If it's omitted the latest version will be used. Try:

`graviton com.github.ricksbrown:cowsay -f tux --cowthink moo!`

or

`graviton com.github.spotbugs`

to see it run a GUI app.

# Next steps

The first milestone is to get Graviton to the point where we can distribute alpha builds to interested people, which will auto update 
indefinitely. [The tasks required are tracked here](https://github.com/mikehearn/graviton-browser/projects/1)

# Artwork credits

This section will be moved to the about box, once implemented.

- Background art courtesy of Vexels.
- Icon courtesy of icons8.com
- 'Wire One' font courtesy of Google Fonts. 