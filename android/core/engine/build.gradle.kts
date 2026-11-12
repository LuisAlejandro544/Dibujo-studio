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

// Modo de arquitectura configurable desde Gradle/CI:
// - "universal" (predeterminado): compila las 4 ABIs (armeabi-v7a, arm64-v8a, x86, x86_64).
// - "arm64-v8a": compila únicamente la arquitectura móvil de 64 bits (más rápido y liviano).
val abiMode = providers.gradleProperty("trazoAbiMode").getOrElse("universal")
val selectedAbis = if (abiMode == "arm64-v8a") {
    listOf("arm64-v8a")
} else {
    listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
}

// Tarea obligatoria de Gradle: compila el motor Rust con cargo-ndk para las ABIs seleccionadas
// antes de empaquetar cualquier APK o AAR.
val buildRustNativeLibs by tasks.registering(Exec::class) {
    group = "rust"
    description = "Compila libtrazo_engine.so con cargo-ndk según trazoAbiMode ($abiMode)."

    workingDir = rustCrateDir
    inputs.property("trazoAbiMode", abiMode)
    inputs.dir(rustCrateDir.resolve("src"))
    inputs.file(rustCrateDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsOutputDir)

    doFirst {
        // Limpia librerías nativas previas para evitar mezclar ABIs al cambiar de modo.
        if (jniLibsOutputDir.exists()) {
            jniLibsOutputDir.deleteRecursively()
        }
    }

    val cargoArgs = mutableListOf("cargo", "ndk")
    selectedAbis.forEach { abi ->
        cargoArgs += listOf("-t", abi)
    }
    cargoArgs += listOf(
        "--platform", "26",
        "-o", jniLibsOutputDir.absolutePath,
        "build", "--release"
    )
    commandLine(cargoArgs)
}

android {
    namespace = "com.trazostudio.engine"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Incluye las 4 ABIs en modo universal o únicamente arm64-v8a en modo 64 bits.
            abiFilters += selectedAbis
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
