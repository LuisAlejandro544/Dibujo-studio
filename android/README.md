# TrazoStudio (Android: Kotlin + Jetpack Compose + Rust)

Aplicación modular de dibujo para Android donde todo el procesamiento gráfico, rasterización de trazos, composición multicapa, relleno por inundación, historial Undo/Redo y codificación PNG ocurre nativamente en **Rust**, mientras que la interfaz y navegación se construyen en **Kotlin + Jetpack Compose + Material 3 + Material Icons Extended**.

## Estructura modular

- `rust/drawing_engine/` (`trazo_engine`): Crate Rust compilado como `cdylib` (`libtrazo_engine.so`) con recomposición parcial `dirty_rect`, lotes de segmentos, historial incremental por baldosas 64×64, formato `.trazo`, analizador local de calco (`tracing_ai.rs`) y dependencias permisivas (`Apache-2.0` / `MIT` / `Zlib`): `jni`, `png`, `bytemuck`, `parking_lot`, `log`, `android_logger`.
- `core/engine/`: Módulo librería Android que ejecuta `cargo ndk` obligatoriamente en `preBuild` (para las 4 ABIs en modo `universal` o solo `arm64-v8a` en modo 64-bit vía `-PtrazoAbiMode`) y expone `RustCanvasSession` con `Dispatchers.Default.limitedParallelism(1)`, doble búfer de `Bitmap` y `analyzeTracingBitmap`.
- `app/`: Interfaz inmersiva a pantalla completa (con recorte `ALWAYS` en Android 11+ y margen superior dinámico solo si hay cámara física) con navegación animada entre 4 pantallas, barra inferior raíz entre Galería/Ajustes y popups modales:
  - `GalleryScreen`: Plantillas de lienzo, miniaturas escaladas, obras guardadas `.trazo`/PNG y popup de lienzo personalizado.
  - `CanvasScreen`: Estudio de dibujo con dock de 2 niveles (accesos rápidos arriba y las 6 herramientas principales visibles sin scroll abajo), confirmación al salir con trazos sin guardar, botón **Mover/Dibujar**, **Calco** manipulable con dedos, **Asistente IA de Calco** (`TracingAiPopup`) y popups modales.
  - `ExportScreen`: Vista previa del PNG generado por Rust, guardado en `Pictures/TrazoStudio` (`MediaStore`) y compartir vía `FileProvider`.
  - `SettingsScreen`: Temas visuales, tamaño por defecto, gestos e inspección de ABI nativa Rust.

## Versión mínima y arquitecturas

- `minSdk = 26` (Android 8.0 Oreo), `targetSdk = 35`.
- Soporta modo **`universal`** (32 bits `armeabi-v7a`, `x86` y 64 bits `arm64-v8a`, `x86_64`) y modo **`arm64-v8a`** (solo 64 bits).
- Incluye flujo manual en GitHub Actions (`.github/workflows/android-apk.yml`) para elegir la arquitectura y compilar el APK directamente en la nube sin PC.
