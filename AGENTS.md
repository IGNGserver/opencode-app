# OpenCode Lagoon repository

Collaboration: collaborative
Validation: `ANDROID_HOME=/home/lvziw/Android/Sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug` and `cd companion && node --test`
Release: use `.github/workflows/release.yml`. If the user does not specify a release type, publish a pre-release. Every release tag must include a Chinese update note at `docs/releases/<tag>.md`; see `docs/RELEASE_POLICY.md`.

The Android app is a native OpenCode Server client. Keep server data authoritative and keep credentials out of source control. The optional companion carries only task metadata and FCM device registration.
