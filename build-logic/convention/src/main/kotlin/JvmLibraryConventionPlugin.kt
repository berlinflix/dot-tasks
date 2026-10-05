import dev.suyash.dot.buildlogic.configureKotlinCompiler
import dev.suyash.dot.buildlogic.lib
import dev.suyash.dot.buildlogic.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Pure Kotlin/JVM module (no Android framework) — fast unit tests, no device needed. */
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.jvm")
            extensions.configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            extensions.configure<KotlinJvmProjectExtension> {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
            configureKotlinCompiler()
            dependencies {
                "testImplementation"(libs.lib("junit4"))
                "testImplementation"(libs.lib("truth"))
                "testImplementation"(libs.lib("kotlinx-coroutines-test"))
            }
        }
    }
}
