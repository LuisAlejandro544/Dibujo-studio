# AI Context (`AI_CONTEXT.md`)

Documento de contexto técnico para mantener coherencia arquitectónica y de producto entre sesiones.

## Perfil del proyecto y del usuario
- **Usuario**: Desarrolla y prueba desde un **teléfono móvil** (sin PC local). La compilación del APK se realiza en la nube mediante GitHub Actions.
- **Distribución objetivo**: APK directo / **Uptodown** y tiendas de terceros (no Google Play).
- **Entorno Web (`src/`)**: Debe mantenerse estrictamente como una pantalla **"Hello World"** limpia y compilable en Vite, salvo que el usuario pida cambiarlo.
- **Entorno Móvil (`android/`)**: Aplicación real de dibujo llamada **TrazoStudio** (`com.trazostudio.draw`). Sin nombres de marcas protegidas por derechos de autor.

## Decisiones arquitectónicas obligatorias
1. **Motor en Rust obligatorio (sin fallbacks en Kotlin)**:
   - Toda la lógica gráfica y de visión por computador (trazos, antialias, borrador, flood fill, cuentagotas, composición de capas, Undo/Redo, codificación PNG y análisis IA local del calco en `tracing_ai.rs`) vive en `android/rust/drawing_engine`.
   - `android/core/engine/build.gradle.kts` enlaza la tarea `buildRustNativeLibs` (`cargo ndk`) a `preBuild`. Nunca se debe saltar la compilación nativa ni sustituirla por funciones de `android.graphics.Canvas` en Kotlin.
2. **Soporte 32 bits y 64 bits configurable (`-PtrazoAbiMode`)**:
   - Modo `universal` (predeterminado): `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
   - Modo `arm64-v8a`: compila únicamente 64 bits ARM para pruebas rápidas y APK más ligero.
   - Los punteros nativos entre Rust y Kotlin usan `jlong` (64 bits siempre en JNI) casteado vía `usize` para compatibilidad segura en arquitecturas de 32 y 64 bits.
3. **Versión mínima de Android (`minSdk = 26`)**:
   - Elegida para garantizar estabilidad con NDK r26+, almacenamiento moderno y buen soporte gráfico cubriendo >96% de dispositivos.
4. **Concurrencia y rendimiento de trazo**:
   - `RustCanvasSession` serializa el acceso al motor nativo mediante `Dispatchers.Default.limitedParallelism(1)`, reutiliza un doble búfer de `Bitmap` y usa `Dispatchers.IO` para disco/MediaStore. Nunca bloquear el hilo principal.
   - `CanvasViewModel` agrupa segmentos táctiles por lotes (`drawStrokeBatch`) y Rust recompone únicamente `dirty_rect` para evitar latencia al dibujar.
5. **Licencias limpias**:
   - Usar únicamente dependencias `Apache-2.0`, `MIT` o `Zlib`. Prohibido introducir librerías GPL/AGPL o que exijan abrir el código o mostrar créditos obligatorios en la UI.
6. **Diseño UI y modo inmersivo**:
   - Evitar el minimalismo extremo.
   - Modo pantalla completa inmersivo en `MainActivity` (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` en Android 11+) ocultando barra de notificaciones y barra de navegación del sistema, aplicando `studioTopBarWindowInsets()` solo cuando existe `displayCutout > 0` para evitar franjas vacías arriba.
   - Separar funcionalidades en **pantallas dedicadas** (`GalleryScreen`, `CanvasScreen`, `ExportScreen`, `SettingsScreen`) con barra inferior raíz (`Galería` / `Ajustes`), transiciones animadas y **popups / hojas modales** (`BrushSettingsPopup`, `ColorPickerPopup`, `LayersPopup`, `TracingReferencePopup`, `TracingAiPopup`, `NewCanvasPopupDialog`).
   - En `CanvasScreen` incluir dock de 2 niveles (controles rápidos arriba + las 6 herramientas visibles sin scroll abajo), confirmación al salir con trazos sin guardar, botón **Mover/Dibujar**, color inicial **negro puro** (`0xFF000000`), **Calco** manipulable con dedos y **Asistente IA de Calco** 100% offline en Rust.
   - Conservar diseño, iconos y lógica existente salvo petición expresa.
7. **Comentarios en el código**:
   - Todos los archivos de código deben incluir comentarios claros (en español) explicando qué hace su lógica.
8. **GitHub Actions**:
   - El workflow `.github/workflows/android-apk.yml` compila el **APK Debug** exclusivamente de forma manual (`workflow_dispatch`) y **sin caché**, permitiendo elegir entre `universal` (32/64-bit) o `arm64-v8a` (solo 64-bit).
