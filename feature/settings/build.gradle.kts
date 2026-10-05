plugins {
    alias(libs.plugins.dot.android.feature)
}

android {
    namespace = "dev.suyash.dot.feature.settings"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
