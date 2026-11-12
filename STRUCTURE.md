# Estructura del Proyecto (`STRUCTURE.md`)

Mapa detallado de carpetas y archivos del repositorio.

```text
/
├── .github/
│   └── workflows/
│       └── android-apk.yml          # GitHub Action manual (sin caché) para compilar APK Debug
├── README.md                        # Documentación general del proyecto
├── ROADMAP.md                       # Hoja de ruta técnica por fases
├── STRUCTURE.md                     # Este documento de arquitectura de archivos
├── AI_CONTEXT.md                    # Contexto persistente para sesiones con IA
├── AGENTS.md                        # Reglas y convenciones para agentes de desarrollo
├── README_AI.md                     # Guía rápida de entrada para IAs
│
├── index.html                       # Entrada HTML del shell web Hello World
├── metadata.json                    # Metadatos del entorno AI Studio
├── package.json                     # Dependencias web (Vite + React + Tailwind + tsx)
├── src/
│   ├── main.tsx                     # Montaje React del shell web
│   ├── App.tsx                      # Pantalla única "Hello World"
│   ├── hello.ts                     # Lógica desacoplada del saludo web
│   ├── hello.test.ts                # Prueba unitaria (node:test)
│   └── index.css                    # Estilos globales Tailwind CSS
│
└── android/                         # Proyecto móvil completo (Kotlin + Rust)
    ├── README.md                    # Guía técnica del módulo Android
    ├── build.gradle.kts             # Configuración de plugins raíz
    ├── settings.gradle.kts          # Declaración modular (:app y :core:engine)
    ├── gradle.properties            # Memoria JVM y banderas AndroidX/Kotlin
    ├── gradlew                      # Script ejecutable Gradle Wrapper
    ├── gradle/
    │   ├── libs.versions.toml       # Catálogo de dependencias (Apache-2.0)
    │   └── wrapper/
    │       └── gradle-wrapper.properties
    │
    ├── rust/
    │   └── drawing_engine/          # Crate nativo Rust (`trazo_engine`)
    │       ├── Cargo.toml           # Dependencias permisivas (jni, png, bytemuck, parking_lot)
    │       └── src/
    │           ├── lib.rs           # Puente JNI seguro para 32-bit y 64-bit
    │           ├── engine.rs        # Estado del lienzo, capas, Undo/Redo y export PNG
    │           ├── rasterizer.rs    # Pinceles SDF, interpolación, borrador y flood fill
    │           └── types.rs         # Tipos base (BrushConfig, Layer, RgbaColor, Snapshot)
    │
    ├── core/
    │   └── engine/                  # Módulo librería Kotlin (:core:engine)
    │       ├── build.gradle.kts     # Compila Rust con `cargo ndk` en `preBuild` (4 ABIs)
    │       ├── consumer-rules.pro   # Protege firmas JNI en R8/ProGuard
    │       └── src/main/
    │           ├── AndroidManifest.xml
    │           └── java/com/trazostudio/engine/
    │               ├── EngineModels.kt       # Enums y modelos de herramientas/capas
    │               ├── NativeEngineBridge.kt # Declaraciones `external fun` hacia Rust
    │               └── RustCanvasSession.kt  # Envoltorio seguro con Corrutinas y ByteBuffer
    │
    └── app/                         # Aplicación Jetpack Compose (:app)
        ├── build.gradle.kts         # minSdk=26, targetSdk=35, Material Icons, Navigation
        ├── proguard-rules.pro
        └── src/main/
            ├── AndroidManifest.xml  # Permisos MediaStore y FileProvider
            ├── res/
            │   ├── values/strings.xml
            │   ├── values/themes.xml
            │   └── xml/file_paths.xml
            └── java/com/trazostudio/draw/
                ├── MainActivity.kt
                ├── data/
                │   ├── ProjectStorage.kt              # Almacenamiento IO, MediaStore y Share
                │   └── StudioPreferencesRepository.kt # Preferencias con DataStore
                └── ui/
                    ├── theme/Theme.kt                 # 3 paletas Material 3 del estudio
                    ├── navigation/TrazoNavHost.kt     # Rutas entre las 4 pantallas
                    ├── gallery/GalleryScreen.kt       # Inicio, plantillas y obras guardadas
                    ├── export/ExportScreen.kt         # Vista previa y exportación PNG
                    ├── settings/SettingsScreen.kt     # Ajustes y diagnóstico ABI Rust
                    └── canvas/
                        ├── CanvasViewModel.kt         # Estado reactivo y corrutinas del lienzo
                        ├── CanvasScreen.kt            # Estudio interactivo y gestos multitáctiles
                        └── popups/
                            ├── BrushSettingsPopup.kt  # Popup tamaño, opacidad y dureza
                            ├── ColorPickerPopup.kt    # Popup selector HSV y muestras
                            └── LayersPopup.kt         # Popup gestor de capas
```
