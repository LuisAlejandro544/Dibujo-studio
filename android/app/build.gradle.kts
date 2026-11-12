// Módulo principal :app — Interfaz de usuario modular en Jetpack Compose + Material 3 + Material Icons.
// Conecta con :core:engine para delegar todo el procesamiento gráfico al motor Rust.
//
// Compatibilidad APK (Uptodown / descarga directa):
// - Incluye las 4 ABIs (32-bit armeabi-v7a/x86 y 64-bit arm64-v8a/x86_64) para instalar en cualquier teléfono.
// - minSdk = 26 para máxima estabilidad gráfica y de almacenamiento sin perder compatibilidad con Android 8+.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Modo de arquitectura configurable desde Gradle/CI ("universal" o "arm64-v8a").
val abiMode = providers.gradleProperty("trazoAbiMode").getOrElse("universal")
val selectedAbis = if (abiMode == "arm64-v8a") {
    listOf("arm64-v8a")
} else {
    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}

android {
    namespace = "com.trazostudio.draw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.trazostudio.draw"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += selectedAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Firma con clave debug de desarrollo si no hay keystore personalizado para facilitar pruebas en APK directo.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
