#!/bin/bash

set -x
cd ../images/*/Graviton\ Browser.app/Contents/
mkdir -p $GRAVITON_VERSION/Contents
mv Java/ MacOS/ PlugIns/ $GRAVITON_VERSION/Contents
mkdir MacOS
mv $GRAVITON_VERSION/Contents/Java/bootstrap.kexe MacOS/Graviton\ Browser
cd $GRAVITON_VERSION/Contents/PlugIns/

# TODO: Maybe if the app gets larger activate AppCDS. This blog post says it's a 20% win for a large app and it's
# used on Windows ...
#
#   https://blog.codefx.org/java/application-class-data-sharing/#Creating-An-Application-Class-Data-Archive
#
# but when I tried it, it didn't seem to make much difference. It generates a massive file on disk that appears
# to duplicate most of the JDK though, so, to avoid bloating the download it'd have to be done in the background
# on first run or something.

# xterm