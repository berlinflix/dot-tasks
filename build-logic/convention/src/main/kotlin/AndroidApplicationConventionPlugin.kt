import com.android.build.api.dsl.ApplicationExtension
import dev.suyash.dot.buildlogic.configureAndroidCommon
import dev.suyash.dot.buildlogic.configureKotlinCompiler
import dev.suyash.dot.buildlogic.libs
import dev.suyash.dot.buildlogic.versionInt
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureAndroidCommon(this)
                defaultConfig.targetSdk = libs.versionInt("targetSdk")
            }
            configureKotlinCompiler()
        }
    }
}
