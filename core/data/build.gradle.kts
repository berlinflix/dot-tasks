plugins {
    alias(libs.plugins.dot.android.library)
    alias(libs.plugins.dot.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.suyash.dot.core.data"
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core:domain"))
    implementation(project(":core:crypto"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.sqlite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.sqlcipher.android)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.turbine)
}
