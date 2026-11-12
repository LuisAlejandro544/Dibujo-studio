# Roadmap de TrazoStudio

Plan de evolución por fases para el estudio de dibujo **Kotlin + Rust**.

## Fase 1 — Base funcional y MVP probables (Completada)
- [x] Motor nativo en Rust (`trazo_engine`) con punteros seguros `jlong` para 32 y 64 bits.
- [x] Rasterizador 2D con Pincel, Marcador, Aerógrafo y Borrador.
- [x] Relleno por inundación iterativo (*scanline flood fill*) y Cuentagotas.
- [x] Composición multicapa (hasta 12 capas), opacidad, visibilidad y combinar hacia abajo.
- [x] Pila Undo/Redo (hasta 25 pasos) y codificación PNG en Rust.
- [x] Interfaz Jetpack Compose + Material 3 + Material Icons con 4 pantallas dedicadas y popups.
- [x] Flujo manual en GitHub Actions para compilar el APK Debug sin PC y sin caché.

## Fase 2 — Precisión de trazo y herramientas artísticas
- [ ] Estabilizador de trazo en tiempo real en Rust (suavizado por promedio móvil / cuerda tensa).
- [ ] Soporte para sensibilidad de presión y ángulo de stylus (`MotionEvent.getPressure()`).
- [ ] Modos de simetría (espejo horizontal, vertical y radial) calculados en Rust.
- [ ] Reglas geométricas básicas (línea recta, círculo y rectángulo) en el rasterizador.

## Fase 3 — Persistencia avanzada y capas profesionales
- [ ] Guardado y reapertura de proyectos editables conservando todas las capas en formato binario propio (`.trazo`).
- [ ] Modos de fusión de capas en Rust (`Multiply`, `Screen`, `Overlay`, `Add`).
- [ ] Transformación de capa (mover, escalar, rotar) ejecutada en memoria nativa.

## Fase 4 — Optimización y publicación (Uptodown / APK directo)
- [ ] Renderizado parcial por regiones sucias (*dirty rects*) en `composited_buffer` para lienzos 2K/4K a 60+ FPS.
- [ ] Firma keystore para publicación de APK Release en Uptodown y canales de terceros.
- [ ] Pruebas instrumentadas de JNI en emuladores ARM64 y x86.
