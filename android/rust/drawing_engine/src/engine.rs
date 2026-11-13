//! Núcleo principal de estado y composición multicapa del motor de dibujo en Rust.
//! Gestiona las capas, la pila Undo/Redo incremental por baldosas (64×64),
//! la composición parcial por región sucia (`dirty_rect`), el formato binario multicapa `.trazo`
//! y la exportación/importación PNG usando el crate `png` (Apache-2.0 / MIT).

use std::io::Cursor;

use crate::rasterizer::{draw_segment, flood_fill, stamp_dab};
use crate::types::{
    BrushConfig, HistorySnapshot, Layer, PendingUndoTransaction, RgbaColor, UNDO_TILE_SIZE,
};

const MAX_UNDO_HISTORY: usize = 25;
const TRAZO_PROJECT_MAGIC: &[u8; 8] = b"TRAZOv01";

#[cfg(target_pointer_width = "32")]
const MAX_UNDO_MEMORY_BYTES: usize = 48 * 1024 * 1024;
#[cfg(not(target_pointer_width = "32"))]
const MAX_UNDO_MEMORY_BYTES: usize = 96 * 1024 * 1024;

/// Estado completo de una sesión de lienzo en memoria nativa.
pub struct DrawingEngine {
    pub width: u32,
    pub height: u32,
    pub layers: Vec<Layer>,
    pub active_layer_index: usize,
    pub next_layer_id: u32,
    pub undo_stack: Vec<HistorySnapshot>,
    pub redo_stack: Vec<HistorySnapshot>,
    pub active_undo: Option<PendingUndoTransaction>,
    pub composited_buffer: Vec<u8>,
    pub stroke_count: u64,
    pub dirty: bool,
    pub dirty_rect: Option<(u32, u32, u32, u32)>,
    /// Cuando está activo y el fondo blanco base no fue modificado, se omite la capa 0
    /// al componer en pantalla para que el calco se dibuje limpiamente debajo de las capas de dibujo.
    pub exclude_base_layer: bool,
    pub base_layer_modified: bool,
}

impl DrawingEngine {
    /// Inicializa un nuevo lienzo con una capa base blanca opaca y una capa superior de trazo transparente.
    pub fn new(width: u32, height: u32) -> Self {
        let safe_w = width.clamp(64, 4096);
        let safe_h = height.clamp(64, 4096);

        let background = Layer::new_filled(
            1,
            "Fondo".to_string(),
            safe_w,
            safe_h,
            RgbaColor {
                r: 255,
                g: 255,
                b: 255,
                a: 255,
            },
        );
        let sketch_layer = Layer::new_transparent(2, "Capa 1".to_string(), safe_w, safe_h);

        let buffer_len = (safe_w as usize) * (safe_h as usize) * 4;
        let mut engine = Self {
            width: safe_w,
            height: safe_h,
            layers: vec![background, sketch_layer],
            active_layer_index: 1,
            next_layer_id: 3,
            undo_stack: Vec::new(),
            redo_stack: Vec::new(),
            active_undo: None,
            composited_buffer: vec![0u8; buffer_len],
            stroke_count: 0,
            dirty: true,
            dirty_rect: Some((0, 0, safe_w - 1, safe_h - 1)),
            exclude_base_layer: false,
            base_layer_modified: false,
        };
        engine.recomposite();
        engine
    }

    #[inline]
    fn mark_dirty_rect(&mut self, x0: u32, y0: u32, x1: u32, y1: u32) {
        if self.width == 0 || self.height == 0 {
            return;
        }
        let cx0 = x0.min(self.width - 1);
        let cy0 = y0.min(self.height - 1);
        let cx1 = x1.min(self.width - 1).max(cx0);
        let cy1 = y1.min(self.height - 1).max(cy0);
        self.dirty_rect = Some(match self.dirty_rect {
            Some((ox0, oy0, ox1, oy1)) => (ox0.min(cx0), oy0.min(cy0), ox1.max(cx1), oy1.max(cy1)),
            None => (cx0, cy0, cx1, cy1),
        });
        self.dirty = true;
    }

    #[inline]
    fn mark_full_dirty(&mut self) {
        if self.width > 0 && self.height > 0 {
            self.mark_dirty_rect(0, 0, self.width - 1, self.height - 1);
        }
    }

    fn undo_memory_bytes(&self) -> usize {
        self.undo_stack.iter().map(|s| s.approx_bytes()).sum()
    }

    fn push_undo_snapshot(&mut self, snapshot: HistorySnapshot) {
        self.undo_stack.push(snapshot);
        self.redo_stack.clear();
        while self.undo_stack.len() > MAX_UNDO_HISTORY
            || (self.undo_stack.len() > 1 && self.undo_memory_bytes() > MAX_UNDO_MEMORY_BYTES)
        {
            self.undo_stack.remove(0);
        }
    }

    /// Consolida la transacción activa de trazo en la pila `undo_stack`.
    pub fn commit_pending_undo(&mut self) {
        if let Some(tx) = self.active_undo.take() {
            if let Some(snapshot) = tx.into_snapshot() {
                self.push_undo_snapshot(snapshot);
            }
        }
    }

    /// Verifica si la capa 0 sigue siendo papel blanco uniforme (sin trazos propios).
    fn refresh_base_layer_modified(&mut self) {
        self.base_layer_modified = self
            .layers
            .first()
            .map(|layer| layer.pixels.iter().any(|&b| b != 255))
            .unwrap_or(false);
    }

    pub fn can_undo(&self) -> bool {
        !self.undo_stack.is_empty()
            || self
                .active_undo
                .as_ref()
                .is_some_and(|tx| !tx.tiles.is_empty())
    }

    pub fn base_layer_visible(&self) -> bool {
        self.layers
            .first()
            .is_some_and(|l| l.visible && l.opacity > 0.001)
    }

    pub fn set_exclude_base_layer(&mut self, exclude: bool) {
        if self.exclude_base_layer != exclude {
            self.exclude_base_layer = exclude;
            self.mark_full_dirty();
        }
    }

    /// Inicia una transacción incremental de trazo sin clonar la capa completa por adelantado.
    pub fn begin_stroke_transaction(&mut self) {
        self.commit_pending_undo();
        if self.active_layer_index < self.layers.len() {
            self.active_undo = Some(PendingUndoTransaction::new(
                self.active_layer_index,
                self.width,
                self.height,
            ));
        }
    }

    /// Cierra la transacción de trazo al levantar el dedo.
    pub fn end_stroke_transaction(&mut self) {
        self.commit_pending_undo();
    }

    /// Estampa un punto único (al tocar la pantalla) y marca solo su caja delimitadora como sucia.
    pub fn apply_dab(&mut self, x: f32, y: f32, brush: BrushConfig) {
        let idx = self.active_layer_index;
        let outer = brush.radius.max(0.5) + 2.0;
        let x0 = (x - outer).floor().max(0.0) as u32;
        let y0 = (y - outer).floor().max(0.0) as u32;
        let x1 = (x + outer).ceil().max(0.0) as u32;
        let y1 = (y + outer).ceil().max(0.0) as u32;

        let mut drew = false;
        if let Some(layer) = self.layers.get_mut(idx) {
            if !layer.visible {
                return;
            }
            if let Some(tx) = self.active_undo.as_mut() {
                if tx.layer_index == idx {
                    tx.capture_rect(x0, y0, x1, y1, &layer.pixels);
                }
            }
            stamp_dab(&mut layer.pixels, self.width, self.height, x, y, brush);
            drew = true;
        }
        if drew {
            if idx == 0 {
                self.base_layer_modified = true;
            }
            self.redo_stack.clear();
            self.stroke_count += 1;
            self.mark_dirty_rect(x0, y0, x1, y1);
        }
    }

    /// Dibuja un trazo interpolado entre dos coordenadas del lienzo y actualiza solo la región afectada.
    pub fn apply_segment(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, brush: BrushConfig) {
        let idx = self.active_layer_index;
        let outer = brush.radius.max(0.5) + 2.0;
        let min_x = (x0.min(x1) - outer).floor().max(0.0) as u32;
        let min_y = (y0.min(y1) - outer).floor().max(0.0) as u32;
        let max_x = (x0.max(x1) + outer).ceil().max(0.0) as u32;
        let max_y = (y0.max(y1) + outer).ceil().max(0.0) as u32;

        let mut drew = false;
        if let Some(layer) = self.layers.get_mut(idx) {
            if !layer.visible {
                return;
            }
            if let Some(tx) = self.active_undo.as_mut() {
                if tx.layer_index == idx {
                    tx.capture_rect(min_x, min_y, max_x, max_y, &layer.pixels);
                }
            }
            draw_segment(&mut layer.pixels, self.width, self.height, x0, y0, x1, y1, brush);
            drew = true;
        }
        if drew {
            if idx == 0 {
                self.base_layer_modified = true;
            }
            self.redo_stack.clear();
            self.mark_dirty_rect(min_x, min_y, max_x, max_y);
        }
    }

    /// Rasteriza múltiples segmentos `[x0, y0, x1, y1, ...]` en una sola llamada nativa.
    pub fn apply_segments_batch(&mut self, coords: &[f32], brush: BrushConfig) {
        if coords.len() < 4 {
            return;
        }
        let idx = self.active_layer_index;

        let outer = brush.radius.max(0.5) + 2.0;
        let mut min_fx = f32::MAX;
        let mut min_fy = f32::MAX;
        let mut max_fx = f32::MIN;
        let mut max_fy = f32::MIN;

        for seg in coords.chunks_exact(4) {
            min_fx = min_fx.min(seg[0].min(seg[2]));
            min_fy = min_fy.min(seg[1].min(seg[3]));
            max_fx = max_fx.max(seg[0].max(seg[2]));
            max_fy = max_fy.max(seg[1].max(seg[3]));
        }

        if min_fx > max_fx || min_fy > max_fy {
            return;
        }

        let min_x = (min_fx - outer).floor().max(0.0) as u32;
        let min_y = (min_fy - outer).floor().max(0.0) as u32;
        let max_x = (max_fx + outer).ceil().max(0.0) as u32;
        let max_y = (max_fy + outer).ceil().max(0.0) as u32;

        let mut drew = false;
        if let Some(layer) = self.layers.get_mut(idx) {
            if !layer.visible {
                return;
            }
            if let Some(tx) = self.active_undo.as_mut() {
                if tx.layer_index == idx {
                    tx.capture_rect(min_x, min_y, max_x, max_y, &layer.pixels);
                }
            }
            for seg in coords.chunks_exact(4) {
                draw_segment(
                    &mut layer.pixels,
                    self.width,
                    self.height,
                    seg[0],
                    seg[1],
                    seg[2],
                    seg[3],
                    brush,
                );
            }
            drew = true;
        }

        if drew {
            if idx == 0 {
                self.base_layer_modified = true;
            }
            self.redo_stack.clear();
            self.mark_dirty_rect(min_x, min_y, max_x, max_y);
        }
    }

    /// Aplica relleno por inundación sobre la capa activa respaldando solo las baldosas afectadas.
    pub fn apply_flood_fill(&mut self, x: i32, y: i32, color: RgbaColor, tolerance: u8) -> bool {
        self.commit_pending_undo();
        let idx = self.active_layer_index;
        let mut tx = PendingUndoTransaction::new(idx, self.width, self.height);

        let changed = if let Some(layer) = self.layers.get_mut(idx) {
            if !layer.visible {
                false
            } else {
                flood_fill(
                    &mut layer.pixels,
                    self.width,
                    self.height,
                    x,
                    y,
                    color,
                    tolerance,
                    Some(&mut tx),
                )
            }
        } else {
            false
        };

        if changed {
            if idx == 0 {
                self.base_layer_modified = true;
            }
            let dirty_bounds = (tx.min_x, tx.min_y, tx.max_x, tx.max_y);
            if let Some(snapshot) = tx.into_snapshot() {
                self.push_undo_snapshot(snapshot);
            }
            self.stroke_count += 1;
            if dirty_bounds.0 <= dirty_bounds.2 && dirty_bounds.1 <= dirty_bounds.3 {
                self.mark_dirty_rect(dirty_bounds.0, dirty_bounds.1, dirty_bounds.2, dirty_bounds.3);
            } else {
                self.mark_full_dirty();
            }
        }
        changed
    }

    /// Lee el color RGBA compuesto en un punto del lienzo (herramienta cuentagotas).
    pub fn pick_color(&mut self, x: i32, y: i32) -> RgbaColor {
        if self.dirty {
            self.recomposite();
        }
        if x < 0 || y < 0 || x >= self.width as i32 || y >= self.height as i32 {
            return RgbaColor {
                r: 0,
                g: 0,
                b: 0,
                a: 255,
            };
        }
        let idx = ((y as usize) * (self.width as usize) + (x as usize)) * 4;
        RgbaColor {
            r: self.composited_buffer[idx],
            g: self.composited_buffer[idx + 1],
            b: self.composited_buffer[idx + 2],
            a: self.composited_buffer[idx + 3],
        }
    }

    /// Limpia la capa activa respaldando únicamente las baldosas no vacías (o no blancas en la capa 0).
    pub fn clear_active_layer(&mut self) {
        self.commit_pending_undo();
        let idx = self.active_layer_index;
        let is_base = idx == 0;

        let mut tx = PendingUndoTransaction::new(idx, self.width, self.height);
        let tile_cols = tx.tile_cols;
        let tile_rows = ((self.height + UNDO_TILE_SIZE - 1) / UNDO_TILE_SIZE).max(1) as usize;

        if let Some(layer) = self.layers.get_mut(idx) {
            let stride = (self.width as usize) * 4;
            for ty in 0..tile_rows {
                for tx_col in 0..tile_cols {
                    let x0 = tx_col * (UNDO_TILE_SIZE as usize);
                    let y0 = ty * (UNDO_TILE_SIZE as usize);
                    let tw = (UNDO_TILE_SIZE as usize).min((self.width as usize) - x0);
                    let th = (UNDO_TILE_SIZE as usize).min((self.height as usize) - y0);
                    let mut differs = false;
                    'scan: for row in 0..th {
                        let start = (y0 + row) * stride + x0 * 4;
                        let end = start + tw * 4;
                        if is_base {
                            if layer.pixels[start..end].iter().any(|&b| b != 255) {
                                differs = true;
                                break 'scan;
                            }
                        } else if layer.pixels[start..end].iter().any(|&b| b != 0) {
                            differs = true;
                            break 'scan;
                        }
                    }
                    if differs {
                        tx.capture_rect(
                            x0 as u32,
                            y0 as u32,
                            (x0 + tw - 1) as u32,
                            (y0 + th - 1) as u32,
                            &layer.pixels,
                        );
                    }
                }
            }

            if is_base {
                for chunk in layer.pixels.chunks_exact_mut(4) {
                    chunk[0] = 255;
                    chunk[1] = 255;
                    chunk[2] = 255;
                    chunk[3] = 255;
                }
                self.base_layer_modified = false;
            } else {
                layer.pixels.fill(0);
            }

            let dirty_bounds = (tx.min_x, tx.min_y, tx.max_x, tx.max_y);
            if let Some(snapshot) = tx.into_snapshot() {
                self.push_undo_snapshot(snapshot);
            }
            if dirty_bounds.0 <= dirty_bounds.2 && dirty_bounds.1 <= dirty_bounds.3 {
                self.mark_dirty_rect(dirty_bounds.0, dirty_bounds.1, dirty_bounds.2, dirty_bounds.3);
            } else {
                self.mark_full_dirty();
            }
        }
    }

    fn swap_snapshot_with_layer(
        layer: &mut Layer,
        canvas_width: u32,
        canvas_height: u32,
        snapshot: HistorySnapshot,
    ) -> (HistorySnapshot, (u32, u32, u32, u32)) {
        match snapshot {
            HistorySnapshot::FullLayer {
                layer_index,
                pixels,
            } => {
                let old = std::mem::replace(&mut layer.pixels, pixels);
                let rect = (
                    0,
                    0,
                    canvas_width.saturating_sub(1),
                    canvas_height.saturating_sub(1),
                );
                (
                    HistorySnapshot::FullLayer {
                        layer_index,
                        pixels: old,
                    },
                    rect,
                )
            }
            HistorySnapshot::TilePatches {
                layer_index,
                mut tiles,
                dirty_rect,
            } => {
                let stride = (canvas_width as usize) * 4;
                for tile in &mut tiles {
                    let x0 = (tile.tx * UNDO_TILE_SIZE) as usize;
                    let y0 = (tile.ty * UNDO_TILE_SIZE) as usize;
                    let row_bytes = (tile.width as usize) * 4;
                    for row in 0..(tile.height as usize) {
                        let layer_start = (y0 + row) * stride + x0 * 4;
                        let tile_start = row * row_bytes;
                        if layer_start + row_bytes <= layer.pixels.len()
                            && tile_start + row_bytes <= tile.pixels.len()
                        {
                            layer.pixels[layer_start..layer_start + row_bytes]
                                .swap_with_slice(&mut tile.pixels[tile_start..tile_start + row_bytes]);
                        }
                    }
                }
                (
                    HistorySnapshot::TilePatches {
                        layer_index,
                        tiles,
                        dirty_rect,
                    },
                    dirty_rect,
                )
            }
        }
    }

    /// Deshace la última operación restaurando únicamente las baldosas modificadas.
    pub fn undo(&mut self) -> bool {
        self.commit_pending_undo();
        let Some(snapshot) = self.undo_stack.pop() else {
            return false;
        };
        let layer_idx = snapshot.layer_index();
        if let Some(layer) = self.layers.get_mut(layer_idx) {
            let (redo_snap, dirty) =
                Self::swap_snapshot_with_layer(layer, self.width, self.height, snapshot);
            self.redo_stack.push(redo_snap);
            self.active_layer_index = layer_idx;
            if layer_idx == 0 {
                self.refresh_base_layer_modified();
            }
            self.mark_dirty_rect(dirty.0, dirty.1, dirty.2, dirty.3);
            true
        } else {
            false
        }
    }

    /// Rehace una operación deshecha previamente restaurando solo sus baldosas.
    pub fn redo(&mut self) -> bool {
        self.commit_pending_undo();
        let Some(snapshot) = self.redo_stack.pop() else {
            return false;
        };
        let layer_idx = snapshot.layer_index();
        if let Some(layer) = self.layers.get_mut(layer_idx) {
            let (undo_snap, dirty) =
                Self::swap_snapshot_with_layer(layer, self.width, self.height, snapshot);
            self.undo_stack.push(undo_snap);
            self.active_layer_index = layer_idx;
            if layer_idx == 0 {
                self.refresh_base_layer_modified();
            }
            self.mark_dirty_rect(dirty.0, dirty.1, dirty.2, dirty.3);
            true
        } else {
            false
        }
    }

    /// Agrega una nueva capa transparente encima de las existentes y la selecciona.
    pub fn add_layer(&mut self, custom_name: Option<String>) -> usize {
        self.commit_pending_undo();
        if self.layers.len() >= 12 {
            return self.active_layer_index;
        }
        let id = self.next_layer_id;
        self.next_layer_id += 1;
        let name = custom_name.unwrap_or_else(|| format!("Capa {}", id - 1));
        let layer = Layer::new_transparent(id, name, self.width, self.height);
        self.layers.push(layer);
        self.active_layer_index = self.layers.len() - 1;
        self.mark_full_dirty();
        self.active_layer_index
    }

    /// Elimina una capa si hay más de una disponible y limpia referencias del historial.
    pub fn delete_layer(&mut self, index: usize) -> bool {
        self.commit_pending_undo();
        if self.layers.len() <= 1 || index >= self.layers.len() {
            return false;
        }
        self.layers.remove(index);
        self.undo_stack.clear();
        self.redo_stack.clear();
        if self.active_layer_index >= self.layers.len() {
            self.active_layer_index = self.layers.len() - 1;
        }
        self.refresh_base_layer_modified();
        self.mark_full_dirty();
        true
    }

    pub fn set_layer_visibility(&mut self, index: usize, visible: bool) {
        if let Some(layer) = self.layers.get_mut(index) {
            layer.visible = visible;
            self.mark_full_dirty();
        }
    }

    pub fn set_layer_opacity(&mut self, index: usize, opacity: f32) {
        if let Some(layer) = self.layers.get_mut(index) {
            layer.opacity = opacity.clamp(0.0, 1.0);
            self.mark_full_dirty();
        }
    }

    pub fn set_active_layer(&mut self, index: usize) {
        if index < self.layers.len() {
            let prev = self.active_layer_index;
            self.active_layer_index = index;
            if self.exclude_base_layer && (prev == 0 || index == 0) && prev != index {
                self.mark_full_dirty();
            }
        }
    }

    /// Combina la capa seleccionada hacia la capa inmediatamente inferior.
    pub fn merge_layer_down(&mut self, index: usize) -> bool {
        self.commit_pending_undo();
        if index == 0 || index >= self.layers.len() {
            return false;
        }
        let top = self.layers.remove(index);
        let target = &mut self.layers[index - 1];
        let total_pixels = (self.width as usize) * (self.height as usize);
        let layer_alpha = top.opacity;

        for p in 0..total_pixels {
            let i = p * 4;
            let sa = (top.pixels[i + 3] as f32 / 255.0) * layer_alpha;
            if sa <= 0.001 {
                continue;
            }
            let src = RgbaColor {
                r: top.pixels[i],
                g: top.pixels[i + 1],
                b: top.pixels[i + 2],
                a: 255,
            };
            crate::rasterizer::blend_source_over_pixel(&mut target.pixels, i, src, sa);
        }

        self.undo_stack.clear();
        self.redo_stack.clear();
        self.active_layer_index = index - 1;
        self.refresh_base_layer_modified();
        self.mark_full_dirty();
        true
    }

    /// Recompone únicamente la región sucia (`dirty_rect`) y devuelve `(x, y, w, h)` si hubo cambios.
    pub fn prepare_render_rect(&mut self) -> Option<(u32, u32, u32, u32)> {
        if !self.dirty && self.dirty_rect.is_none() {
            return None;
        }
        if self.width == 0 || self.height == 0 {
            self.dirty = false;
            self.dirty_rect = None;
            return None;
        }

        let (rx0, ry0, rx1, ry1) = self
            .dirty_rect
            .take()
            .unwrap_or((0, 0, self.width - 1, self.height - 1));
        let x0 = (rx0 as usize).min((self.width - 1) as usize);
        let y0 = (ry0 as usize).min((self.height - 1) as usize);
        let x1 = (rx1 as usize).min((self.width - 1) as usize).max(x0);
        let y1 = (ry1 as usize).min((self.height - 1) as usize).max(y0);
        let w = self.width as usize;

        let skip_base = self.exclude_base_layer
            && !self.base_layer_modified
            && self.active_layer_index != 0
            && self.layers.len() > 1;
        let first_scan_idx = if skip_base { 1 } else { 0 };

        let first_visible = self
            .layers
            .iter()
            .enumerate()
            .skip(first_scan_idx)
            .find(|(_, l)| l.visible && l.opacity > 0.001)
            .map(|(i, _)| i);

        let start_layer_idx = match first_visible {
            Some(idx) if self.layers[idx].opacity >= 0.999 => {
                // Copia directa por fila para la primera capa visible sin transparencia de capa.
                let base = &self.layers[idx].pixels;
                for y in y0..=y1 {
                    let row_start = (y * w + x0) * 4;
                    let row_end = (y * w + x1 + 1) * 4;
                    self.composited_buffer[row_start..row_end]
                        .copy_from_slice(&base[row_start..row_end]);
                }
                idx + 1
            }
            Some(idx) => {
                for y in y0..=y1 {
                    let row_start = (y * w + x0) * 4;
                    let row_end = (y * w + x1 + 1) * 4;
                    self.composited_buffer[row_start..row_end].fill(0);
                }
                idx
            }
            None => {
                for y in y0..=y1 {
                    let row_start = (y * w + x0) * 4;
                    let row_end = (y * w + x1 + 1) * 4;
                    self.composited_buffer[row_start..row_end].fill(0);
                }
                self.layers.len()
            }
        };

        for layer in self.layers.iter().skip(start_layer_idx) {
            if !layer.visible || layer.opacity <= 0.001 {
                continue;
            }
            let layer_alpha = layer.opacity;
            let full_layer_opacity = layer_alpha >= 0.999;

            for y in y0..=y1 {
                let row_base = y * w;
                for x in x0..=x1 {
                    let i = (row_base + x) * 4;
                    let src_a_raw = layer.pixels[i + 3];
                    if src_a_raw == 0 {
                        continue;
                    }
                    if src_a_raw == 255 && full_layer_opacity {
                        self.composited_buffer[i] = layer.pixels[i];
                        self.composited_buffer[i + 1] = layer.pixels[i + 1];
                        self.composited_buffer[i + 2] = layer.pixels[i + 2];
                        self.composited_buffer[i + 3] = 255;
                        continue;
                    }

                    let sa = (src_a_raw as f32 / 255.0) * layer_alpha;
                    let dst_a = self.composited_buffer[i + 3] as f32 / 255.0;

                    if sa >= 0.999 && dst_a == 0.0 {
                        self.composited_buffer[i] = layer.pixels[i];
                        self.composited_buffer[i + 1] = layer.pixels[i + 1];
                        self.composited_buffer[i + 2] = layer.pixels[i + 2];
                        self.composited_buffer[i + 3] = 255;
                    } else {
                        let out_a = sa + dst_a * (1.0 - sa);
                        if out_a > 0.0001 {
                            let inv_out = 1.0 / out_a;
                            let da_inv_sa = dst_a * (1.0 - sa);
                            self.composited_buffer[i] = ((layer.pixels[i] as f32 * sa
                                + self.composited_buffer[i] as f32 * da_inv_sa)
                                * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                            self.composited_buffer[i + 1] = ((layer.pixels[i + 1] as f32 * sa
                                + self.composited_buffer[i + 1] as f32 * da_inv_sa)
                                * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                            self.composited_buffer[i + 2] = ((layer.pixels[i + 2] as f32 * sa
                                + self.composited_buffer[i + 2] as f32 * da_inv_sa)
                                * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                            self.composited_buffer[i + 3] =
                                (out_a * 255.0).round().clamp(0.0, 255.0) as u8;
                        }
                    }
                }
            }
        }
        self.dirty = false;
        Some((
            x0 as u32,
            y0 as u32,
            (x1 - x0 + 1) as u32,
            (y1 - y0 + 1) as u32,
        ))
    }

    /// Recompone toda región sucia pendiente dentro de `composited_buffer`.
    pub fn recomposite(&mut self) {
        let _ = self.prepare_render_rect();
    }

    /// Empaqueta únicamente la subregión `(x, y, w, h)` en enteros ARGB (`0xAARRGGBB`)
    /// para evitar transferir todo el lienzo en cada cuadro hacia Kotlin (`Bitmap.setPixels`).
    pub fn copy_region_argb(&self, x: u32, y: u32, w: u32, h: u32, out: &mut [i32]) -> bool {
        if x >= self.width || y >= self.height || w == 0 || h == 0 {
            return false;
        }
        let cw = w.min(self.width - x) as usize;
        let ch = h.min(self.height - y) as usize;
        if out.len() < cw * ch {
            return false;
        }
        let canvas_w = self.width as usize;
        let x0 = x as usize;
        let y0 = y as usize;

        for row in 0..ch {
            let src_row_start = ((y0 + row) * canvas_w + x0) * 4;
            let dst_row_start = row * cw;
            for col in 0..cw {
                let s = src_row_start + col * 4;
                let r = self.composited_buffer[s] as u32;
                let g = self.composited_buffer[s + 1] as u32;
                let b = self.composited_buffer[s + 2] as u32;
                let a = self.composited_buffer[s + 3] as u32;
                out[dst_row_start + col] = ((a << 24) | (r << 16) | (g << 8) | b) as i32;
            }
        }
        true
    }

    /// Codifica el lienzo actual en un archivo PNG en memoria usando la librería `png`.
    pub fn encode_png(&mut self, include_base_background: bool) -> Result<Vec<u8>, String> {
        let pixel_count = (self.width as usize) * (self.height as usize);
        let mut export_buf = vec![0u8; pixel_count * 4];

        for (idx, layer) in self.layers.iter().enumerate() {
            if !layer.visible || layer.opacity <= 0.001 {
                continue;
            }
            if idx == 0 && !include_base_background {
                continue;
            }
            let layer_alpha = layer.opacity;
            for p in 0..pixel_count {
                let i = p * 4;
                let src_a_raw = layer.pixels[i + 3];
                if src_a_raw == 0 {
                    continue;
                }
                let sa = (src_a_raw as f32 / 255.0) * layer_alpha;
                let src = RgbaColor {
                    r: layer.pixels[i],
                    g: layer.pixels[i + 1],
                    b: layer.pixels[i + 2],
                    a: 255,
                };
                crate::rasterizer::blend_source_over_pixel(&mut export_buf, i, src, sa);
            }
        }

        Self::encode_rgba_as_png(self.width, self.height, &export_buf)
    }

    fn encode_rgba_as_png(width: u32, height: u32, rgba: &[u8]) -> Result<Vec<u8>, String> {
        let mut png_bytes = Vec::new();
        {
            let cursor = Cursor::new(&mut png_bytes);
            let mut encoder = png::Encoder::new(cursor, width, height);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder
                .write_header()
                .map_err(|e| format!("Error al escribir cabecera PNG: {e}"))?;
            writer
                .write_image_data(rgba)
                .map_err(|e| format!("Error al codificar datos PNG: {e}"))?;
        }
        Ok(png_bytes)
    }

    fn decode_png_to_rgba(
        png_bytes: &[u8],
        dst_w: usize,
        dst_h: usize,
    ) -> Result<Vec<u8>, String> {
        let mut decoder = png::Decoder::new(Cursor::new(png_bytes));
        decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
        let mut reader = decoder
            .read_info()
            .map_err(|e| format!("Error al leer cabecera PNG: {e}"))?;

        let mut buf = vec![0u8; reader.output_buffer_size()];
        let info = reader
            .next_frame(&mut buf)
            .map_err(|e| format!("Error al decodificar PNG: {e}"))?;
        let src = &buf[..info.buffer_size()];

        let src_w = info.width as usize;
        let src_h = info.height as usize;
        let mut out = vec![0u8; dst_w * dst_h * 4];

        let copy_w = src_w.min(dst_w);
        let copy_h = src_h.min(dst_h);
        let channels = match info.color_type {
            png::ColorType::Rgba => 4,
            png::ColorType::Rgb => 3,
            png::ColorType::GrayscaleAlpha => 2,
            png::ColorType::Grayscale => 1,
            png::ColorType::Indexed => return Err("Formato PNG indexado no expandido".to_string()),
        };

        for y in 0..copy_h {
            for x in 0..copy_w {
                let s_idx = (y * src_w + x) * channels;
                let d_idx = (y * dst_w + x) * 4;
                if s_idx + channels > src.len() || d_idx + 4 > out.len() {
                    continue;
                }
                match channels {
                    4 => {
                        out[d_idx] = src[s_idx];
                        out[d_idx + 1] = src[s_idx + 1];
                        out[d_idx + 2] = src[s_idx + 2];
                        out[d_idx + 3] = src[s_idx + 3];
                    }
                    3 => {
                        out[d_idx] = src[s_idx];
                        out[d_idx + 1] = src[s_idx + 1];
                        out[d_idx + 2] = src[s_idx + 2];
                        out[d_idx + 3] = 255;
                    }
                    2 => {
                        let g = src[s_idx];
                        out[d_idx] = g;
                        out[d_idx + 1] = g;
                        out[d_idx + 2] = g;
                        out[d_idx + 3] = src[s_idx + 1];
                    }
                    _ => {
                        let g = src[s_idx];
                        out[d_idx] = g;
                        out[d_idx + 1] = g;
                        out[d_idx + 2] = g;
                        out[d_idx + 3] = 255;
                    }
                }
            }
        }
        Ok(out)
    }

    /// Serializa todas las capas, visibilidad, opacidad, nombres y capa activa al formato propio `.trazo`.
    /// Cada capa se comprime individualmente en PNG sin pérdida dentro del contenedor binario little-endian.
    pub fn encode_project(&mut self) -> Result<Vec<u8>, String> {
        self.commit_pending_undo();
        let mut out = Vec::with_capacity(64 * 1024);
        out.extend_from_slice(TRAZO_PROJECT_MAGIC);
        out.extend_from_slice(&self.width.to_le_bytes());
        out.extend_from_slice(&self.height.to_le_bytes());
        out.extend_from_slice(&(self.active_layer_index as u32).to_le_bytes());
        out.extend_from_slice(&self.next_layer_id.to_le_bytes());
        out.extend_from_slice(&(self.layers.len() as u32).to_le_bytes());

        for layer in &self.layers {
            out.extend_from_slice(&layer.id.to_le_bytes());
            out.push(if layer.visible { 1 } else { 0 });
            out.extend_from_slice(&layer.opacity.to_le_bytes());

            let name_bytes = layer.name.as_bytes();
            let safe_name_len = name_bytes.len().min(256);
            out.extend_from_slice(&(safe_name_len as u32).to_le_bytes());
            out.extend_from_slice(&name_bytes[..safe_name_len]);

            let layer_png = Self::encode_rgba_as_png(self.width, self.height, &layer.pixels)?;
            out.extend_from_slice(&(layer_png.len() as u32).to_le_bytes());
            out.extend_from_slice(&layer_png);
        }
        Ok(out)
    }

    /// Restaura un proyecto `.trazo` completo conservando todas las capas y sus propiedades.
    pub fn load_project(&mut self, bytes: &[u8]) -> Result<(), String> {
        if bytes.len() < 28 || &bytes[0..8] != TRAZO_PROJECT_MAGIC {
            return Err("Cabecera de proyecto .trazo inválida".to_string());
        }
        let mut pos = 8usize;

        let read_u32 = |data: &[u8], p: &mut usize| -> Result<u32, String> {
            if *p + 4 > data.len() {
                return Err("Archivo .trazo truncado (u32)".to_string());
            }
            let val = u32::from_le_bytes([data[*p], data[*p + 1], data[*p + 2], data[*p + 3]]);
            *p += 4;
            Ok(val)
        };

        let _proj_w = read_u32(bytes, &mut pos)?;
        let _proj_h = read_u32(bytes, &mut pos)?;
        let active_idx = read_u32(bytes, &mut pos)? as usize;
        let next_id = read_u32(bytes, &mut pos)?;
        let layer_count = read_u32(bytes, &mut pos)? as usize;

        if layer_count == 0 || layer_count > 12 {
            return Err(format!("Número de capas inválido en .trazo: {layer_count}"));
        }

        let dst_w = self.width as usize;
        let dst_h = self.height as usize;
        let mut loaded_layers = Vec::with_capacity(layer_count);

        for _ in 0..layer_count {
            let id = read_u32(bytes, &mut pos)?;
            if pos >= bytes.len() {
                return Err("Archivo .trazo truncado (visible)".to_string());
            }
            let visible = bytes[pos] != 0;
            pos += 1;

            if pos + 4 > bytes.len() {
                return Err("Archivo .trazo truncado (opacity)".to_string());
            }
            let raw_opacity =
                f32::from_le_bytes([bytes[pos], bytes[pos + 1], bytes[pos + 2], bytes[pos + 3]]);
            pos += 4;
            let opacity = if raw_opacity.is_finite() {
                raw_opacity.clamp(0.0, 1.0)
            } else {
                1.0
            };

            let name_len = read_u32(bytes, &mut pos)? as usize;
            if name_len > 1024 || pos + name_len > bytes.len() {
                return Err("Nombre de capa inválido en .trazo".to_string());
            }
            let name = String::from_utf8_lossy(&bytes[pos..pos + name_len]).into_owned();
            pos += name_len;

            let png_len = read_u32(bytes, &mut pos)? as usize;
            if pos + png_len > bytes.len() {
                return Err("Datos PNG de capa truncados en .trazo".to_string());
            }
            let pixels = Self::decode_png_to_rgba(&bytes[pos..pos + png_len], dst_w, dst_h)?;
            pos += png_len;

            loaded_layers.push(Layer {
                id,
                name,
                visible,
                opacity,
                pixels,
            });
        }

        let max_id = loaded_layers.iter().map(|l| l.id).max().unwrap_or(0);
        self.layers = loaded_layers;
        self.active_layer_index = active_idx.min(self.layers.len() - 1);
        self.next_layer_id = next_id.max(max_id + 1);
        self.undo_stack.clear();
        self.redo_stack.clear();
        self.active_undo = None;
        self.refresh_base_layer_modified();
        self.mark_full_dirty();
        Ok(())
    }

    /// Decodifica bytes PNG en memoria y carga su imagen sobre la capa de dibujo principal (`Capa 1`),
    /// conservando la capa `Fondo` blanca para proyectos antiguos guardados solo como PNG.
    pub fn load_png(&mut self, png_bytes: &[u8]) -> Result<(), String> {
        let dst_w = self.width as usize;
        let dst_h = self.height as usize;
        let decoded = Self::decode_png_to_rgba(png_bytes, dst_w, dst_h)?;

        if self.layers.len() < 2 {
            let sketch_layer = Layer::new_transparent(
                self.next_layer_id,
                "Capa 1".to_string(),
                self.width,
                self.height,
            );
            self.next_layer_id += 1;
            self.layers.push(sketch_layer);
        }

        let target = &mut self.layers[1];
        target.visible = true;
        target.opacity = 1.0;
        target.pixels = decoded;

        self.undo_stack.clear();
        self.redo_stack.clear();
        self.active_undo = None;
        self.active_layer_index = 1;
        self.refresh_base_layer_modified();
        self.mark_full_dirty();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::BrushKind;

    #[test]
    fn engine_draws_and_undoes_cleanly_with_tile_patches() {
        let mut engine = DrawingEngine::new(256, 256);
        engine.begin_stroke_transaction();
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor {
                r: 220,
                g: 40,
                b: 40,
                a: 255,
            },
            radius: 6.0,
            opacity: 1.0,
            hardness: 0.9,
        };
        engine.apply_segment(20.0, 20.0, 60.0, 60.0, brush);
        engine.end_stroke_transaction();

        // Verifica que el snapshot incremental no clona toda la capa (256*256*4 = 262 KB).
        let snap_bytes = engine.undo_stack.last().unwrap().approx_bytes();
        assert!(snap_bytes < 256 * 256 * 4);

        let picked = engine.pick_color(40, 40);
        assert!(picked.r > 180);

        assert!(engine.undo());
        let picked_after_undo = engine.pick_color(40, 40);
        assert_eq!(picked_after_undo.r, 255);
        assert_eq!(picked_after_undo.g, 255);

        assert!(engine.redo());
        let picked_after_redo = engine.pick_color(40, 40);
        assert!(picked_after_redo.r > 180);
    }

    #[test]
    fn dirty_rect_batch_matches_full_recomposition() {
        let mut engine = DrawingEngine::new(160, 160);
        engine.begin_stroke_transaction();
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor {
                r: 25,
                g: 90,
                b: 210,
                a: 255,
            },
            radius: 8.0,
            opacity: 0.75,
            hardness: 0.85,
        };
        let coords = [16.0, 16.0, 64.0, 40.0, 64.0, 40.0, 112.0, 96.0];
        engine.apply_segments_batch(&coords, brush);
        engine.end_stroke_transaction();
        engine.recomposite();
        let incremental = engine.composited_buffer.clone();

        engine.mark_full_dirty();
        engine.recomposite();
        assert_eq!(incremental, engine.composited_buffer);
    }

    #[test]
    fn project_trazo_preserves_layers_opacity_and_pixels() {
        let mut engine = DrawingEngine::new(96, 96);
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor {
                r: 210,
                g: 30,
                b: 40,
                a: 255,
            },
            radius: 6.0,
            opacity: 1.0,
            hardness: 0.9,
        };
        engine.apply_dab(24.0, 24.0, brush);
        let extra_idx = engine.add_layer(Some("Tinta".to_string()));
        engine.set_layer_opacity(extra_idx, 0.55);
        engine.set_layer_visibility(extra_idx, true);
        engine.apply_dab(
            48.0,
            48.0,
            BrushConfig {
                color: RgbaColor {
                    r: 20,
                    g: 120,
                    b: 230,
                    a: 255,
                },
                ..brush
            },
        );

        let project_bytes = engine.encode_project().expect("Debe serializar .trazo");

        let mut reopened = DrawingEngine::new(96, 96);
        reopened
            .load_project(&project_bytes)
            .expect("Debe restaurar .trazo con capas");

        assert_eq!(reopened.layers.len(), 3);
        assert_eq!(reopened.layers[2].name, "Tinta");
        assert!((reopened.layers[2].opacity - 0.55).abs() < 0.01);
        assert_eq!(reopened.active_layer_index, 2);

        let red_spot = reopened.pick_color(24, 24);
        assert!(red_spot.r > 180 && red_spot.b < 60);
        let blue_spot = reopened.pick_color(48, 48);
        assert!(blue_spot.b > 150);
    }

    #[test]
    fn exclude_base_layer_renders_transparent_background_for_tracing() {
        let mut engine = DrawingEngine::new(64, 64);
        engine.set_exclude_base_layer(true);
        engine.recomposite();
        // El fondo debe quedar transparente (alfa 0) cuando no hay trazos sobre Capa 1.
        assert_eq!(engine.composited_buffer[3], 0);

        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor {
                r: 0,
                g: 0,
                b: 0,
                a: 255,
            },
            radius: 4.0,
            opacity: 1.0,
            hardness: 1.0,
        };
        engine.apply_dab(32.0, 32.0, brush);
        engine.recomposite();
        let center_idx = (32 * 64 + 32) * 4;
        assert_eq!(engine.composited_buffer[center_idx], 0);
        assert_eq!(engine.composited_buffer[center_idx + 3], 255);
    }

    #[test]
    fn copy_region_argb_matches_pixels_accurately() {
        let mut engine = DrawingEngine::new(64, 64);
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor {
                r: 12,
                g: 200,
                b: 80,
                a: 255,
            },
            radius: 3.0,
            opacity: 1.0,
            hardness: 1.0,
        };
        engine.apply_dab(20.0, 20.0, brush);
        let rect = engine.prepare_render_rect().expect("Debe haber región sucia");
        let (rx, ry, rw, rh) = rect;
        let mut argb = vec![0i32; (rw * rh) as usize];
        assert!(engine.copy_region_argb(rx, ry, rw, rh, &mut argb));

        let local_x = (20 - rx) as usize;
        let local_y = (20 - ry) as usize;
        let px = argb[local_y * (rw as usize) + local_x] as u32;
        assert_eq!((px >> 24) & 0xFF, 255);
        assert_eq!((px >> 16) & 0xFF, 12);
        assert_eq!((px >> 8) & 0xFF, 200);
        assert_eq!(px & 0xFF, 80);
    }
}
