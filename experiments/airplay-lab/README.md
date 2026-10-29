# TVCompanion AirPlay Lab

Optional development shell over the **same** runtime and selected native engine used by the main TVCompanion APK. It is not a production dependency or second required installation. The original harness proved Mac extended desktop on the Google TV; [historical results](../../docs/phase-2/TV_RECEIVER_EXPERIMENT.md) and [current integrated checkpoint](../../docs/phase-2/SINGLE_APK_INTEGRATION.md) distinguish those stages.

Native source preparation, patches, JNI and implementation now live under `android/integrations/uxplay-native` and `scripts/prepare-airplay-native.py`; there is no duplicate engine in this shell. The old preparation entry delegates to the shared script for continuity.

From the repository root, with JDK 17, Android SDK/build-tools 34, NDK 27.2.12479018 and SDK CMake 3.22.1:

```sh
python3 scripts/prepare-airplay-native.py
cd android
./gradlew --no-daemon -PairplayLab=true :experiments:airplay-lab:assembleDebug
```

The package remains `id.poncol.tvcompanion.airplaylab`; output is `experiments/airplay-lab/app/build/outputs/apk/debug/airplay-lab-debug.apk`. This shell uses **TVCompanion Lab** as its receiver name, while the main app uses **TVCompanion**. Run one receiver at a time. Open the receiver, use Screen Mirroring from the sender, and leave playback to stop it. Audio and background availability are not implemented yet.

Downloaded sources, notices and native outputs stay under ignored `android/integrations/uxplay-native/.native/`. No APKs or native binaries are committed. Source/script pins are recorded in the shared preparation script. Generated upstream notices are accessible in-app; they do not replace the [licensing/distribution review](../../docs/LICENSE_COMPLIANCE.md). No root license is adopted by this experiment. Follow the workspace’s authorized TV/ADB procedure when deploying.
