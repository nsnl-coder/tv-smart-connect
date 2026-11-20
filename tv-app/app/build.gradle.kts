plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val serverUrl = (project.findProperty("serverUrl") as String?) ?: "https://tv-smart-connect.onrender.com"

android {
    namespace = "com.tvsmartconnect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tvsmartconnect"
        minSdk = 22 // Fire OS 5 (Fire TV Stick đời đầu) trở lên
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Cài tay (sideload) nên ký bằng khoá debug là đủ.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.zxing:core:3.5.3")
}
