plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.suyash.dot.core.sync"
}

dependencies {
    api(project(":core:auth"))
    api(project(":core:crypto"))
    implementation(project(":core:data"))
    implementation(project(":core:domain"))

    api(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.protobuf)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.kotlinx.serialization.protobuf)
}
