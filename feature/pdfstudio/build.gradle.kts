plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ugallery.feature.pdfstudio"
    compileSdk = 37
    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "com.ugallery.feature.pdfstudio.PdfRecoveryProbeRunner"
    }
    buildTypes {
        create("benchmark") { initWith(getByName("release")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; aidl = true }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":core:designsystem"))
    implementation(platform(libs.compose.bom))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.exifinterface)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Keep the Android-compatible family; override the port's obsolete 1.72 transitives.
    // Patch versions differ per artifact; reviewed against Maven Central release metadata.
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15to18:1.85.2") {
            because("Reviewed provider security fixes and 1.85 patch compatibility")
        }
        implementation("org.bouncycastle:bcpkix-jdk15to18:1.85") {
            because("Reviewed PKIX/CMS security fixes; no certificate validation feature added")
        }
        implementation("org.bouncycastle:bcutil-jdk15to18:1.85.1") {
            because("Match the reviewed ASN.1/CMS utility patch release")
        }
    }
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

// The Room asset-copy task otherwise snapshots the schema directory before KSP exports a
// newly introduced version. Order generation and export before packaging migration fixtures.
tasks.matching { it.name == "copyRoomSchemasToAndroidTestAssetsDebugAndroidTest" }.configureEach {
    dependsOn("kspDebugKotlin", "copyRoomSchemas")
}
tasks.matching { it.name == "copyRoomSchemas" }.configureEach {
    mustRunAfter("kspDebugKotlin")
}
