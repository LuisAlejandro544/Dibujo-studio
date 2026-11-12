# README_AI.md — Referencia rápida de entrada para IAs

Guía técnica condensada para entender el estado del repositorio en segundos sin leer archivos innecesarios.

## Puntos de entrada clave

| Área | Archivo principal | Responsabilidad |
|---|---|---|
| Web (Hello World) | `src/App.tsx` | Muestra únicamente `"Hello World"` en el entorno web |
| Motor Rust (Core) | `android/rust/drawing_engine/src/engine.rs` | Capas, `dirty_rect`, lotes, Undo/Redo, composición RGBA, `encode_png` y `load_png` |
| Rasterizador Rust | `android/rust/drawing_engine/src/rasterizer.rs` | Pinceles SDF, marcador, aerógrafo, borrador y flood fill |
| Puente JNI (Rust) | `android/rust/drawing_engine/src/lib.rs` | Exporta `Java_com_trazostudio_engine_NativeEngineBridge_*` (incl. `nativeApplySegmentsBatch` y `nativeLoadPng`) |
| Puente JNI (Kotlin) | `android/core/engine/src/main/java/com/trazostudio/engine/NativeEngineBridge.kt` | Declaraciones `external fun` cargando `libtrazo_engine.so` |
| Sesión Corrutinas | `android/core/engine/src/main/java/com/trazostudio/engine/RustCanvasSession.kt` | Serializa llamadas Rust en `limitedParallelism(1)` y usa doble búfer de `Bitmap` |
| Gradle Rust NDK | `android/core/engine/build.gradle.kts` | Tarea `buildRustNativeLibs` (`cargo ndk`) atada a `preBuild` (`universal` o `arm64-v8a`) |
| Actividad / Inmersivo | `android/app/src/main/java/com/trazostudio/draw/MainActivity.kt` | Oculta barra de notificaciones y barra de navegación del sistema |
| Tema / Insets seguros | `android/app/src/main/java/com/trazostudio/draw/ui/theme/Theme.kt` | Paletas Material 3, `studioTopBarWindowInsets()` e inmersivo en hojas modales |
| Navegación UI | `android/app/src/main/java/com/trazostudio/draw/ui/navigation/TrazoNavHost.kt` | Conecta `GalleryScreen`, `CanvasScreen`, `ExportScreen`, `SettingsScreen` (incl. reapertura de obras) |
| Estado del Lienzo | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/CanvasViewModel.kt` | Cola por lotes, carga de obras guardadas, modo Mover/Dibujar, calco y capas |
| Estudio de Dibujo | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/CanvasScreen.kt` | Lienzo, gestos pan/zoom/rotación en modo Mover, guía 1:1 de pincel, overlay de calco y dock |
| Popup de Calco | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/popups/TracingReferencePopup.kt` | Selector de galería, tamaño, opacidad, posición y mostrar/ocultar calco |
| Build APK CI | `.github/workflows/android-apk.yml` | Compilación manual sin caché eligiendo `universal` o `arm64-v8a` |

## Contrato JNI crítico (Kotlin ↔ Rust)
1. Al crear una sesión: `nativeCreateEngine(width, height)` devuelve `jlong` (puntero opaco a `Arc<EngineHandle>`).
2. Al abrir una obra guardada: `CanvasViewModel.openSavedDrawing` lee el PNG en `Dispatchers.IO` y llama a `nativeLoadPng(ptr, pngBytes)` para restaurar la imagen en `Capa 1` dentro de Rust.
3. Al dibujar: `CanvasViewModel` agrupa eventos táctiles en `drawStrokeBatch(...)`, invocando `nativeBeginStroke`, `nativeApplyDab` y `nativeApplySegmentsBatch`, marcando solo la región sucia (`dirty_rect`).
4. Para renderizar en pantalla: `RustCanvasSession` reutiliza un doble búfer de `Bitmap`, llama `nativeRenderToDirectBuffer(ptr, buffer)` (que recompone únicamente `dirty_rect` en Rust) y copia a `Bitmap` sin reasignar memoria por cuadro.
5. Al cerrar: `RustCanvasSession.close()` invoca `nativeDestroyEngine(ptr)` para liberar memoria en Rust.

## Verificación rápida
- Web: `npx --yes tsx --test src/hello.test.ts && npm run lint && npm run build`
- Rust (en entorno con Cargo): `cd android/rust/drawing_engine && cargo test`
- APK Debug Universal: `cd android && ./gradlew :app:assembleDebug -PtrazoAbiMode=universal --no-build-cache --no-daemon`
- APK Debug Solo 64-bit: `cd android && ./gradlew :app:assembleDebug -PtrazoAbiMode=arm64-v8a --no-build-cache --no-daemon`
