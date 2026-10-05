import dev.suyash.dot.buildlogic.lib
import dev.suyash.dot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt via KSP for Android modules. */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.google.devtools.ksp")
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies {
                "implementation"(libs.lib("hilt-android"))
                "ksp"(libs.lib("hilt-compiler"))
                "testImplementation"(libs.lib("hilt-android-testing"))
                "kspTest"(libs.lib("hilt-compiler"))
            }
        }
    }
}
