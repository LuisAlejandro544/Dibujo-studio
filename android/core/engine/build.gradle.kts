// Módulo :core:engine — Encapsula el puente JNI y la compilación obligatoria de la librería Rust (.so).
//
// Decisión de versión mínima de Android (minSdk = 26 / Android 8.0 Oreo):
// - El NDK moderno (r26+) y cargo-ndk generan binarios nativos más limpios y con menor código legado desde API 26.
// - API 26 habilita íconos adaptativos, MediaStore moderno y gestión segura de memoria gráfica sin hacks de API 21-24.
// - Cubre >96% de los dispositivos Android activos sin sacrificar estabilidad en hardware de 32 y 64 bits.

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val rustCrateDir = rootProject.file("rust/drawing_engine")
val jniLibsOutputDir = layout.projectDirectory.dir("src/main/jniLibs").asFile

// Tarea obligatoria de Gradle: compila el motor Rust para arquitecturas de 32 bits (armeabi-v7a, x86)
// y 64 bits (arm64-v8a, x86_64) antes de empaquetar cualquier APK o AAR.
val buildRustNativeLibs by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila libtrazo_engine.so con cargo-ndk para 32-bit y 64-bit."

    workingDir = rustCrateDir
    inputs.dir(rustCrateDir.resolve("src"))
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsOutputDir)

    commandLine(
        "cargo", "ndk",
        "-t", "armeabi-v7a",
        "-t", "arm64-v8a",
        "-t", "x86",
        "-t", "x86_64",
        "-p", "26",
        "-o", jniLibsOutputDir.absolutePath,
        "build", "--release"
    )
}

android {
    namespace = "com.trazostudio.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Soporte explícito para dispositivos de 32 bits y 64 bits (ARM y x86 para emuladores/tablets).
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

tasks.named("preBuild") {
    dependsOn(buildRustNativeLibs)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
