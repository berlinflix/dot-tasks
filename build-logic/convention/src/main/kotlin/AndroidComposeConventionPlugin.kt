import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import dev.suyash.dot.buildlogic.lib
import dev.suyash.dot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/** Enables Jetpack Compose for an Android module (apply after the application/library plugin). */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val android: CommonExtension = when {
                pluginManager.hasPlugin("com.android.application") ->
                    extensions.getByType(ApplicationExtension::class.java)
                pluginManager.hasPlugin("com.android.library") ->
                    extensions.getByType(LibraryExtension::class.java)
                else -> error("dot.android.compose must be applied after an Android plugin in $path")
            }
            android.buildFeatures.compose = true

            dependencies {
                val bom = platform(libs.lib("androidx-compose-bom"))
                "implementation"(bom)
                "androidTestImplementation"(bom)
                "implementation"(libs.lib("androidx-compose-runtime"))
                "implementation"(libs.lib("androidx-compose-ui-tooling-preview"))
                "debugImplementation"(libs.lib("androidx-compose-ui-tooling"))
                "debugImplementation"(libs.lib("androidx-compose-ui-test-manifest"))
                "androidTestImplementation"(libs.lib("androidx-compose-ui-test-junit4"))
            }

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                // Opt in to compiler metrics with -Pdot.composeReports=true
                if (providers.gradleProperty("dot.composeReports").orNull == "true") {
                    reportsDestination.set(layout.buildDirectory.dir("compose-reports"))
                    metricsDestination.set(layout.buildDirectory.dir("compose-metrics"))
                }
                stabilityConfigurationFiles.add(
                    rootProject.layout.projectDirectory.file("compose-stability.conf"),
                )
            }
        }
    }
}
