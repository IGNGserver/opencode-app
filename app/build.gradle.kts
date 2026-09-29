plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
}
if (file("google-services.json").exists()) pluginManager.apply("com.google.gms.google-services")

// Local release builds may use the debug key for verification. Every publishing build sets
// REQUIRE_RELEASE_SIGNING and must use the protected keystore and fixed certificate.
val releaseKeystorePath: String? = System.getenv("OPENCODE_MOBILE_KEYSTORE_FILE")
val releaseKeystorePassword: String? = System.getenv("OPENCODE_MOBILE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("OPENCODE_MOBILE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("OPENCODE_MOBILE_KEY_PASSWORD")
val hasReleaseSigning = listOf(releaseKeystorePath, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

if (System.getenv("OPENCODE_MOBILE_REQUIRE_RELEASE_SIGNING") == "true") {
  require(hasReleaseSigning && file(releaseKeystorePath!!).isFile) { "发布构建必须提供完整的稳定签名输入" }
}
android {
  namespace = "com.igng.opencode.mobile"
  compileSdk = 36
  defaultConfig {
    applicationId = "com.igng.opencode.mobile"
    minSdk = 26
    targetSdk = 36
    versionCode = System.getenv("OPENCODE_MOBILE_VERSION_CODE")?.toInt() ?: 3
    versionName = System.getenv("OPENCODE_MOBILE_VERSION_NAME") ?: "0.1.0"
  }
  if (hasReleaseSigning) {
    signingConfigs {
      create("release") {
        storeFile = file(releaseKeystorePath!!)
        storePassword = releaseKeystorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }
  buildTypes {
    release {
      // A distributable build must never be debuggable; keep the debug key as a non-publishable
      // fallback so local `assembleRelease` still works without the production keystore.
      isDebuggable = false
      signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
    }
  }
  buildFeatures { compose = true }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlin { jvmToolchain(17) }
  testOptions { unitTests.isReturnDefaultValues = true }
}
// Unit tests read the shared cross-language contract from the repository root.
tasks.withType<Test>().configureEach { workingDir = rootDir }
dependencies {
  implementation("androidx.core:core-ktx:1.17.0")
  implementation("androidx.activity:activity-compose:1.10.1")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation(platform("androidx.compose:compose-bom:2024.12.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.foundation:foundation")
  implementation("androidx.compose.material3:material3")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("com.google.firebase:firebase-messaging:25.0.1")
  testImplementation("junit:junit:4.13.2")
  testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
  testImplementation("org.json:json:20240303")
}

tasks.register("printReleaseApkPath") {
  doLast { println(layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile.absolutePath) }
}
