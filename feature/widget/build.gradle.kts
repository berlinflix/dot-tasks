plugins {
    alias(libs.plugins.dot.android.feature)
}

android {
    namespace = "dev.suyash.dot.feature.widget"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
}
