# README_AI.md — Referencia rápida de entrada para IAs

Guía técnica condensada para entender el estado del repositorio en segundos sin leer archivos innecesarios.

## Puntos de entrada clave

| Área | Archivo principal | Responsabilidad |
|---|---|---|
| Web (Hello World) | `src/App.tsx` | Muestra únicamente `"Hello World"` en el entorno web |
| Motor Rust (Core) | `android/rust/drawing_engine/src/engine.rs` | Capas, `dirty_rect`, lotes, Undo/Redo por baldosas 64×64, `.trazo` multicapa, `encode_png` y `load_png` |
| Rasterizador Rust | `android/rust/drawing_engine/src/rasterizer.rs` | Pinceles SDF, marcador, aerógrafo, borrador y flood fill |
| Asistente IA Rust | `android/rust/drawing_engine/src/tracing_ai.rs` | Medición de grosor de líneas escalado al lienzo y paleta K-Means++ para colorear calco |
| Puente JNI (Rust) | `android/rust/drawing_engine/src/lib.rs` | Exporta `Java_com_trazostudio_engine_NativeEngineBridge_*` (incl. `nativePrepareRender`, `nativeCopyRegionArgb`, `nativeExportProject`, `nativeLoadProject`, `nativeAnalyzeTracingImage`) |
| Puente JNI (Kotlin) | `android/core/engine/src/main/java/com/trazostudio/engine/NativeEngineBridge.kt` | Declaraciones `external fun` cargando `libtrazo_engine.so` |
| Sesión Corrutinas | `android/core/engine/src/main/java/com/trazostudio/engine/RustCanvasSession.kt` | Serializa llamadas Rust en `limitedParallelism(1)`, actualiza subregión sucia y ejecuta `analyzeTracingBitmap` |
| Actividad / Inmersivo | `android/app/src/main/java/com/trazostudio/draw/MainActivity.kt` | Oculta barra de notificaciones y barra de navegación del sistema |
| Tema / Insets seguros | `android/app/src/main/java/com/trazostudio/draw/ui/theme/Theme.kt` | Paletas Material 3, `studioTopBarWindowInsets()` e inmersivo en hojas modales |
| Navegación UI | `android/app/src/main/java/com/trazostudio/draw/ui/navigation/TrazoNavHost.kt` | Conecta `GalleryScreen`, `CanvasScreen`, `ExportScreen`, `SettingsScreen` (incl. reapertura de obras) |
| Estado del Lienzo | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/CanvasViewModel.kt` | Cola por lotes, carga/guardado `.trazo`, modo Mover/Dibujar, ajuste táctil y análisis IA de calco |
| Estudio de Dibujo | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/CanvasScreen.kt` | Lienzo, gestos pan/zoom/rotación, ajuste táctil de calco con `TracingAdjustDock`, acceso rápido IA y dock |
| Popup de Calco | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/popups/TracingReferencePopup.kt` | Selector de galería, botón a gestos táctiles, acceso a Asistente IA, posición, tamaño y opacidad |
| Popup IA de Calco | `android/app/src/main/java/com/trazostudio/draw/ui/canvas/popups/TracingAiPopup.kt` | Muestra grosores recomendados (ojos/detalles, contorno, grueso) y paleta para pintar/rellenar |
| Build APK CI | `.github/workflows/android-apk.yml` | Compilación manual sin caché eligiendo `universal` o `arm64-v8a` |

## Contrato JNI crítico (Kotlin ↔ Rust)
1. Al crear una sesión: `nativeCreateEngine(width, height)` devuelve `jlong` (puntero opaco a `Arc<EngineHandle>`).
2. Al abrir una obra guardada: `CanvasViewModel.openSavedDrawing` prioriza el archivo multicapa `.trazo` (`nativeLoadProject`) y cae a `nativeLoadPng` en obras antiguas solo-PNG.
3. Al dibujar: `CanvasViewModel` agrupa eventos táctiles en `drawStrokeBatch(...)`, invocando `nativeBeginStroke`, `nativeApplyDab`, `nativeApplySegmentsBatch` y `nativeEndStroke` al soltar, respaldando solo baldosas `64×64` modificadas.
4. Para renderizar en pantalla: `RustCanvasSession` llama `nativePrepareRender(ptr, rect)` (recompone `dirty_rect`), acumula por ranura de doble búfer y transfiere únicamente la subregión cambiada vía `nativeCopyRegionArgb` + `Bitmap.setPixels`.
5. Para analizar calco con IA local: `RustCanvasSession.analyzeTracingBitmap` escala muestras hasta 640 px máx. e invoca `nativeAnalyzeTracingImage` pasando el ancho real del calco sobre el lienzo para devolver `META|...` y `COLOR|...`.
6. Al cerrar: `RustCanvasSession.close()` invoca `nativeDestroyEngine(ptr)` para liberar memoria en Rust.

## Verificación rápida
- Web: `npx --yes tsx --test src/hello.test.ts && npm run lint && npm run build`
- Rust (en entorno con Cargo): `cd android/rust/drawing_engine && cargo test`
- APK Debug Universal: `cd android && ./gradlew :app:assembleDebug -PtrazoAbiMode=universal --no-build-cache --no-daemon`
- APK Debug Solo 64-bit: `cd android && ./gradlew :app:assembleDebug -PtrazoAbiMode=arm64-v8a --no-build-cache --no-daemon`
