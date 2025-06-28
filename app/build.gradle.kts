plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "home.wyoming_android_tts"
    compileSdk = 35

    defaultConfig {
        applicationId = "home.wyoming_android_tts"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

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
            applicationIdSuffix = ".debug"
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
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.preference:preference-ktx:1.2.1") // For settings screen

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
