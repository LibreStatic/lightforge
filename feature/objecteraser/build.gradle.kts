plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.librestatic.lightforge.feature.objecteraser"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") { initWith(getByName("release")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:thumbnail"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:subjectclip"))
    implementation(project(":core:ml"))
    implementation(libs.litert.api) {
        // The model is downloaded from the pinned release asset, never through Play AI delivery.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
