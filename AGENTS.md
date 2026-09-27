# OpenCode Mobile repository

Collaboration: collaborative
Validation: `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` and `cd companion && node --test`
Release: no automatic release; a release requires an explicit user request and real-device acceptance.

The Android app is a native OpenCode Server client. Keep server data authoritative and keep credentials out of source control. The optional companion carries only task metadata and FCM device registration.
