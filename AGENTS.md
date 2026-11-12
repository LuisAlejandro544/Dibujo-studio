# AGENTS.md — Guía operativa para agentes de ingeniería

Reglas estrictas para cualquier agente o colaborador que modifique este repositorio.

## 1. Reglas de arquitectura y seguridad de código
- **Modularidad**: Mantén separados el motor Rust (`android/rust/drawing_engine`), el puente JNI/Coroutines (`android/core/engine`) y la interfaz Compose (`android/app`). No concentres lógica gráfica dentro de pantallas `@Composable`.
- **Cero fallbacks en Kotlin**: Si agregas un nuevo pincel, filtro o herramienta, impleméntalo en Rust (`rasterizer.rs` / `engine.rs`), expónlo por JNI en `lib.rs` y conéctalo en `NativeEngineBridge.kt` + `RustCanvasSession.kt`.
- **Seguridad 32-bit / 64-bit**: En Rust JNI no asumas `sizeof(usize) == 8`. Convierte siempre punteros usando `ptr as usize as jlong` y de vuelta `handle_ptr as usize as *const EngineHandle`.
- **No romper diseño ni lógica existente**: En cambios incrementales, edita quirúrgicamente solo lo necesario. No cambies iconos, colores o navegación sin que el usuario lo solicite.
- **Explicación en archivos**: Cada archivo nuevo o modificado debe explicar claramente su propósito y lógica mediante comentarios.

## 2. Reglas de dependencias y licencias
- Usa dependencias reales y funcionales (no soluciones improvisadas sin librerías cuando exista una librería adecuada).
- Verifica que toda nueva librería (Crate de Rust o artefacto Maven/Gradle) tenga licencia permisiva (`Apache-2.0`, `MIT`, `Zlib`, `Unlicense` o `CC0`). Rechaza `GPL`, `AGPL` o licencias que obliguen a publicar el código o incluir atribuciones visibles.

## 3. Reglas de compilación y CI
- El entorno web (`src/`) debe compilar siempre con `npm run build` y `npm run lint` (`tsc --noEmit`).
- El flujo `.github/workflows/android-apk.yml`:
  - Debe activarse **solo manualmente** (`workflow_dispatch`).
  - Debe correr **sin caché**.
  - Debe usar `sdkmanager` directamente (sin `android-actions/setup-android@v3` por su fallo con el paquete legado `tools`).
  - Debe generar el APK Debug tras compilar los 4 targets de Rust y Kotlin.

## 4. Archivo `commit_message.txt`
- Si llegara a existir un archivo `commit_message.txt`, su contenido debe estar en **español** y no debe alterarse salvo petición explícita del usuario.
