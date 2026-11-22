#!/bin/sh
# Builds two probe APKs (targetSdk 27 and 34) without Gradle.
# Usage: research/vss-probe/build.sh  -> research/vss-probe/out/vssprobe{27,34}.apk
set -eu
cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf out && mkdir -p out/classes

javac -source 8 -target 8 -nowarn -cp "$JAR" -d out/classes src/id/poncol/vssprobe/Main.java
"$BT/d8" --min-api 26 --lib "$JAR" --output out $(find out/classes -name '*.class')

for T in ${TARGETS:-27 34}; do
  cat > out/AndroidManifest.xml <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="id.poncol.vssprobe$T">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="$T"/>
  <uses-feature android:name="android.software.leanback" android:required="false"/>
  <uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
  <application android:label="VSS probe $T">
    <activity android:name="id.poncol.vssprobe.Main" android:exported="true"/>
  </application>
</manifest>
EOF
  "$BT/aapt2" link -I "$JAR" --manifest out/AndroidManifest.xml -o out/u.apk
  (cd out && zip -q u.apk classes.dex)
  "$BT/zipalign" -f 4 out/u.apk out/a.apk
  "$BT/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android \
    --out out/vssprobe$T.apk out/a.apk
  rm out/u.apk out/a.apk
done
ls -l out/*.apk
