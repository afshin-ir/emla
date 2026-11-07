plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ir.afshin.emla"
    compileSdk = 35

    defaultConfig {
        applicationId = "ir.afshin.emla"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("AFRA_KEYSTORE_FILE") ?: "release-key.jks")
            storePassword = System.getenv("AFRA_KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("AFRA_KEY_ALIAS") ?: ""
            keyPassword = System.getenv("AFRA_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.13.1")
}

