#!/bin/bash -x

set -e

v=$( ./gradlew -q printVersion )
export GRAVITON_VERSION=$v

echo "Building macOS package for Graviton $v"
echo

jarname=`pwd`/online-update-packages/$v.mac.jar
mkdir -p online-update-packages

./gradlew copyBootstrapToLibs

srcfiles=$( cd build/install/graviton/lib; echo * | sed 's/ /:/g' )

# TODO: Signing
# TODO: Configure to use the serial GC.

javapackager -deploy \
             -nosign \
             -native dmg \
             -outdir build/packaged \
             -outfile "Graviton" \
             -name "Graviton" \
             -appclass net.plan99.graviton.Graviton \
             -srcdir build/install/graviton/lib \
             -srcfiles $srcfiles \
             "-Bicons=package/macosx/Graviton.icns" \
             -Bidentifier=net.plan99.graviton \
             -BmainJar=graviton-$v.jar \
             -BappVersion=$v \
             -Bmac.CFBundleIdentifier=net.plan99.graviton \
             -verbose

if [[ "$1" == "--skip-jar" ]]; then
    exit 0
fi

hdiutil attach "build/packaged/bundles/Graviton-$v.dmg"
cd "/Volumes/Graviton/Graviton.app/Contents/$v"
jar cvf $jarname .
cd -
umount "/Volumes/Graviton"
jarsigner -keystore keystore.p12 $jarname $USER