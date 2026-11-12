// Configuración raíz de módulos del proyecto Android TrazoStudio.
// Separa la interfaz (:app) del motor nativo Rust + JNI (:core:engine) para mantener una arquitectura modular.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TrazoStudio"
include(":app")
include(":core:engine")
