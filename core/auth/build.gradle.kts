plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.hilt)
}

android {
    namespace = "dev.suyash.dot.core.auth"
}

dependencies {
    // The BoM must be `api` too: consumers need it to resolve the version of the `api` Firebase artifact.
    api(platform(libs.firebase.bom))
    api(libs.firebase.auth)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.kotlinx.coroutines.play.services)
}
