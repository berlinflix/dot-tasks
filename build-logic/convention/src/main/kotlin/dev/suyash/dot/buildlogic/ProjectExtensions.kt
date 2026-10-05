package dev.suyash.dot.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library alias '$alias' in libs.versions.toml") }

fun VersionCatalog.versionInt(alias: String): Int =
    findVersion(alias).orElseThrow { IllegalStateException("Missing version '$alias'") }.requiredVersion.toInt()

/** Shared Android configuration for application and library modules. */
internal fun Project.configureAndroidCommon(android: CommonExtension) {
    android.compileSdk = libs.versionInt("compileSdk")
    android.defaultConfig.minSdk = libs.versionInt("minSdk")
    android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    android.testOptions.unitTests.isIncludeAndroidResources = true
    android.testOptions.unitTests.isReturnDefaultValues = true
    android.lint.abortOnError = true
    android.lint.checkReleaseBuilds = true
    android.lint.checkDependencies = true
    android.packaging.resources.excludes += setOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/LICENSE*",
        "/META-INF/NOTICE*",
    )
}

/** Kotlin compiler options shared by every Kotlin module (Android built-in Kotlin and JVM). */
internal fun Project.configureKotlinCompiler() {
    tasks.withType<KotlinCompilationTask<*>>().configureEach {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-Xconsistent-data-class-copy-visibility",
            )
        }
    }
    // Modules without unit tests still get generated test sources (Hilt); don't fail them.
    tasks.withType<Test>().configureEach {
        failOnNoDiscoveredTests.set(false)
    }
}
