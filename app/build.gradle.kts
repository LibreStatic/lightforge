plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

dependencyLocking {
    lockAllConfigurations()
}

android {
    namespace = "com.librestatic.lightforge"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.librestatic.lightforge"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("offline") {
            dimension = "distribution"
        }
        create("demo") {
            dimension = "distribution"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
        }
    }

    buildTypes {
        debug {
            // Explicit acceptance installs must never replace the user's debug application/data.
            applicationIdSuffix = if (providers.gradleProperty("lightforge.pdfAcceptance").orNull == "true") {
                ".pdfacceptance"
            } else {
                ".debug"
            }
        }
        release {
            // Opt-in minified acceptance APK is isolated from every user's normal/debug install.
            if (providers.gradleProperty("lightforge.remoteReleaseAcceptance").orNull == "true") {
                applicationIdSuffix = ".remoteacceptance"
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isDebuggable = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        localeFilters += listOf("en", "es", "fr", "pt", "it", "de")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

val verbatimLicenseCopies = listOf(
    "LICENSE" to "app/src/main/assets/licenses/Lightforge-Apache-2.0.txt",
    "COPYRIGHT" to "app/src/main/assets/licenses/Lightforge-Copyright.txt",
    "core/raw/third_party/libraw/LICENSE.LGPL" to "app/src/main/assets/licenses/LibRaw-LGPL.txt",
    "core/raw/third_party/libraw/LICENSE.CDDL" to "app/src/main/assets/licenses/LibRaw-CDDL.txt",
    "core/frame-interpolation/src/main/resources/META-INF/NCNN_LICENSE.txt" to
        "app/src/main/assets/licenses/ncnn-BSD-3-Clause-and-notices.txt",
    "core/frame-interpolation/src/main/resources/META-INF/RIFE_NCNN_LICENSE.txt" to
        "app/src/main/assets/licenses/rife-ncnn-vulkan-MIT.txt",
    "docs/models/licenses/SFACE_APACHE_2.0.txt" to
        "app/src/main/assets/licenses/SFace-Apache-2.0.txt",
    "feature/pdfstudio/src/main/assets/fonts/OFL.txt" to
        "app/src/main/assets/licenses/Noto-OFL-1.1.txt",
)

val verifyVerbatimLicenseCopies by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies that license assets are byte-for-byte copies of their canonical sources."
    inputs.files(verbatimLicenseCopies.flatMap { (source, bundled) -> listOf(source, bundled) })
    workingDir(rootProject.projectDir)
    commandLine(
        "bash",
        "-c",
        verbatimLicenseCopies.joinToString(" && ") { (source, bundled) ->
            "cmp --silent '$source' '$bundled' || { echo 'Bundled license is not a verbatim copy: $bundled' >&2; exit 1; }"
        },
    )
}

val verifyThirdPartyLicenses by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies that the checked-in third-party license catalog matches the runtime lockfile."
    inputs.files(
        "gradle.lockfile",
        rootProject.file("tools/generate_third_party_licenses.py"),
        "src/main/assets/third_party_licenses.json",
    )
    workingDir(rootProject.projectDir)
    commandLine(
        "python3",
        "tools/generate_third_party_licenses.py",
        "--lockfile",
        "app/gradle.lockfile",
        "--output",
        "app/src/main/assets/third_party_licenses.json",
        "--check",
    )
}

tasks.named("preBuild").configure {
    dependsOn(verifyVerbatimLicenseCopies)
    dependsOn(verifyThirdPartyLicenses)
}

dependencies {
    androidTestImplementation("org.maplibre.gl:android-sdk-opengl:13.6.0")
    androidTestImplementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Compile instrumented tests against the BouncyCastle family they run with, not the port's 1.72.
    constraints {
        androidTestImplementation("org.bouncycastle:bcprov-jdk15to18:1.85.2")
        androidTestImplementation("org.bouncycastle:bcpkix-jdk15to18:1.85")
        androidTestImplementation("org.bouncycastle:bcutil-jdk15to18:1.85.1")
    }
    implementation(project(":core:designsystem"))
    implementation(project(":core:navigation"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:mediastore"))
    implementation(project(":core:thumbnail"))
    implementation(project(":core:ml"))
    implementation(project(":core:search"))
    implementation(project(":core:preferences"))
    implementation(project(":feature:photos"))
    implementation(project(":feature:permissions"))
    implementation(project(":feature:collections"))
    implementation(project(":feature:album"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:details"))
    implementation(project(":feature:trash"))
    implementation(project(":feature:search"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:remotebackup"))
    implementation(project(":feature:ownsync"))
    implementation(project(":feature:localsharing"))
    implementation(project(":core:remotestorage"))
    implementation(project(":core:selection"))
    implementation(project(":core:editing-image"))
    implementation(project(":core:raw"))
    implementation(project(":core:editing-video"))
    implementation(project(":core:security"))
    implementation(project(":feature:photoeditor"))
    implementation(project(":feature:objecteraser"))
    implementation(project(":feature:subjectclip"))
    implementation(project(":feature:videoeditor"))
    implementation(project(":feature:privatealbum"))
    implementation(project(":feature:motionphotos"))
    implementation(project(":feature:collage"))
    implementation(project(":feature:pdfstudio"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:places"))
    implementation(project(":feature:semanticsearch"))
    implementation(project(":feature:petrecognition"))

    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.exifinterface)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
