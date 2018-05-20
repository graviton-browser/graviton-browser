#!/bin/bash

set -e

v=$( ./gradlew -q printVersion )
jarname=`pwd`/online-update-packages/$v.mac.jar
mkdir -p online-update-packages

./gradlew packageMac
hdiutil attach "build/packaged/Graviton Browser-$v.dmg"
cd "/Volumes/Graviton Browser/Graviton Browser.app/Contents/$v"
jar cvf $jarname .
cd -
umount "/Volumes/Graviton Browser"
jarsigner -keystore keystore.p12 $jarname mike