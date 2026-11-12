# TrazoStudio

**TrazoStudio** es una aplicación móvil de dibujo digital para Android construida con **Kotlin + Jetpack Compose** en la interfaz y un motor gráfico nativo de alto rendimiento en **Rust** (`libtrazo_engine.so`), acompañada de un cascarón web mínimo (`Hello World`) para validación en entornos web.

## Características principales (App Android)

  - **Motor nativo en Rust (`trazo_engine`)**:
  - Rasterización 2D con antialias SDF radial, interpolación suave y procesamiento por lotes (`apply_segments_batch`).
  - Recomposición parcial por región sucia (`dirty_rect`) y doble búfer de `Bitmap` reutilizable en Kotlin para trazos instantáneos.
  - Herramientas reales: **Pincel**, **Marcador**, **Aerógrafo**, **Borrador** (resta de alfa), **Relleno por inundación iterativo** (*scanline flood fill*) y **Cuentagotas**.
  - Sistema multicapa (visibilidad, opacidad por capa, combinar hacia abajo, añadir y eliminar capas).
  - Historial **Undo / Redo** con instantáneas por capa.
  - Exportación real a **PNG** en memoria mediante el crate `png`.
- **Interfaz modular en Kotlin + Jetpack Compose + Material 3**:
  - **Modo pantalla completa inmersivo**: oculta la barra de estado/notificaciones y la barra de navegación del sistema.
  - **Galería / Inicio**: Plantillas rápidas, historial de obras locales y popup para lienzos personalizados.
  - **Estudio de Dibujo**: Lienzo interactivo con botón para alternar entre **Mover** (arrastre/zoom libre) y **Dibujar**, zoom/panorámica de 2 dedos, barra superior compacta, barra inferior de herramientas con **Material Icons Extended** y popups modales de color HSV, ajustes de pincel y capas.
  - **Exportar Obra**: Vista previa PNG, modo transparente opcional, guardado en `Pictures/TrazoStudio` (`MediaStore`) y compartir vía `FileProvider`.
  - **Ajustes del Estudio**: 3 paletas visuales, resolución predeterminada, gestos multitáctiles y diagnóstico de arquitectura Rust.
- **Soporte configurable de arquitecturas**:
  - Modo **`universal`** (predeterminado): compila nativamente para **32 bits** (`armeabi-v7a`, `x86`) y **64 bits** (`arm64-v8a`, `x86_64`).
  - Modo **`arm64-v8a`**: compila únicamente la arquitectura móvil de **64 bits** para un APK más ligero y compilación más rápida.
  - `minSdk = 26` (Android 8.0 Oreo), `targetSdk = 35`.

## Cómo compilar el APK Debug sin PC

1. Sube este repositorio a GitHub.
2. Abre la pestaña **Actions** en GitHub desde tu navegador móvil.
3. Selecciona **"Compilar APK Debug (Manual)"** y pulsa **"Run workflow"**.
4. Elige el modo de arquitectura (`universal` para 32/64-bit o `arm64-v8a` solo 64-bit).
5. Al finalizar, descarga el artefacto **`TrazoStudio-Debug-APK-universal`** o **`TrazoStudio-Debug-APK-arm64-v8a`**.

## Entorno Web

El entorno web (`src/App.tsx`) muestra intencionalmente únicamente **"Hello World"** para permitir compilación y pruebas rápidas mientras el desarrollo principal reside en `android/`.

```bash
npm install
npm run dev
```

## Licencias

Todas las dependencias de Rust y Android emplean licencias permisivas (`Apache-2.0`, `MIT`, `Zlib`), sin restricciones copyleft.
