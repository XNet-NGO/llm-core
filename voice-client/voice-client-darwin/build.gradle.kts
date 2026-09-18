plugins {
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.touchlab.kmmbridge)
    alias(libs.plugins.touchlab.skie)
    `maven-publish`
}

kotlin {
    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach { macosTarget ->
        macosTarget.binaries.framework {
            export(projects.voiceClient.voiceClientCore)
            baseName = "VoiceClient"
            isStatic = true
        }
    }

    sourceSets {
        commonMain {
            dependencies {
                api(projects.voiceClient.voiceClientCore)
                implementation(libs.ktor.client.darwin)
            }
        }
        appleMain {}
    }
}

kmmbridge {
    gitHubReleaseArtifacts()
    spm(swiftToolVersion = "5.9", useCustomPackageFile = true, perModuleVariablesBlock = true) {
        iOS { v("15") }
        macOS { v("15") }
    }
}
