//! Núcleo principal de estado y composición multicapa del motor de dibujo en Rust.
//! Gestiona las capas, la pila Undo/Redo, la composición final RGBA8888 y la exportación a PNG
//! usando el crate `png` (Apache-2.0 / MIT).

use std::io::Cursor;

use crate::rasterizer::{draw_segment, flood_fill, stamp_dab};
use crate::types::{BrushConfig, HistorySnapshot, Layer, RgbaColor};

const MAX_UNDO_HISTORY: usize = 25;

/// Estado completo de una sesión de lienzo en memoria nativa.
pub struct DrawingEngine {
    pub width: u32,
    pub height: u32,
    pub layers: Vec<Layer>,
    pub active_layer_index: usize,
    pub next_layer_id: u32,
    pub undo_stack: Vec<HistorySnapshot>,
    pub redo_stack: Vec<HistorySnapshot>,
    pub composited_buffer: Vec<u8>,
    pub stroke_count: u64,
    pub dirty: bool,
    pub dirty_rect: Option<(u32, u32, u32, u32)>,
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
            composited_buffer: vec![0u8; buffer_len],
            stroke_count: 0,
            dirty: true,
            dirty_rect: Some((0, 0, safe_w - 1, safe_h - 1)),
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

    /// Guarda una instantánea de la capa activa antes de iniciar un trazo o relleno.
    pub fn begin_stroke_transaction(&mut self) {
        if let Some(layer) = self.layers.get(self.active_layer_index) {
            if self.undo_stack.len() >= MAX_UNDO_HISTORY {
                self.undo_stack.remove(0);
            }
            self.undo_stack.push(HistorySnapshot {
                layer_index: self.active_layer_index,
                pixels: layer.pixels.clone(),
            });
            self.redo_stack.clear();
        }
    }

    /// Estampa un punto único (al tocar la pantalla) y marca solo su caja delimitadora como sucia.
    pub fn apply_dab(&mut self, x: f32, y: f32, brush: BrushConfig) {
        if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if !layer.visible {
                return;
            }
            stamp_dab(&mut layer.pixels, self.width, self.height, x, y, brush);
            self.stroke_count += 1;
            let outer = brush.radius.max(0.5) + 2.0;
            let x0 = (x - outer).floor().max(0.0) as u32;
            let y0 = (y - outer).floor().max(0.0) as u32;
            let x1 = (x + outer).ceil().max(0.0) as u32;
            let y1 = (y + outer).ceil().max(0.0) as u32;
            self.mark_dirty_rect(x0, y0, x1, y1);
        }
    }

    /// Dibuja un trazo interpolado entre dos coordenadas del lienzo y actualiza solo la región afectada.
    pub fn apply_segment(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, brush: BrushConfig) {
        if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if !layer.visible {
                return;
            }
            draw_segment(&mut layer.pixels, self.width, self.height, x0, y0, x1, y1, brush);
            let outer = brush.radius.max(0.5) + 2.0;
            let min_x = (x0.min(x1) - outer).floor().max(0.0) as u32;
            let min_y = (y0.min(y1) - outer).floor().max(0.0) as u32;
            let max_x = (x0.max(x1) + outer).ceil().max(0.0) as u32;
            let max_y = (y0.max(y1) + outer).ceil().max(0.0) as u32;
            self.mark_dirty_rect(min_x, min_y, max_x, max_y);
        }
    }

    /// Rasteriza múltiples segmentos `[x0, y0, x1, y1, ...]` en una sola llamada nativa.
    pub fn apply_segments_batch(&mut self, coords: &[f32], brush: BrushConfig) {
        let Some(layer) = self.layers.get_mut(self.active_layer_index) else {
            return;
        };
        if !layer.visible || coords.len() < 4 {
            return;
        }

        let outer = brush.radius.max(0.5) + 2.0;
        let mut min_fx = f32::MAX;
        let mut min_fy = f32::MAX;
        let mut max_fx = f32::MIN;
        let mut max_fy = f32::MIN;

        for seg in coords.chunks_exact(4) {
            let (x0, y0, x1, y1) = (seg[0], seg[1], seg[2], seg[3]);
            draw_segment(&mut layer.pixels, self.width, self.height, x0, y0, x1, y1, brush);
            min_fx = min_fx.min(x0.min(x1));
            min_fy = min_fy.min(y0.min(y1));
            max_fx = max_fx.max(x0.max(x1));
            max_fy = max_fy.max(y0.max(y1));
        }

        if min_fx <= max_fx && min_fy <= max_fy {
            let min_x = (min_fx - outer).floor().max(0.0) as u32;
            let min_y = (min_fy - outer).floor().max(0.0) as u32;
            let max_x = (max_fx + outer).ceil().max(0.0) as u32;
            let max_y = (max_fy + outer).ceil().max(0.0) as u32;
            self.mark_dirty_rect(min_x, min_y, max_x, max_y);
        }
    }

    /// Aplica relleno por inundación sobre la capa activa.
    pub fn apply_flood_fill(&mut self, x: i32, y: i32, color: RgbaColor, tolerance: u8) -> bool {
        self.begin_stroke_transaction();
        let changed = if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if !layer.visible {
                false
            } else {
                flood_fill(&mut layer.pixels, self.width, self.height, x, y, color, tolerance)
            }
        } else {
            false
        };

        if changed {
            self.stroke_count += 1;
            self.mark_full_dirty();
        } else {
            // Si no hubo cambios, revertimos la instantánea vacía para no ensuciar el historial.
            self.undo_stack.pop();
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

    /// Limpia todos los píxeles de la capa activa (dejándola transparente o blanca si es la capa 0).
    pub fn clear_active_layer(&mut self) {
        self.begin_stroke_transaction();
        let is_base = self.active_layer_index == 0;
        if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if is_base {
                for chunk in layer.pixels.chunks_exact_mut(4) {
                    chunk[0] = 255;
                    chunk[1] = 255;
                    chunk[2] = 255;
                    chunk[3] = 255;
                }
            } else {
                layer.pixels.fill(0);
            }
            self.mark_full_dirty();
        }
    }

    /// Deshace la última operación en la capa correspondiente.
    pub fn undo(&mut self) -> bool {
        let Some(snapshot) = self.undo_stack.pop() else {
            return false;
        };
        if let Some(layer) = self.layers.get_mut(snapshot.layer_index) {
            let current = std::mem::replace(&mut layer.pixels, snapshot.pixels);
            self.redo_stack.push(HistorySnapshot {
                layer_index: snapshot.layer_index,
                pixels: current,
            });
            self.active_layer_index = snapshot.layer_index;
            self.mark_full_dirty();
            true
        } else {
            false
        }
    }

    /// Rehace una operación deshecha previamente.
    pub fn redo(&mut self) -> bool {
        let Some(snapshot) = self.redo_stack.pop() else {
            return false;
        };
        if let Some(layer) = self.layers.get_mut(snapshot.layer_index) {
            let current = std::mem::replace(&mut layer.pixels, snapshot.pixels);
            self.undo_stack.push(HistorySnapshot {
                layer_index: snapshot.layer_index,
                pixels: current,
            });
            self.active_layer_index = snapshot.layer_index;
            self.mark_full_dirty();
            true
        } else {
            false
        }
    }

    /// Agrega una nueva capa transparente encima de las existentes y la selecciona.
    pub fn add_layer(&mut self, custom_name: Option<String>) -> usize {
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
        if self.layers.len() <= 1 || index >= self.layers.len() {
            return false;
        }
        self.layers.remove(index);
        self.undo_stack.clear();
        self.redo_stack.clear();
        if self.active_layer_index >= self.layers.len() {
            self.active_layer_index = self.layers.len() - 1;
        }
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
            self.active_layer_index = index;
        }
    }

    /// Combina la capa seleccionada hacia la capa inmediatamente inferior.
    pub fn merge_layer_down(&mut self, index: usize) -> bool {
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
        self.mark_full_dirty();
        true
    }

    /// Recompone únicamente la región sucia (`dirty_rect`) de las capas visibles dentro de `composited_buffer`.
    pub fn recomposite(&mut self) {
        if !self.dirty && self.dirty_rect.is_none() {
            return;
        }
        if self.width == 0 || self.height == 0 {
            self.dirty = false;
            self.dirty_rect = None;
            return;
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

        let first_visible = self
            .layers
            .iter()
            .position(|l| l.visible && l.opacity > 0.001);

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

        let mut png_bytes = Vec::new();
        {
            let cursor = Cursor::new(&mut png_bytes);
            let mut encoder = png::Encoder::new(cursor, self.width, self.height);
            encoder.set_color(png::ColorType::Rgba);
            encoder.set_depth(png::BitDepth::Eight);
            let mut writer = encoder
                .write_header()
                .map_err(|e| format!("Error al escribir cabecera PNG: {e}"))?;
            writer
                .write_image_data(&export_buf)
                .map_err(|e| format!("Error al codificar datos PNG: {e}"))?;
        }
        Ok(png_bytes)
    }

    /// Decodifica bytes PNG en memoria y carga su imagen sobre la capa de dibujo principal (`Capa 1`),
    /// conservando la capa `Fondo` blanca para permitir seguir dibujando o borrando trazos guardados.
    pub fn load_png(&mut self, png_bytes: &[u8]) -> Result<(), String> {
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
        let dst_w = self.width as usize;
        let dst_h = self.height as usize;

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
        target.pixels.fill(0);

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
                if s_idx + channels > src.len() || d_idx + 4 > target.pixels.len() {
                    continue;
                }
                match channels {
                    4 => {
                        target.pixels[d_idx] = src[s_idx];
                        target.pixels[d_idx + 1] = src[s_idx + 1];
                        target.pixels[d_idx + 2] = src[s_idx + 2];
                        target.pixels[d_idx + 3] = src[s_idx + 3];
                    }
                    3 => {
                        target.pixels[d_idx] = src[s_idx];
                        target.pixels[d_idx + 1] = src[s_idx + 1];
                        target.pixels[d_idx + 2] = src[s_idx + 2];
                        target.pixels[d_idx + 3] = 255;
                    }
                    2 => {
                        let g = src[s_idx];
                        target.pixels[d_idx] = g;
                        target.pixels[d_idx + 1] = g;
                        target.pixels[d_idx + 2] = g;
                        target.pixels[d_idx + 3] = src[s_idx + 1];
                    }
                    _ => {
                        let g = src[s_idx];
                        target.pixels[d_idx] = g;
                        target.pixels[d_idx + 1] = g;
                        target.pixels[d_idx + 2] = g;
                        target.pixels[d_idx + 3] = 255;
                    }
                }
            }
        }

        self.undo_stack.clear();
        self.redo_stack.clear();
        self.active_layer_index = 1;
        self.mark_full_dirty();
        self.recomposite();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::types::BrushKind;

    #[test]
    fn engine_draws_and_undoes_cleanly() {
        let mut engine = DrawingEngine::new(128, 128);
        engine.begin_stroke_transaction();
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor { r: 220, g: 40, b: 40, a: 255 },
            radius: 6.0,
            opacity: 1.0,
            hardness: 0.9,
        };
        engine.apply_segment(20.0, 20.0, 60.0, 60.0, brush);
        let picked = engine.pick_color(40, 40);
        assert!(picked.r > 180);

        assert!(engine.undo());
        let picked_after_undo = engine.pick_color(40, 40);
        assert_eq!(picked_after_undo.r, 255);
        assert_eq!(picked_after_undo.g, 255);
    }

    #[test]
    fn dirty_rect_batch_matches_full_recomposition() {
        let mut engine = DrawingEngine::new(160, 160);
        engine.begin_stroke_transaction();
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor { r: 25, g: 90, b: 210, a: 255 },
            radius: 8.0,
            opacity: 0.75,
            hardness: 0.85,
        };
        let coords = [16.0, 16.0, 64.0, 40.0, 64.0, 40.0, 112.0, 96.0];
        engine.apply_segments_batch(&coords, brush);
        engine.recomposite();
        let incremental = engine.composited_buffer.clone();

        engine.mark_full_dirty();
        engine.recomposite();
        assert_eq!(incremental, engine.composited_buffer);
    }

    #[test]
    fn engine_encodes_valid_png_header() {
        let mut engine = DrawingEngine::new(64, 64);
        let bytes = engine.encode_png(true).expect("PNG debe codificar sin errores");
        assert!(bytes.starts_with(&[0x89, b'P', b'N', b'G']));
    }

    #[test]
    fn engine_saves_and_reloads_png_pixels() {
        let mut engine = DrawingEngine::new(96, 96);
        let brush = BrushConfig {
            kind: BrushKind::Brush,
            color: RgbaColor { r: 210, g: 30, b: 40, a: 255 },
            radius: 8.0,
            opacity: 1.0,
            hardness: 0.9,
        };
        engine.apply_dab(48.0, 48.0, brush);
        let bytes = engine.encode_png(true).expect("Debe exportar PNG");

        let mut reopened = DrawingEngine::new(96, 96);
        reopened.load_png(&bytes).expect("Debe recargar PNG guardado");
        let center = reopened.pick_color(48, 48);
        assert!(center.r > 180 && center.g < 60);
    }
}
