//! Definición de tipos base, estructuras de capas, pinceles e historial del motor.
//! Este módulo mantiene los datos puros sin lógica de JNI para poder reutilizarlos
//! y probarlos independientemente de Android (compatible con arquitecturas de 32 y 64 bits).

/// Tipos de herramientas de trazo rasterizadas en Rust.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BrushKind {
    /// Pincel clásico con bordes suaves configurables.
    Brush = 0,
    /// Marcador de cobertura firme y uniforme.
    Marker = 1,
    /// Aerógrafo con caída radial suave y baja densidad por paso.
    Airbrush = 2,
    /// Borrador que resta canal alfa directamente sobre la capa activa.
    Eraser = 3,
}

impl BrushKind {
    pub fn from_i32(value: i32) -> Self {
        match value {
            1 => BrushKind::Marker,
            2 => BrushKind::Airbrush,
            3 => BrushKind::Eraser,
            _ => BrushKind::Brush,
        }
    }
}

/// Color RGBA de 8 bits por canal (no premultiplicado).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RgbaColor {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl RgbaColor {
    /// Convierte un entero ARGB de Android (`0xAARRGGBB`) a `RgbaColor`.
    pub fn from_argb_int(argb: i32) -> Self {
        let u = argb as u32;
        Self {
            a: ((u >> 24) & 0xFF) as u8,
            r: ((u >> 16) & 0xFF) as u8,
            g: ((u >> 8) & 0xFF) as u8,
            b: (u & 0xFF) as u8,
        }
    }

    /// Convierte `RgbaColor` de vuelta al entero ARGB (`0xAARRGGBB`) esperado por Android.
    pub fn to_argb_int(self) -> i32 {
        (((self.a as u32) << 24)
            | ((self.r as u32) << 16)
            | ((self.g as u32) << 8)
            | (self.b as u32)) as i32
    }
}

/// Parámetros de configuración para cada trazo aplicado al lienzo.
#[derive(Debug, Clone, Copy)]
pub struct BrushConfig {
    pub kind: BrushKind,
    pub color: RgbaColor,
    pub radius: f32,
    pub opacity: f32,
    pub hardness: f32,
}

/// Representa una capa independiente en formato RGBA8888.
#[derive(Debug, Clone)]
pub struct Layer {
    pub id: u32,
    pub name: String,
    pub visible: bool,
    pub opacity: f32,
    pub pixels: Vec<u8>,
}

impl Layer {
    /// Crea una capa transparente vacía con las dimensiones del lienzo.
    pub fn new_transparent(id: u32, name: String, width: u32, height: u32) -> Self {
        let byte_len = (width as usize)
            .saturating_mul(height as usize)
            .saturating_mul(4);
        Self {
            id,
            name,
            visible: true,
            opacity: 1.0,
            pixels: vec![0u8; byte_len],
        }
    }

    /// Crea una capa rellena con un color sólido inicial (por ejemplo, fondo blanco).
    pub fn new_filled(id: u32, name: String, width: u32, height: u32, color: RgbaColor) -> Self {
        let pixel_count = (width as usize).saturating_mul(height as usize);
        let mut pixels = Vec::with_capacity(pixel_count * 4);
        for _ in 0..pixel_count {
            pixels.push(color.r);
            pixels.push(color.g);
            pixels.push(color.b);
            pixels.push(color.a);
        }
        Self {
            id,
            name,
            visible: true,
            opacity: 1.0,
            pixels,
        }
    }
}

/// Instantánea ligera de una capa modificada para soportar Undo/Redo sin duplicar todo el proyecto.
#[derive(Debug, Clone)]
pub struct HistorySnapshot {
    pub layer_index: usize,
    pub pixels: Vec<u8>,
}
