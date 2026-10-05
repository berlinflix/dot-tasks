plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.android.compose)
}

android {
    namespace = "dev.suyash.dot.core.designsystem"
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.animation)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.core.ktx)
}
