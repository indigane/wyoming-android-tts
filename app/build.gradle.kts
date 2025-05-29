plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "home.wyoming_android_tts"
    compileSdk = 34

    defaultConfig {
        applicationId = "home.wyoming_android_tts"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false // Set to true for production releases with ProGuard/R8
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro" // Create this file if you enable minifyEnabled
            )
        }
        debug {
            isApplicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8 // Or JavaVersion.VERSION_17 if preferred
        targetCompatibility = JavaVersion.VERSION_1_8 // Or JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "1.8" // Or "17"
    }
    // If you plan to use View Binding or Data Binding later:
    // buildFeatures {
    //     viewBinding = true
    // }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0") // Check for latest stable version
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0") // For lifecycle awareness, useful for services
    // For JSON parsing later (Phase 2), we might add:
    // implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
