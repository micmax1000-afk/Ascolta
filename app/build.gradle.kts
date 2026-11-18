plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.micmax.ascolta"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.micmax.ascolta"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("ASCOLTA_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = "1.1.3"
        resourceConfigurations += listOf("it", "en")
    }

    // Firma fissa: la chiave arriva dai secret di GitHub (ASCOLTA_KEYSTORE_BASE64, ASCOLTA_KEYSTORE_PASSWORD).
    val ksFile = System.getenv("ASCOLTA_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    val ksPass = System.getenv("ASCOLTA_KEYSTORE_PASSWORD")
    signingConfigs {
        if (ksFile != null && !ksPass.isNullOrEmpty()) create("fixed") {
            storeFile = ksFile
            storePassword = ksPass
            keyAlias = "weblauncher"
            keyPassword = ksPass
        }
    }
    val fixedSigning = signingConfigs.findByName("fixed")

    buildTypes {
        release {
            if (fixedSigning != null) signingConfig = fixedSigning
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (fixedSigning != null) signingConfig = fixedSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.webkit:webkit:1.11.0")                // pagina di YouTube Music
    implementation("androidx.media3:media3-exoplayer:1.3.1")       // podcast e radio
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")         // puntate scaricate col wifi
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
