//! Puente JNI entre el motor de dibujo en Rust (`DrawingEngine`) y Kotlin (`NativeEngineBridge`).
//! Seguridad de arquitecturas (32-bit y 64-bit):
//! - Cada instancia vive en un `Mutex<DrawingEngine>` asignado en el heap de Rust y expuesto como `jlong`.
//! - `jlong` tiene siempre 64 bits en JNI, por lo que convertir punteros `*mut EngineHandle` funciona
//!   de forma segura tanto en ARMv7/x86 (32 bits) como en ARM64/x86_64 (64 bits).
//! - La transferencia de píxeles a Kotlin se realiza mediante `ByteBuffer.allocateDirect(...)`,
//!   evitando copias intermedias en el heap de la JVM.

mod engine;
mod rasterizer;
mod types;

use std::sync::Arc;

use jni::objects::{JByteArray, JByteBuffer, JClass, JFloatArray, JString};
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
        with_engine(handle_ptr, |engine| engine.pick_color(x, y).to_argb_int()).unwrap_or(0xFF000000_u32 as i32)
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
        with_engine(handle_ptr, |engine| engine.delete_layer(index.max(0) as usize)).unwrap_or(false)
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
            engine.encode_png(include_background == JNI_TRUE).unwrap_or_default()
        })
        .unwrap_or_default()
    };

    env.byte_array_from_slice(&bytes)
        .unwrap_or_else(|_| JByteArray::default())
}

/// Devuelve metadatos compactos de estado: [layerCount, activeLayer, canUndo, canRedo, strokeCount].
#[no_mangle]
pub extern "system" fn Java_com_trazostudio_engine_NativeEngineBridge_nativeGetStats<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle_ptr: jlong,
) -> jni::objects::JIntArray<'local> {
    let values = unsafe {
        with_engine(handle_ptr, |engine| {
            [
                engine.layers.len() as i32,
                engine.active_layer_index as i32,
                if engine.undo_stack.is_empty() { 0 } else { 1 },
                if engine.redo_stack.is_empty() { 0 } else { 1 },
                engine.stroke_count.min(i32::MAX as u64) as i32,
            ]
        })
        .unwrap_or([0, 0, 0, 0, 0])
    };

    let arr = env.new_int_array(5).expect("No se pudo crear JIntArray");
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
