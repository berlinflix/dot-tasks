import com.android.build.api.dsl.LibraryExtension
import dev.suyash.dot.buildlogic.configureAndroidCommon
import dev.suyash.dot.buildlogic.configureKotlinCompiler
import dev.suyash.dot.buildlogic.lib
import dev.suyash.dot.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureAndroidCommon(this)
                if (file("consumer-rules.pro").exists()) {
                    defaultConfig.consumerProguardFiles("consumer-rules.pro")
                }
            }
            configureKotlinCompiler()
            dependencies {
                "testImplementation"(libs.lib("junit4"))
                "testImplementation"(libs.lib("truth"))
                "testImplementation"(libs.lib("kotlinx-coroutines-test"))
                // Android framework tests run on a device/emulator (androidTest); JVM tests stay pure.
                "androidTestImplementation"(libs.lib("androidx-test-core"))
                "androidTestImplementation"(libs.lib("androidx-test-ext-junit"))
                "androidTestImplementation"(libs.lib("androidx-test-runner"))
                "androidTestImplementation"(libs.lib("truth"))
                "androidTestImplementation"(libs.lib("kotlinx-coroutines-test"))
            }
        }
    }
}
