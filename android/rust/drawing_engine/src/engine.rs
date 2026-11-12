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
        };
        engine.recomposite();
        engine
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

    /// Estampa un punto único (al tocar la pantalla).
    pub fn apply_dab(&mut self, x: f32, y: f32, brush: BrushConfig) {
        if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if !layer.visible {
                return;
            }
            stamp_dab(&mut layer.pixels, self.width, self.height, x, y, brush);
            self.stroke_count += 1;
            self.dirty = true;
        }
    }

    /// Dibuja un trazo interpolado entre dos coordenadas del lienzo.
    pub fn apply_segment(&mut self, x0: f32, y0: f32, x1: f32, y1: f32, brush: BrushConfig) {
        if let Some(layer) = self.layers.get_mut(self.active_layer_index) {
            if !layer.visible {
                return;
            }
            draw_segment(&mut layer.pixels, self.width, self.height, x0, y0, x1, y1, brush);
            self.dirty = true;
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
            self.dirty = true;
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
            self.dirty = true;
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
            self.dirty = true;
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
            self.dirty = true;
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
        self.dirty = true;
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
        self.dirty = true;
        true
    }

    pub fn set_layer_visibility(&mut self, index: usize, visible: bool) {
        if let Some(layer) = self.layers.get_mut(index) {
            layer.visible = visible;
            self.dirty = true;
        }
    }

    pub fn set_layer_opacity(&mut self, index: usize, opacity: f32) {
        if let Some(layer) = self.layers.get_mut(index) {
            layer.opacity = opacity.clamp(0.0, 1.0);
            self.dirty = true;
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
        self.dirty = true;
        true
    }

    /// Recompone todas las capas visibles dentro de `composited_buffer` (RGBA8888).
    pub fn recomposite(&mut self) {
        self.composited_buffer.fill(0);
        let pixel_count = (self.width as usize) * (self.height as usize);

        for layer in &self.layers {
            if !layer.visible || layer.opacity <= 0.001 {
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
                        self.composited_buffer[i] =
                            ((layer.pixels[i] as f32 * sa + self.composited_buffer[i] as f32 * da_inv_sa) * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                        self.composited_buffer[i + 1] =
                            ((layer.pixels[i + 1] as f32 * sa + self.composited_buffer[i + 1] as f32 * da_inv_sa) * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                        self.composited_buffer[i + 2] =
                            ((layer.pixels[i + 2] as f32 * sa + self.composited_buffer[i + 2] as f32 * da_inv_sa) * inv_out)
                                .round()
                                .clamp(0.0, 255.0) as u8;
                        self.composited_buffer[i + 3] = (out_a * 255.0).round().clamp(0.0, 255.0) as u8;
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
    fn engine_encodes_valid_png_header() {
        let mut engine = DrawingEngine::new(64, 64);
        let bytes = engine.encode_png(true).expect("PNG debe codificar sin errores");
        assert!(bytes.starts_with(&[0x89, b'P', b'N', b'G']));
    }
}
