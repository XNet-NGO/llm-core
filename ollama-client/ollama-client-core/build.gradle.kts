plugins {
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kover)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

kotlin {
    jvm()
    androidTarget()
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // put your Multiplatform dependencies here
            api(projects.common)
        }

        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            api(projects.common)
        }

        macosMain.dependencies { api(libs.ktor.client.darwin) }

        jvmMain.dependencies { api(libs.ktor.client.cio) }

        androidMain.dependencies { api(libs.ktor.client.okhttp) }

        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.bundles.jvm.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.koin.test)
            implementation(libs.koin.test.junit5)
            implementation(libs.app.cash.turbine)
            implementation("com.tngtech.archunit:archunit-junit5:1.4.1")
            implementation("org.reflections:reflections:0.10.2")
            implementation("org.junit.platform:junit-platform-launcher")
        }
    }
}

tasks { named<Test>("jvmTest") { useJUnitPlatform() } }

android {
    namespace = "com.tddworks.ollama.client"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
}
