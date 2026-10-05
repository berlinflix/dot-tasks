plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.android.compose)
}

android {
    namespace = "dev.suyash.dot.core.ui"
}

dependencies {
    api(project(":core:designsystem"))
    api(project(":core:domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.collections.immutable)
}
