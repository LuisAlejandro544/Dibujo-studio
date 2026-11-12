# TrazoStudio (Android: Kotlin + Jetpack Compose + Rust)

Aplicación modular de dibujo para Android donde todo el procesamiento gráfico, rasterización de trazos, composición multicapa, relleno por inundación, historial Undo/Redo y codificación PNG ocurre nativamente en **Rust**, mientras que la interfaz y navegación se construyen en **Kotlin + Jetpack Compose + Material 3 + Material Icons Extended**.

## Estructura modular

- `rust/drawing_engine/` (`trazo_engine`): Crate Rust compilado como `cdylib` (`libtrazo_engine.so`) con recomposición parcial `dirty_rect`, lotes de segmentos y dependencias permisivas (`Apache-2.0` / `MIT` / `Zlib`): `jni`, `png`, `bytemuck`, `parking_lot`, `log`, `android_logger`.
- `core/engine/`: Módulo librería Android que ejecuta `cargo ndk` obligatoriamente en `preBuild` (para las 4 ABIs en modo `universal` o solo `arm64-v8a` en modo 64-bit vía `-PtrazoAbiMode`) y expone `RustCanvasSession` con `Dispatchers.Default.limitedParallelism(1)` y doble búfer de `Bitmap`.
- `app/`: Interfaz inmersiva a pantalla completa (sin barra de notificaciones ni botones del sistema, con margen superior seguro ante cámara perforada o isla dinámica) con 4 pantallas separadas y popups modales:
  - `GalleryScreen`: Plantillas de lienzo, obras guardadas y popup de lienzo personalizado.
  - `CanvasScreen`: Estudio de dibujo con botón para alternar entre **Mover** (pan, zoom y rotación libre del lienzo) y **Dibujar**, color inicial negro puro, herramienta de **Calco** desde galería (tamaño, opacidad, posición y visibilidad), zoom/pan de 2 dedos, pincel, marcador, aerógrafo, borrador, relleno, cuentagotas y popups de color/pincel/calco/capas.
  - `ExportScreen`: Vista previa del PNG generado por Rust, guardado en `Pictures/TrazoStudio` (`MediaStore`) y compartir vía `FileProvider`.
  - `SettingsScreen`: Temas visuales, tamaño por defecto, gestos e inspección de ABI nativa Rust.

## Versión mínima y arquitecturas

- `minSdk = 26` (Android 8.0 Oreo), `targetSdk = 35`.
- Soporta modo **`universal`** (32 bits `armeabi-v7a`, `x86` y 64 bits `arm64-v8a`, `x86_64`) y modo **`arm64-v8a`** (solo 64 bits).
- Incluye flujo manual en GitHub Actions (`.github/workflows/android-apk.yml`) para elegir la arquitectura y compilar el APK directamente en la nube sin PC.
