import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

group = "dev.suyash.dot.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = libs.plugins.dot.android.application.get().pluginId
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = libs.plugins.dot.android.library.get().pluginId
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = libs.plugins.dot.android.compose.get().pluginId
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = libs.plugins.dot.android.feature.get().pluginId
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("hilt") {
            id = libs.plugins.dot.hilt.get().pluginId
            implementationClass = "HiltConventionPlugin"
        }
        register("jvmLibrary") {
            id = libs.plugins.dot.jvm.library.get().pluginId
            implementationClass = "JvmLibraryConventionPlugin"
        }
    }
}
