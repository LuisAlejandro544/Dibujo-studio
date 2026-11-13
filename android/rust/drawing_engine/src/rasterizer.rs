//! Rasterizador 2D puro en Rust para TrazoStudio.
//! Contiene la lógica matemática de interpolación de trazos, sellado antialias (SDF radial),
//! borrado por resta de alfa, relleno por inundación iterativo (scanline flood fill)
//! con respaldo incremental de baldosas para Undo/Redo eficiente.

use crate::types::{BrushConfig, BrushKind, PendingUndoTransaction, RgbaColor};

/// Dibuja un segmento interpolado suavemente entre `(x0, y0)` y `(x1, y1)` sobre `pixels`.
/// Se calcula el espaciado en función del radio para evitar cortes cuando el usuario mueve el dedo rápido.
pub fn draw_segment(
    pixels: &mut [u8],
    width: u32,
    height: u32,
    x0: f32,
    y0: f32,
    x1: f32,
    y1: f32,
    brush: BrushConfig,
) {
    let dx = x1 - x0;
    let dy = y1 - y0;
    let dist = (dx * dx + dy * dy).sqrt();

    let radius = brush.radius.max(0.5);
    let step_spacing = match brush.kind {
        BrushKind::Airbrush => (radius * 0.18).max(1.0),
        BrushKind::Marker => (radius * 0.15).max(0.75),
        _ => (radius * 0.22).max(0.75),
    };

    let steps = (dist / step_spacing).ceil().max(1.0) as usize;
    for i in 0..=steps {
        let t = if steps == 0 {
            0.0
        } else {
            i as f32 / steps as f32
        };
        let cx = x0 + dx * t;
        let cy = y0 + dy * t;
        stamp_dab(pixels, width, height, cx, cy, brush);
    }
}

/// Estampa una huella circular antialias en `(cx, cy)` usando un campo de distancia firmado (SDF).
pub fn stamp_dab(
    pixels: &mut [u8],
    width: u32,
    height: u32,
    cx: f32,
    cy: f32,
    brush: BrushConfig,
) {
    let radius = brush.radius.max(0.5);
    let aa_margin = 1.25_f32;
    let outer_r = radius + aa_margin;

    let min_x = (cx - outer_r).floor().max(0.0) as i32;
    let max_x = ((cx + outer_r).ceil() as i32).min((width as i32) - 1);
    let min_y = (cy - outer_r).floor().max(0.0) as i32;
    let max_y = ((cy + outer_r).ceil() as i32).min((height as i32) - 1);

    if min_x > max_x || min_y > max_y {
        return;
    }

    let opacity = brush.opacity.clamp(0.01, 1.0);
    let hardness = brush.hardness.clamp(0.0, 1.0);
    let w_usize = width as usize;

    for py in min_y..=max_y {
        let dy = (py as f32 + 0.5) - cy;
        let dy2 = dy * dy;
        let row_offset = (py as usize) * w_usize;

        for px in min_x..=max_x {
            let dx = (px as f32 + 0.5) - cx;
            let dist = (dx * dx + dy2).sqrt();

            if dist > outer_r {
                continue;
            }

            let coverage = compute_dab_coverage(dist, radius, hardness, brush.kind);
            if coverage <= 0.001 {
                continue;
            }

            let idx = (row_offset + px as usize) * 4;
            let effective_alpha = (coverage * opacity).clamp(0.0, 1.0);

            if brush.kind == BrushKind::Eraser {
                // Borrado real reduciendo el canal alfa de la capa activa.
                let dst_a = pixels[idx + 3] as f32 / 255.0;
                let new_a = (dst_a * (1.0 - effective_alpha)).clamp(0.0, 1.0);
                pixels[idx + 3] = (new_a * 255.0).round() as u8;
                if pixels[idx + 3] == 0 {
                    pixels[idx] = 0;
                    pixels[idx + 1] = 0;
                    pixels[idx + 2] = 0;
                }
            } else {
                blend_source_over_pixel(pixels, idx, brush.color, effective_alpha);
            }
        }
    }
}

/// Calcula la cobertura de un píxel según el tipo de pincel, dureza y distancia al centro.
#[inline]
fn compute_dab_coverage(dist: f32, radius: f32, hardness: f32, kind: BrushKind) -> f32 {
    match kind {
        BrushKind::Airbrush => {
            if dist >= radius {
                0.0
            } else {
                let norm = 1.0 - (dist / radius);
                // Caída suave cuadrática con baja densidad por paso para efecto aerógrafo.
                (norm * norm) * 0.22
            }
        }
        BrushKind::Marker => {
            // Borde firme tipo rotulador con suavizado antialias de 1px.
            let edge = radius - dist;
            (edge + 0.5).clamp(0.0, 1.0)
        }
        BrushKind::Brush | BrushKind::Eraser => {
            let inner = radius * (0.2 + 0.75 * hardness);
            if dist <= inner {
                1.0
            } else if dist >= radius + 1.0 {
                0.0
            } else {
                let span = (radius + 1.0 - inner).max(0.5);
                let t = 1.0 - ((dist - inner) / span);
                // Smoothstep para transición orgánica sin dientes de sierra.
                let s = t.clamp(0.0, 1.0);
                s * s * (3.0 - 2.0 * s)
            }
        }
    }
}

/// Mezcla un color RGBA sobre el píxel destino usando Porter-Duff Source-Over.
#[inline]
pub fn blend_source_over_pixel(pixels: &mut [u8], idx: usize, src: RgbaColor, src_alpha: f32) {
    let sa = src_alpha * (src.a as f32 / 255.0);
    if sa <= 0.0 {
        return;
    }

    let da = pixels[idx + 3] as f32 / 255.0;
    let out_a = sa + da * (1.0 - sa);

    if out_a <= 0.0001 {
        pixels[idx] = 0;
        pixels[idx + 1] = 0;
        pixels[idx + 2] = 0;
        pixels[idx + 3] = 0;
        return;
    }

    let inv_out_a = 1.0 / out_a;
    let da_inv_sa = da * (1.0 - sa);

    let r = (src.r as f32 * sa + pixels[idx] as f32 * da_inv_sa) * inv_out_a;
    let g = (src.g as f32 * sa + pixels[idx + 1] as f32 * da_inv_sa) * inv_out_a;
    let b = (src.b as f32 * sa + pixels[idx + 2] as f32 * da_inv_sa) * inv_out_a;

    pixels[idx] = r.round().clamp(0.0, 255.0) as u8;
    pixels[idx + 1] = g.round().clamp(0.0, 255.0) as u8;
    pixels[idx + 2] = b.round().clamp(0.0, 255.0) as u8;
    pixels[idx + 3] = (out_a * 255.0).round().clamp(0.0, 255.0) as u8;
}

/// Relleno por inundación iterativo (scanline flood fill) seguro para móvil:
/// evita recursión profunda para prevenir StackOverflow en dispositivos ARM de 32 y 64 bits,
/// y respalda perezosamente solo las baldosas 64×64 realmente modificadas en `undo_tx`.
pub fn flood_fill(
    pixels: &mut [u8],
    width: u32,
    height: u32,
    start_x: i32,
    start_y: i32,
    fill_color: RgbaColor,
    tolerance: u8,
    mut undo_tx: Option<&mut PendingUndoTransaction>,
) -> bool {
    if start_x < 0 || start_y < 0 || start_x >= width as i32 || start_y >= height as i32 {
        return false;
    }

    let w = width as usize;
    let h = height as usize;
    let sx = start_x as usize;
    let sy = start_y as usize;
    let start_idx = (sy * w + sx) * 4;

    let target = RgbaColor {
        r: pixels[start_idx],
        g: pixels[start_idx + 1],
        b: pixels[start_idx + 2],
        a: pixels[start_idx + 3],
    };

    if color_matches(target, fill_color, 2) {
        return false;
    }

    let mut visited = vec![false; w * h];
    let mut stack: Vec<(usize, usize)> = Vec::with_capacity(1024);
    stack.push((sx, sy));

    let tol = tolerance as i32;

    while let Some((x, y)) = stack.pop() {
        let mut lx = x;
        while lx > 0
            && !visited[y * w + (lx - 1)]
            && pixel_within_tol(pixels, (y * w + (lx - 1)) * 4, target, tol)
        {
            lx -= 1;
        }

        let mut span_above = false;
        let mut span_below = false;
        let mut cx = lx;

        while cx < w {
            let cell = y * w + cx;
            if visited[cell] || !pixel_within_tol(pixels, cell * 4, target, tol) {
                break;
            }

            visited[cell] = true;
            if let Some(tx) = undo_tx.as_deref_mut() {
                tx.capture_pixel(cx, y, pixels);
            }

            let p_idx = cell * 4;
            pixels[p_idx] = fill_color.r;
            pixels[p_idx + 1] = fill_color.g;
            pixels[p_idx + 2] = fill_color.b;
            pixels[p_idx + 3] = fill_color.a;

            if y > 0 {
                let up_cell = (y - 1) * w + cx;
                let up_matches = !visited[up_cell] && pixel_within_tol(pixels, up_cell * 4, target, tol);
                if !span_above && up_matches {
                    stack.push((cx, y - 1));
                    span_above = true;
                } else if span_above && !up_matches {
                    span_above = false;
                }
            }

            if y + 1 < h {
                let down_cell = (y + 1) * w + cx;
                let down_matches =
                    !visited[down_cell] && pixel_within_tol(pixels, down_cell * 4, target, tol);
                if !span_below && down_matches {
                    stack.push((cx, y + 1));
                    span_below = true;
                } else if span_below && !down_matches {
                    span_below = false;
                }
            }

            cx += 1;
        }
    }

    true
}

#[inline]
fn color_matches(a: RgbaColor, b: RgbaColor, tol: i32) -> bool {
    (a.r as i32 - b.r as i32).abs() <= tol
        && (a.g as i32 - b.g as i32).abs() <= tol
        && (a.b as i32 - b.b as i32).abs() <= tol
        && (a.a as i32 - b.a as i32).abs() <= tol
}

#[inline]
fn pixel_within_tol(pixels: &[u8], idx: usize, target: RgbaColor, tol: i32) -> bool {
    (pixels[idx] as i32 - target.r as i32).abs() <= tol
        && (pixels[idx + 1] as i32 - target.g as i32).abs() <= tol
        && (pixels[idx + 2] as i32 - target.b as i32).abs() <= tol
        && (pixels[idx + 3] as i32 - target.a as i32).abs() <= tol
}
