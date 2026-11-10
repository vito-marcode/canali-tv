import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The companion's address: companion.url in local.properties (not committed), or -PcompanionUrl=...
val localProps = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
val companionUrl: String = (findProperty("companionUrl") as String?)
    ?: localProps.getProperty("companion.url")
    ?: "http://raspberrypi.local:8765"

android {
    namespace = "it.mdv.dttchannels"
    compileSdk = 35
    defaultConfig {
        applicationId = "it.mdv.dttchannels"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "2.0.0"
        buildConfigField("String", "PI_URL", "\"$companionUrl\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") }
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
