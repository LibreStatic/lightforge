plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librestatic.lightforge.core.frameinterpolation"
    compileSdk = 37
    ndkVersion = "27.1.12297006"

    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-fno-rtti")
                // NDK r27 still links with 4 KB pages by default; Play requires 16 KB ELF alignment for Android 15+ targets.
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
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
    packaging { jniLibs.useLegacyPackaging = false }
    androidResources.noCompress += listOf("bin", "param", "so")
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
