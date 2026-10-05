plugins {
    alias(libs.plugins.dot.android.application)
    alias(libs.plugins.dot.android.compose)
    alias(libs.plugins.dot.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services)
}

android {
    namespace = "dev.suyash.dot"

    defaultConfig {
        applicationId = "dev.suyash.dot"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    implementation(platform(libs.firebase.bom))
    // App Check: Play Integrity in release; the debug provider never ships in release builds.
    releaseImplementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)
}
