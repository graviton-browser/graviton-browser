# Graviton Browser

This repository contains design ideas, notes and experiments related to the creation of a prototype application browser
for multi-language JVM based GUI and command line apps.

**[Visit our design website to learn more](http://graviton-browser.readthedocs.io/en/latest/)**

We discuss these ideas in the #graviton-browser channel of the [Kotlin Slack](http://slack.kotlinlang.org/).

# What's implemented?

The foundations of a silent Chrome-style daily auto update are laid for macOS and Windows (we assume Linux users will
sort themselves out via snap, flatpak, apt-get etc). A bootstrap program written in Kotlin/Native scans the install
directory for versioned directories, picks the highest, and invokes the real program within. This means that it's
possible to upgrade the browser whilst it's running, thus all upgrades can be perceived as instant, as with Chrome.

Packaging using javapackager is also provided. Unfortunately we can't use jlink yet, although the Windows image is
optimised slightly using AppCDS.

Next steps:

* A nice icon
* Registration of background update tasks
* Implementation of the background updater