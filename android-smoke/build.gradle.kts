// android-smoke — on-device verification harness (NOT a published library module).
// Packages openai-gateway-core + common into a debug APK whose SmokeActivity exercises
// real core code (ProviderConfig parse, OpenAIProvider.from, AwsSigV4Signer, EventStreamDecoder)
// on an actual arm64-v8a device and logs PASS/FAIL to logcat (tag SMOKE).
// Run: ./gradlew :android-smoke:assembleDebug && adb install -r <apk> && adb shell am start
//      -n com.tddworks.smoke/.SmokeActivity ; adb logcat -s SMOKE:*
plugins {
    id("com.android.application")
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    androidTarget()
    sourceSets {
        androidMain.dependencies {
            implementation(projects.openaiGateway.openaiGatewayCore)
            implementation(projects.common)
        }
    }
}

android {
    namespace = "com.tddworks.smoke"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.tddworks.smoke"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { getByName("debug") { isMinifyEnabled = false } }
}
