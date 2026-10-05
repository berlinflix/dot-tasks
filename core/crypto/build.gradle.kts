plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.hilt)
}

android {
    namespace = "dev.suyash.dot.core.crypto"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.play.services.auth.blockstore)
    api(libs.tink.android)
}
