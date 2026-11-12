# TrazoStudio

**TrazoStudio** es una aplicación móvil de dibujo digital para Android construida con **Kotlin + Jetpack Compose** en la interfaz y un motor gráfico nativo de alto rendimiento en **Rust** (`libtrazo_engine.so`), acompañada de un cascarón web mínimo (`Hello World`) para validación en entornos web.

## Características principales (App Android)

- **Motor nativo en Rust (`trazo_engine`)**:
  - Rasterización 2D con antialias SDF radial e interpolación suave de segmentos.
  - Herramientas reales: **Pincel**, **Marcador**, **Aerógrafo**, **Borrador** (resta de alfa), **Relleno por inundación iterativo** (*scanline flood fill*) y **Cuentagotas**.
  - Sistema multicapa (visibilidad, opacidad por capa, combinar hacia abajo, añadir y eliminar capas).
  - Historial **Undo / Redo** con instantáneas por capa.
  - Exportación real a **PNG** en memoria mediante el crate `png`.
- **Interfaz modular en Kotlin + Jetpack Compose + Material 3**:
  - **Galería / Inicio**: Plantillas rápidas, historial de obras locales y popup para lienzos personalizados.
  - **Estudio de Dibujo**: Lienzo interactivo con zoom/panorámica de 2 dedos, barra superior de acciones, barra inferior de herramientas con **Material Icons Extended** y popups modales de color HSV, ajustes de pincel y capas.
  - **Exportar Obra**: Vista previa PNG, modo transparente opcional, guardado en `Pictures/TrazoStudio` (`MediaStore`) y compartir vía `FileProvider`.
  - **Ajustes del Estudio**: 3 paletas visuales, resolución predeterminada, gestos multitáctiles y diagnóstico de arquitectura Rust.
- **Soporte completo de arquitecturas**:
  - Compila nativamente para **32 bits** (`armeabi-v7a`, `x86`) y **64 bits** (`arm64-v8a`, `x86_64`).
  - `minSdk = 26` (Android 8.0 Oreo), `targetSdk = 35`.

## Cómo compilar el APK Debug sin PC

1. Sube este repositorio a GitHub.
2. Abre la pestaña **Actions** en GitHub desde tu navegador móvil.
3. Selecciona **"Compilar APK Debug (Manual)"** y pulsa **"Run workflow"**.
4. Al finalizar, descarga el artefacto **`TrazoStudio-Debug-APK`**.

## Entorno Web

El entorno web (`src/App.tsx`) muestra intencionalmente únicamente **"Hello World"** para permitir compilación y pruebas rápidas mientras el desarrollo principal reside en `android/`.

```bash
npm install
npm run dev
```

## Licencias

Todas las dependencias de Rust y Android emplean licencias permisivas (`Apache-2.0`, `MIT`, `Zlib`), sin restricciones copyleft.
