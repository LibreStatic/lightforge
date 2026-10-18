plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librestatic.lightforge.core.legacyvideo"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // -Plightforge.abis=x86_64 limits local builds to one ABI; releases keep all three.
        ndk {
            abiFilters += providers.gradleProperty("lightforge.abis").orNull?.split(",")?.map(String::trim)
                ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    buildTypes { create("benchmark") { initWith(getByName("release")) } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric pokes FileDescriptor internals that newer JDKs no longer export.
        unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    api(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.media3.test.utils)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
