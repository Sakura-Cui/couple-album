import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signing = Properties()
val signingPath = System.getenv("SAKURA_SIGNING_PROPERTIES")
if (signingPath != null) file(signingPath).inputStream().use { signing.load(it) }

val vivo = Properties()
val vivoConfig = System.getenv("SAKURA_VIVO_PROPERTIES")
if (vivoConfig != null) file(vivoConfig).inputStream().use { vivo.load(it) }
val vivoAppId = vivo.getProperty("VIVO_APP_ID", "0")
val vivoAppKey = vivo.getProperty("VIVO_APP_KEY", "")
require(vivoAppId.matches(Regex("[0-9]+")) && vivoAppKey.matches(Regex("[A-Za-z0-9-]*"))) { "Invalid vivo app configuration" }

android {
    namespace = "com.sakura.romance"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.sakura.romance"
        minSdk = 26
        targetSdk = 35
        versionCode = 51
        versionName = "1.2.26"
        resValue("string", "vivo_app_id", vivoAppId)
        resValue("string", "vivo_app_key", vivoAppKey)
        buildConfigField("String", "VIVO_APP_KEY", "\"$vivoAppKey\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingPath != null) {
        signingConfigs.create("privateRelease") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = false
            if (signingPath != null) signingConfig = signingConfigs.getByName("privateRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(files("libs/vpush_clientSdk_v4.1.5.0_515.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.airbnb.android:lottie-compose:6.7.1")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
