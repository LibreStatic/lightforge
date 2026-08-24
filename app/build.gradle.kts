plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.ugallery.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ugallery.app"
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
            applicationIdSuffix = ".debug"
        }
        release {
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

dependencies {
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
    implementation(project(":core:selection"))
    implementation(project(":core:editing-image"))
    implementation(project(":core:raw"))
    implementation(project(":core:editing-video"))
    implementation(project(":core:security"))
    implementation(project(":feature:photoeditor"))
    implementation(project(":feature:videoeditor"))
    implementation(project(":feature:privatealbum"))
    implementation(project(":feature:motionphotos"))
    implementation(project(":feature:collage"))
    implementation(project(":feature:widget"))
    implementation(project(":feature:places"))
    implementation(project(":feature:subjectclip"))
    implementation(project(":feature:objecteraser"))
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    baselineProfile(project(":baselineprofile"))
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
