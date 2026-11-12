# AI Context (`AI_CONTEXT.md`)

Documento de contexto técnico para mantener coherencia arquitectónica y de producto entre sesiones.

## Perfil del proyecto y del usuario
- **Usuario**: Desarrolla y prueba desde un **teléfono móvil** (sin PC local). La compilación del APK se realiza en la nube mediante GitHub Actions.
- **Distribución objetivo**: APK directo / **Uptodown** y tiendas de terceros (no Google Play).
- **Entorno Web (`src/`)**: Debe mantenerse estrictamente como una pantalla **"Hello World"** limpia y compilable en Vite, salvo que el usuario pida cambiarlo.
- **Entorno Móvil (`android/`)**: Aplicación real de dibujo llamada **TrazoStudio** (`com.trazostudio.draw`). Sin nombres de marcas protegidas por derechos de autor.

## Decisiones arquitectónicas obligatorias
1. **Motor en Rust obligatorio (sin fallbacks en Kotlin)**:
   - Toda la lógica gráfica (trazos, antialias, borrador, flood fill, cuentagotas, composición de capas, Undo/Redo y codificación PNG) vive en `android/rust/drawing_engine`.
   - `android/core/engine/build.gradle.kts` enlaza la tarea `buildRustNativeLibs` (`cargo ndk`) a `preBuild`. Nunca se debe saltar la compilación nativa ni sustituirla por funciones de `android.graphics.Canvas` en Kotlin.
2. **Soporte 32 bits y 64 bits**:
   - ABIs obligatorias: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
   - Los punteros nativos entre Rust y Kotlin usan `jlong` (64 bits siempre en JNI) casteado vía `usize` para compatibilidad segura en arquitecturas de 32 y 64 bits.
3. **Versión mínima de Android (`minSdk = 26`)**:
   - Elegida para garantizar estabilidad con NDK r26+, almacenamiento moderno y buen soporte gráfico cubriendo >96% de dispositivos.
4. **Concurrencia con Corrutinas**:
   - `RustCanvasSession` serializa el acceso al motor nativo mediante `Dispatchers.Default.limitedParallelism(1)` y usa `Dispatchers.IO` para disco/MediaStore. Nunca bloquear el hilo principal.
5. **Licencias limpias**:
   - Usar únicamente dependencias `Apache-2.0`, `MIT` o `Zlib`. Prohibido introducir librerías GPL/AGPL o que exijan abrir el código o mostrar créditos obligatorios en la UI.
6. **Diseño UI**:
   - Evitar el minimalismo extremo.
   - Separar funcionalidades en **pantallas dedicadas** (`GalleryScreen`, `CanvasScreen`, `ExportScreen`, `SettingsScreen`) y usar **popups / hojas modales** (`BrushSettingsPopup`, `ColorPickerPopup`, `LayersPopup`, `NewCanvasPopupDialog`) para paneles secundarios.
   - Conservar diseño, iconos y lógica existente salvo petición expresa.
7. **Comentarios en el código**:
   - Todos los archivos de código deben incluir comentarios claros (en español) explicando qué hace su lógica.
8. **GitHub Actions**:
   - El workflow `.github/workflows/android-apk.yml` compila el **APK Debug** exclusivamente de forma manual (`workflow_dispatch`) y **sin caché**.
