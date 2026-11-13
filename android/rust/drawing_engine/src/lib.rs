//! Puente JNI entre el motor de dibujo en Rust (`DrawingEngine`) y Kotlin (`NativeEngineBridge`).
//! Seguridad de arquitecturas (32-bit y 64-bit):
//! - Cada instancia vive en un `Mutex<DrawingEngine>` asignado en el heap de Rust y expuesto como `jlong`.
//! - `jlong` tiene siempre 64 bits en JNI, por lo que convertir punteros `*mut EngineHandle` funciona
//!   de forma segura tanto en ARMv7/x86 (32 bits) como en ARM64/x86_64 (64 bits).
//! - La transferencia incremental de píxeles a Kotlin copia únicamente la subregión modificada
//!   (`nativePrepareRender` + `nativeCopyRegionArgb`), evitando mover todo el búfer en cada cuadro.

mod engine;
mod rasterizer;
mod tracing_ai;
mod types;

use std::sync::Arc;

use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JIntArray, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use parking_lot::Mutex;

use crate::engine::DrawingEngine;
use crate::types::{BrushConfig, BrushKind, RgbaColor};

struct EngineHandle {
    inner: Mutex<DrawingEngine>,
}

fn init_android_logging() {
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("TrazoRustEngine"),
        );
    });
}

/// Convierte el puntero opaco `jlong` en referencia segura mientras exista la sesión.
unsafe fn with_engine<R>(handle_ptr: jlong, f: impl FnOnce(&mut DrawingEngine) -> R) -> Option<R> {
    if handle_ptr == 0 {
        return None;
    }
    let handle = &*(handle_ptr as usize as *const EngineHandle);
    let mut guard = handle.inner.lock();
    Some(f(&mut guard))
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeCreateEngine(
    _env: JNIEnv,
    _class: JClass,
    width: jint,
    height: jint,
) -> jlong {
    init_android_logging();
    let engine = DrawingEngine::new(width.max(64) as u32, height.max(64) as u32);
    let boxed = Arc::new(EngineHandle {
        inner: Mutex::new(engine),
    });
    Arc::into_raw(boxed) as usize as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeDestroyEngine(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    if handle_ptr != 0 {
        unsafe {
            let _ = Arc::from_raw(handle_ptr as usize as *const EngineHandle);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeBeginStroke(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.begin_stroke_transaction();
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeEndStroke(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.end_stroke_transaction();
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeApplyDab(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    x: jfloat,
    y: jfloat,
    brush_kind: jint,
    argb_color: jint,
    radius: jfloat,
    opacity: jfloat,
    hardness: jfloat,
) {
    let config = BrushConfig {
        kind: BrushKind::from_i32(brush_kind),
        color: RgbaColor::from_argb_int(argb_color),
        radius,
        opacity,
        hardness,
    };
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.apply_dab(x, y, config);
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeApplySegment(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    x0: jfloat,
    y0: jfloat,
    x1: jfloat,
    y1: jfloat,
    brush_kind: jint,
    argb_color: jint,
    radius: jfloat,
    opacity: jfloat,
    hardness: jfloat,
) {
    let config = BrushConfig {
        kind: BrushKind::from_i32(brush_kind),
        color: RgbaColor::from_argb_int(argb_color),
        radius,
        opacity,
        hardness,
    };
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.apply_segment(x0, y0, x1, y1, config);
        });
    }
}

/// Rasteriza un lote de segmentos `[x0, y0, x1, y1, ...]` en una sola llamada JNI.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeApplySegmentsBatch(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    coords: JFloatArray,
    float_count: jint,
    brush_kind: jint,
    argb_color: jint,
    radius: jfloat,
    opacity: jfloat,
    hardness: jfloat,
) {
    let Ok(arr_len) = env.get_array_length(&coords) else {
        return;
    };
    let count = (float_count.max(0) as usize).min(arr_len.max(0) as usize);
    if count < 4 {
        return;
    }

    let mut buf = vec![0.0_f32; count];
    if env.get_float_array_region(&coords, 0, &mut buf).is_err() {
        return;
    }

    let config = BrushConfig {
        kind: BrushKind::from_i32(brush_kind),
        color: RgbaColor::from_argb_int(argb_color),
        radius,
        opacity,
        hardness,
    };
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.apply_segments_batch(&buf, config);
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeFloodFill(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    x: jint,
    y: jint,
    argb_color: jint,
    tolerance: jint,
) -> jboolean {
    let color = RgbaColor::from_argb_int(argb_color);
    let tol = tolerance.clamp(0, 128) as u8;
    let changed = unsafe {
        with_engine(handle_ptr, |engine| engine.apply_flood_fill(x, y, color, tol)).unwrap_or(false)
    };
    if changed { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativePickColor(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    x: jint,
    y: jint,
) -> jint {
    unsafe {
        with_engine(handle_ptr, |engine| engine.pick_color(x, y).to_argb_int())
            .unwrap_or(0xFF000000_u32 as i32)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeClearActiveLayer(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) {
    unsafe {
        with_engine(handle_ptr, |engine| engine.clear_active_layer());
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeUndo(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) -> jboolean {
    let ok = unsafe { with_engine(handle_ptr, |engine| engine.undo()).unwrap_or(false) };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeRedo(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
) -> jboolean {
    let ok = unsafe { with_engine(handle_ptr, |engine| engine.redo()).unwrap_or(false) };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeAddLayer(
    mut env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    name: JString,
) -> jint {
    let custom_name = env
        .get_string(&name)
        .ok()
        .map(|s| s.to_string_lossy().into_owned())
        .filter(|s| !s.trim().is_empty());

    unsafe {
        with_engine(handle_ptr, |engine| engine.add_layer(custom_name) as jint).unwrap_or(0)
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeDeleteLayer(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    index: jint,
) -> jboolean {
    let ok = unsafe {
        with_engine(handle_ptr, |engine| engine.delete_layer(index.max(0) as usize))
            .unwrap_or(false)
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeSetLayerVisibility(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    index: jint,
    visible: jboolean,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.set_layer_visibility(index.max(0) as usize, visible == JNI_TRUE);
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeSetLayerOpacity(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    index: jint,
    opacity: jfloat,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.set_layer_opacity(index.max(0) as usize, opacity);
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeSetActiveLayer(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    index: jint,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.set_active_layer(index.max(0) as usize);
        });
    }
}

#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeMergeLayerDown(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    index: jint,
) -> jboolean {
    let ok = unsafe {
        with_engine(handle_ptr, |engine| engine.merge_layer_down(index.max(0) as usize))
            .unwrap_or(false)
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Activa o desactiva la omisión de la capa base blanca al renderizar en pantalla
/// para que el calco se dibuje detrás de los trazos sin velarlos.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeSetExcludeBaseLayer(
    _env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    exclude: jboolean,
) {
    unsafe {
        with_engine(handle_ptr, |engine| {
            engine.set_exclude_base_layer(exclude == JNI_TRUE);
        });
    }
}

/// Recompone únicamente la región sucia pendiente y escribe `[x, y, w, h]` en `out_rect`.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativePrepareRender(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    out_rect: JIntArray,
) -> jboolean {
    let rect_opt = unsafe { with_engine(handle_ptr, |engine| engine.prepare_render_rect()) }.flatten();
    if let Some((x, y, w, h)) = rect_opt {
        let vals = [x as i32, y as i32, w as i32, h as i32];
        let _ = env.set_int_array_region(&out_rect, 0, &vals);
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Copia únicamente los píxeles de la subregión `(x, y, w, h)` en formato ARGB (`0xAARRGGBB`)
/// hacia `out_argb` para actualizar solo el área modificada del `Bitmap` en Kotlin.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeCopyRegionArgb(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    x: jint,
    y: jint,
    w: jint,
    h: jint,
    out_argb: JIntArray,
) -> jboolean {
    if x < 0 || y < 0 || w <= 0 || h <= 0 {
        return JNI_FALSE;
    }
    let count = (w as usize).saturating_mul(h as usize);
    let Ok(arr_len) = env.get_array_length(&out_argb) else {
        return JNI_FALSE;
    };
    if (arr_len as usize) < count {
        return JNI_FALSE;
    }

    let mut region = vec![0i32; count];
    let ok = unsafe {
        with_engine(handle_ptr, |engine| {
            engine.copy_region_argb(x as u32, y as u32, w as u32, h as u32, &mut region)
        })
        .unwrap_or(false)
    };
    if !ok {
        return JNI_FALSE;
    }
    if env.set_int_array_region(&out_argb, 0, &region).is_err() {
        return JNI_FALSE;
    }
    JNI_TRUE
}

/// Copia la imagen compuesta RGBA8888 al ByteBuffer directo proporcionado por Kotlin.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeRenderToDirectBuffer(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    direct_buffer: JByteBuffer,
) -> jboolean {
    let Ok(addr) = env.get_direct_buffer_address(&direct_buffer) else {
        return JNI_FALSE;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&direct_buffer) else {
        return JNI_FALSE;
    };

    let ok = unsafe {
        with_engine(handle_ptr, |engine| {
            if engine.dirty {
                engine.recomposite();
            }
            let needed = engine.composited_buffer.len();
            if cap < needed {
                return false;
            }
            std::ptr::copy_nonoverlapping(engine.composited_buffer.as_ptr(), addr, needed);
            true
        })
        .unwrap_or(false)
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Exporta el lienzo actual en bytes PNG listos para guardar en galería o compartir.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeExportPng<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle_ptr: jlong,
    include_background: jboolean,
) -> JByteArray<'local> {
    let bytes = unsafe {
        with_engine(handle_ptr, |engine| {
            engine
                .encode_png(include_background == JNI_TRUE)
                .unwrap_or_default()
        })
        .unwrap_or_default()
    };

    env.byte_array_from_slice(&bytes)
        .unwrap_or_else(|_| JByteArray::default())
}

/// Exporta el proyecto completo con todas sus capas en formato binario `.trazo`.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeExportProject<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle_ptr: jlong,
) -> JByteArray<'local> {
    let bytes = unsafe {
        with_engine(handle_ptr, |engine| engine.encode_project().unwrap_or_default())
            .unwrap_or_default()
    };
    env.byte_array_from_slice(&bytes)
        .unwrap_or_else(|_| JByteArray::default())
}

/// Carga un proyecto `.trazo` conservando todas las capas, opacidad, visibilidad y nombres.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeLoadProject(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    project_bytes: JByteArray,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&project_bytes) else {
        return JNI_FALSE;
    };
    let ok = unsafe {
        with_engine(handle_ptr, |engine| engine.load_project(&bytes).is_ok()).unwrap_or(false)
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Carga un PNG guardado previamente en el lienzo activo dentro de Rust.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeLoadPng(
    env: JNIEnv,
    _class: JClass,
    handle_ptr: jlong,
    png_bytes: JByteArray,
) -> jboolean {
    let Ok(bytes) = env.convert_byte_array(&png_bytes) else {
        return JNI_FALSE;
    };
    let ok = unsafe {
        with_engine(handle_ptr, |engine| engine.load_png(&bytes).is_ok()).unwrap_or(false)
    };
    if ok { JNI_TRUE } else { JNI_FALSE }
}

/// Devuelve metadatos compactos de estado:
/// [layerCount, activeLayer, canUndo, canRedo, strokeCount, baseVisible, baseModified].
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeGetStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle_ptr: jlong,
) -> JIntArray<'local> {
    let values = unsafe {
        with_engine(handle_ptr, |engine| {
            [
                engine.layers.len() as i32,
                engine.active_layer_index as i32,
                if engine.can_undo() { 1 } else { 0 },
                if engine.redo_stack.is_empty() { 0 } else { 1 },
                engine.stroke_count.min(i32::MAX as u64) as i32,
                if engine.base_layer_visible() { 1 } else { 0 },
                if engine.base_layer_modified { 1 } else { 0 },
            ]
        })
        .unwrap_or([0, 0, 0, 0, 0, 1, 0])
    };

    let arr = env.new_int_array(7).expect("No se pudo crear JIntArray");
    let _ = env.set_int_array_region(&arr, 0, &values);
    arr
}

/// Devuelve el nombre y visibilidad/opacidad codificados de cada capa como cadena separada por líneas.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeGetLayersManifest<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle_ptr: jlong,
) -> JString<'local> {
    let manifest = unsafe {
        with_engine(handle_ptr, |engine| {
            engine
                .layers
                .iter()
                .enumerate()
                .map(|(idx, l)| {
                    format!(
                        "{}|{}|{}|{}|{:.2}",
                        idx,
                        l.id,
                        l.name.replace('|', " "),
                        if l.visible { 1 } else { 0 },
                        l.opacity
                    )
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .unwrap_or_default()
    };

    env.new_string(manifest).unwrap_or_else(|_| JString::default())
}

/// Informa la arquitectura nativa con la que se compiló esta librería Rust (.so).
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeGetArchInfo<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> JString<'local> {
    let arch = std::env::consts::ARCH;
    let pointer_bits = std::mem::size_of::<usize>() * 8;
    let info = format!("Rust {} ({}-bit)", arch, pointer_bits);
    env.new_string(info).unwrap_or_else(|_| JString::default())
}

/// Analiza en Rust la imagen de calco (grosores de línea escalados al lienzo y paleta para colorear)
/// devolviendo un manifiesto compacto de líneas `META|...` y `COLOR|...`.
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeAnalyzeTracingImage<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    argb_pixels: JIntArray<'local>,
    width: jint,
    height: jint,
    rendered_width_on_canvas: jfloat,
) -> JString<'local> {
    init_android_logging();
    let w = width.max(0) as usize;
    let h = height.max(0) as usize;
    let needed = w.saturating_mul(h);

    let Ok(arr_len) = env.get_array_length(&argb_pixels) else {
        return env.new_string("").unwrap_or_else(|_| JString::default());
    };
    if needed == 0 || (arr_len.max(0) as usize) < needed {
        return env.new_string("").unwrap_or_else(|_| JString::default());
    }

    let mut pixels = vec![0i32; needed];
    if env.get_int_array_region(&argb_pixels, 0, &mut pixels).is_err() {
        return env.new_string("").unwrap_or_else(|_| JString::default());
    }

    let report = tracing_ai::analyze_tracing_image(
        &pixels,
        width.max(0) as u32,
        height.max(0) as u32,
        rendered_width_on_canvas,
    );
    env.new_string(report.to_manifest())
        .unwrap_or_else(|_| JString::default())
}
