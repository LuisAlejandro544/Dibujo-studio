# README_AI.md — Referencia rápida de entrada para IAs

Guía técnica condensada para entender el estado del repositorio en segundos sin leer archivos innecesarios.

## Puntos de entrada clave

| Área | Archivo principal | Responsabilidad |
|---|---|---|
| Web (Hello World) | `src/App.tsx` | Muestra únicamente `"Hello World"` en el entorno web |
| Motor Rust (Core) | `android/rust/drawing_engine/src/engine.rs` | Capas, Undo/Redo, composición RGBA y export PNG |
| Rasterizador Rust | `android/rust/drawing_engine/src/rasterizer.rs` | Pinceles SDF, marcador, aerógrafo, borrador y flood fill |
| Puente JNI (Rust) | `android/rust/drawing_engine/src/lib.rs` | Exporta `Java_com_trazostudio_engine_NativeEngineBridge_*` |
| Puente JNI (Kotlin) | `android/core/engine/src/main/java/com/trazostudio/engine/NativeEngineBridge.kt` | Declaraciones `external fun` cargando `libtrazo_engine.so` |
| Sesión Corrutinas | `android/core/engine/src/main/java/com/trazostudio/engine/RustCanvasSession.kt` | Serializa llamadas Rust en `Dispatchers.Default.limitedParallelism(1)` |
| Gradle Rust NDK | `android/core/engine/build.gradle.kts` | Tarea `buildRustNativeLibs` (`cargo ndk`) atada a `preBuild` |
| Navegación UI | `android/app/src/main/java/com/trazostudio/draw/ui/navigation/TrazoNavHost.kt` | Conecta `GalleryScreen`, `CanvasScreen`, `ExportScreen`, `SettingsScreen` |
| Estado del Lienzo | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/CanvasViewModel.kt` | Coordina gestos táctiles, herramientas y capas |
| Build APK CI | `.github/workflows/android-apk.yml` | Compilación manual sin caché de APK Debug (Rust + Kotlin) |

## Contrato JNI crítico (Kotlin ↔ Rust)
1. Al crear una sesión: `nativeCreateEngine(width, height)` devuelve `jlong` (puntero opaco a `Arc<EngineHandle>`).
2. Al dibujar: `nativeBeginStroke(ptr)` guarda instantánea Undo; `nativeApplyDab` / `nativeApplySegment` rasterizan en la capa activa.
3. Para renderizar en pantalla: Kotlin asigna un `ByteBuffer.allocateDirect(w * h * 4)` y llama `nativeRenderToDirectBuffer(ptr, buffer)`, luego `bitmap.copyPixelsFromBuffer(buffer)`.
4. Al cerrar: `RustCanvasSession.close()` invoca `nativeDestroyEngine(ptr)` para liberar memoria en Rust.

## Verificación rápida
- Web: `npx --yes tsx --test src/hello.test.ts && npm run lint && npm run build`
- Rust (en entorno con Cargo): `cd android/rust/drawing_engine && cargo test`
- APK Debug (en CI o entorno Android+NDK): `cd android && ./gradlew :app:assembleDebug --no-build-cache --no-daemon`
