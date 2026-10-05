plugins {
    alias(libs.plugins.dot.android.feature)
}

android {
    namespace = "dev.suyash.dot.feature.account"
}

dependencies {
    implementation(project(":core:sync"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}
