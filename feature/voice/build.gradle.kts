plugins {
    alias(libs.plugins.dot.android.feature)
}

android {
    namespace = "dev.suyash.dot.feature.voice"
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
