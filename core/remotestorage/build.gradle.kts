plugins { alias(libs.plugins.android.library) }
android {
    namespace = "com.librestatic.lightforge.core.remotestorage"
    compileSdk = 37
    defaultConfig { minSdk = 30; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"; consumerProguardFiles("consumer-rules.pro") }
    buildTypes { create("benchmark") { initWith(getByName("release")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("com.hierynomus:sshj:0.40.0") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
    }
    implementation("com.hierynomus:smbj:0.15.0") { exclude(group = "org.bouncycastle", module = "bcprov-jdk18on") }
    implementation("org.bouncycastle:bcprov-jdk15to18:1.85.2")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.85")
    implementation("org.bouncycastle:bcutil-jdk15to18:1.85.1")
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.runner)
}
