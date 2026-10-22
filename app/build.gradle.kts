plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Numéro de compilation fourni par GitHub : chaque APK a un numéro de version plus grand que le précédent.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0

android {
    namespace = "com.antivol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.antivol.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 400 + buildNumber
        versionName = "0.4.$buildNumber"
        // Le S22 est en 64 bits : on ne garde que ça pour ne pas gonfler l'APK pour rien.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Clé de signature fixe : chaque nouvel APK s'installe PAR-DESSUS l'ancien, sans désinstaller.
    signingConfigs {
        create("antivol") {
            storeFile = file("antivol.jks")
            storePassword = "antivol123"
            keyAlias = "antivol"
            keyPassword = "antivol123"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("antivol") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("antivol")
        }
    }

    // Les modèles de reconnaissance doivent rester non compressés pour être lus directement.
    androidResources {
        noCompress += listOf("tflite", "onnx")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    // Reconnaissance du visage : détection (ML Kit) + comparaison (TensorFlow Lite).
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
}
