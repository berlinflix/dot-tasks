import dev.suyash.dot.buildlogic.lib
import dev.suyash.dot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/** A UI feature module: Android library + Compose + Hilt + the core modules every feature needs. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("dot.android.library")
            pluginManager.apply("dot.android.compose")
            pluginManager.apply("dot.hilt")

            dependencies {
                "implementation"(project(":core:domain"))
                "implementation"(project(":core:data"))
                "implementation"(project(":core:designsystem"))
                "implementation"(project(":core:ui"))

                "implementation"(libs.lib("androidx-hilt-navigation-compose"))
                "implementation"(libs.lib("androidx-lifecycle-runtime-compose"))
                "implementation"(libs.lib("androidx-lifecycle-viewmodel-compose"))
                "implementation"(libs.lib("androidx-navigation-compose"))
                "implementation"(libs.lib("kotlinx-collections-immutable"))

                "testImplementation"(libs.lib("turbine"))
            }
        }
    }
}
