//! Definición de tipos base, estructuras de capas, pinceles e historial incremental del motor.
//! Este módulo mantiene los datos puros sin lógica de JNI para poder reutilizarlos
//! y probarlos independientemente de Android (compatible con arquitecturas de 32 y 64 bits).

/// Tamaño en píxeles de cada baldosa (tile) cuadrada usada por el historial incremental Undo/Redo.
/// 64×64 RGBA = 16 KB por baldosa, reduciendo drásticamente el consumo de RAM en lienzos grandes y 32-bit.
pub const UNDO_TILE_SIZE: u32 = 64;

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

/// Copia de seguridad de una única baldosa (`<= 64×64` px) modificada durante una operación.
#[derive(Debug, Clone)]
pub struct TileBackup {
    pub tx: u32,
    pub ty: u32,
    pub width: u32,
    pub height: u32,
    pub pixels: Vec<u8>,
}

/// Transacción activa que captura perezosamente únicamente las baldosas tocadas antes de pintar.
#[derive(Debug)]
pub struct PendingUndoTransaction {
    pub layer_index: usize,
    pub canvas_width: u32,
    pub canvas_height: u32,
    pub tile_cols: usize,
    pub captured: Vec<bool>,
    pub tiles: Vec<TileBackup>,
    pub min_x: u32,
    pub min_y: u32,
    pub max_x: u32,
    pub max_y: u32,
}

impl PendingUndoTransaction {
    pub fn new(layer_index: usize, canvas_width: u32, canvas_height: u32) -> Self {
        let tile_cols = ((canvas_width + UNDO_TILE_SIZE - 1) / UNDO_TILE_SIZE).max(1) as usize;
        let tile_rows = ((canvas_height + UNDO_TILE_SIZE - 1) / UNDO_TILE_SIZE).max(1) as usize;
        Self {
            layer_index,
            canvas_width,
            canvas_height,
            tile_cols,
            captured: vec![false; tile_cols * tile_rows],
            tiles: Vec::new(),
            min_x: u32::MAX,
            min_y: u32::MAX,
            max_x: 0,
            max_y: 0,
        }
    }

    /// Captura la baldosa `(tx, ty)` si aún no ha sido respaldada en esta transacción.
    pub fn capture_tile(&mut self, tx: u32, ty: u32, layer_pixels: &[u8]) {
        let idx = (ty as usize) * self.tile_cols + (tx as usize);
        if idx >= self.captured.len() || self.captured[idx] {
            return;
        }
        self.captured[idx] = true;

        let x0 = tx * UNDO_TILE_SIZE;
        let y0 = ty * UNDO_TILE_SIZE;
        if x0 >= self.canvas_width || y0 >= self.canvas_height {
            return;
        }
        let tw = UNDO_TILE_SIZE.min(self.canvas_width - x0);
        let th = UNDO_TILE_SIZE.min(self.canvas_height - y0);
        let row_bytes = (tw as usize) * 4;
        let stride = (self.canvas_width as usize) * 4;
        let mut buf = vec![0u8; row_bytes * (th as usize)];

        for row in 0..(th as usize) {
            let src_start = ((y0 as usize) + row) * stride + (x0 as usize) * 4;
            let dst_start = row * row_bytes;
            if src_start + row_bytes <= layer_pixels.len() {
                buf[dst_start..dst_start + row_bytes]
                    .copy_from_slice(&layer_pixels[src_start..src_start + row_bytes]);
            }
        }

        self.tiles.push(TileBackup {
            tx,
            ty,
            width: tw,
            height: th,
            pixels: buf,
        });
    }

    /// Captura todas las baldosas cubiertas por un rectángulo inclusivo `(x0..=x1, y0..=y1)`.
    pub fn capture_rect(&mut self, x0: u32, y0: u32, x1: u32, y1: u32, layer_pixels: &[u8]) {
        if self.canvas_width == 0 || self.canvas_height == 0 {
            return;
        }
        let cx0 = x0.min(self.canvas_width - 1);
        let cy0 = y0.min(self.canvas_height - 1);
        let cx1 = x1.min(self.canvas_width - 1).max(cx0);
        let cy1 = y1.min(self.canvas_height - 1).max(cy0);

        self.min_x = self.min_x.min(cx0);
        self.min_y = self.min_y.min(cy0);
        self.max_x = self.max_x.max(cx1);
        self.max_y = self.max_y.max(cy1);

        let tx0 = cx0 / UNDO_TILE_SIZE;
        let ty0 = cy0 / UNDO_TILE_SIZE;
        let tx1 = cx1 / UNDO_TILE_SIZE;
        let ty1 = cy1 / UNDO_TILE_SIZE;

        for ty in ty0..=ty1 {
            for tx in tx0..=tx1 {
                self.capture_tile(tx, ty, layer_pixels);
            }
        }
    }

    /// Captura al vuelo la baldosa que contiene al píxel `(x, y)` (útil en `flood_fill`).
    #[inline]
    pub fn capture_pixel(&mut self, x: usize, y: usize, layer_pixels: &[u8]) {
        let tx = x / (UNDO_TILE_SIZE as usize);
        let ty = y / (UNDO_TILE_SIZE as usize);
        let idx = ty * self.tile_cols + tx;
        if idx < self.captured.len() && !self.captured[idx] {
            self.capture_tile(tx as u32, ty as u32, layer_pixels);
        }
        let ux = x as u32;
        let uy = y as u32;
        self.min_x = self.min_x.min(ux);
        self.min_y = self.min_y.min(uy);
        self.max_x = self.max_x.max(ux);
        self.max_y = self.max_y.max(uy);
    }

    /// Convierte la transacción en un `HistorySnapshot` compacto si hubo baldosas modificadas.
    pub fn into_snapshot(self) -> Option<HistorySnapshot> {
        if self.tiles.is_empty() || self.min_x > self.max_x || self.min_y > self.max_y {
            return None;
        }
        Some(HistorySnapshot::TilePatches {
            layer_index: self.layer_index,
            tiles: self.tiles,
            dirty_rect: (self.min_x, self.min_y, self.max_x, self.max_y),
        })
    }
}

/// Instantánea incremental (por baldosas) o completa de una capa para Undo/Redo.
#[derive(Debug, Clone)]
pub enum HistorySnapshot {
    TilePatches {
        layer_index: usize,
        tiles: Vec<TileBackup>,
        dirty_rect: (u32, u32, u32, u32),
    },
    #[allow(dead_code)]
    FullLayer {
        layer_index: usize,
        pixels: Vec<u8>,
    },
}

impl HistorySnapshot {
    pub fn layer_index(&self) -> usize {
        match self {
            HistorySnapshot::TilePatches { layer_index, .. } => *layer_index,
            HistorySnapshot::FullLayer { layer_index, .. } => *layer_index,
        }
    }

    pub fn approx_bytes(&self) -> usize {
        match self {
            HistorySnapshot::TilePatches { tiles, .. } => {
                tiles.iter().map(|t| t.pixels.len() + 32).sum()
            }
            HistorySnapshot::FullLayer { pixels, .. } => pixels.len(),
        }
    }
}
