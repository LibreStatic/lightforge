plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ugallery.core.selection"
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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:preferences"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit4)
}
