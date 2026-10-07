import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.dot.android.application)
    alias(libs.plugins.dot.android.compose)
    alias(libs.plugins.dot.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
    alias(libs.plugins.androidx.baselineprofile)
}

// Release signing (the Play upload key) comes from keystore.properties at the project root, which is
// git-ignored: storeFile, storePassword, keyAlias, keyPassword. Without it, release builds are unsigned.
val releaseSigning: Properties? = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText.orNull
    ?.let { text -> Properties().apply { load(StringReader(text)) } }

android {
    namespace = "dev.suyash.dot"

    defaultConfig {
        applicationId = "dev.suyash.dot"
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Keep native libs uncompressed and page-aligned (16 KB page devices).
            useLegacyPackaging = false
        }
    }
}

// Baseline Profile + startup profile, generated on a device by :baselineprofile and committed under
// src/release/generated/baselineProfiles, so regular builds and CI never need a device:
//   ./gradlew :app:generateBaselineProfile
baselineProfile {
    automaticGenerationDuringBuild = false
    saveInSrc = true
    // R8 lays out the dex so code used at startup is loaded together.
    dexLayoutOptimization = true
}

androidComponents {
    // The plugin's nonMinifiedRelease and benchmarkRelease build types must run the release code
    // (Play Integrity App Check), not the debug provider.
    onVariants(selector().withBuildType("nonMinifiedRelease")) { it.sources.kotlin?.addStaticSourceDirectory("src/release/kotlin") }
    onVariants(selector().withBuildType("benchmarkRelease")) { it.sources.kotlin?.addStaticSourceDirectory("src/release/kotlin") }
}

dependencies {
    implementation(project(":core:auth"))
    implementation(project(":core:data"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:domain"))
    implementation(project(":core:sync"))
    implementation(project(":core:ui"))
    implementation(project(":feature:account"))
    implementation(project(":feature:reminders"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:tasks"))
    implementation(project(":feature:voice"))
    implementation(project(":feature:widget"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.hilt.compiler)
    baselineProfile(project(":baselineprofile"))

    implementation(platform(libs.firebase.bom))
    // App Check: Play Integrity outside debug builds; the debug provider never ships in release builds.
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
}
