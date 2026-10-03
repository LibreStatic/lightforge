// Development-only Compose Driver host: serves one zero-argument composable over HTTP from a
// Robolectric unit test so agents can read its semantics tree, screenshot it and interact with it.
// Nothing depends on this module; it is only configured when its tasks are requested.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.librestatic.lightforge.tools.composedriver"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { it.enable = false }
}

dependencies {
    // Feature modules whose debug source sets hold driver previews. Add a module here when you add
    // previews to it; keep the list short so the server compiles and starts quickly.
    testImplementation(project(":feature:photoeditor"))
    testImplementation(project(":feature:collections"))
    testImplementation(project(":core:designsystem"))
    testImplementation(project(":feature:details"))
    testImplementation(project(":feature:viewer"))
    testImplementation(project(":feature:photos"))
    testImplementation(project(":feature:picker"))
    testImplementation(project(":feature:trash"))
    testImplementation(project(":feature:album"))
    testImplementation(project(":feature:onboarding"))
    testImplementation(project(":feature:settings"))
    testImplementation(project(":feature:videoeditor"))

    testImplementation(libs.compose.driver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext)
    testImplementation(platform(libs.compose.bom))
    debugImplementation(platform(libs.compose.bom))
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

val composable = providers.gradleProperty("composeDriver.composable")
val port = providers.gradleProperty("composeDriver.port").orElse("8137")
val qualifiers = providers.gradleProperty("composeDriver.qualifiers")
val fontScale = providers.gradleProperty("composeDriver.fontScale")

tasks.withType<Test>().configureEach {
    // The server test blocks until the process is stopped; never treat it as up to date.
    outputs.upToDateWhen { false }
    maxHeapSize = "2g"
    // Robolectric's SDK 36 runtime pokes FileDescriptor internals that newer JDKs no longer export.
    jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
    testLogging { showStandardStreams = true }
    composable.orNull?.let { systemProperty("compose.driver.composable", it) }
    systemProperty("compose.driver.port", port.get())
    qualifiers.orNull?.let { systemProperty("compose.driver.qualifiers", it) }
    fontScale.orNull?.let { systemProperty("compose.driver.fontScale", it) }
}
