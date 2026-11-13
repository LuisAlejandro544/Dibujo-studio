//! Módulo de análisis inteligente local ("Asistente IA de Calco") en Rust.
//!
//! Analiza los píxeles de la imagen de calco directamente en código nativo (sin conexión ni claves API):
//! 1. Detecta el estilo de la imagen (contorno definido, boceto suave o fotografía/pintura).
//! 2. Mide el grosor perpendicular real de los trazos/líneas en la imagen y lo convierte
//!    al radio exacto (`radiusPx`) correspondiente al tamaño actual del calco sobre el lienzo.
//! 3. Extrae mediante cuantización K-Means++ el color de tinta/contorno y la paleta de colores
//!    principales para colorear el dibujo.

use crate::types::RgbaColor;

/// Resultado estructurado del análisis inteligente de una imagen de calco.
#[derive(Debug, Clone)]
pub struct TracingAiReport {
    /// 0 = contorno definido, 1 = boceto suave, 2 = fotografía/pintura, 3 = lienzo claro.
    pub style_code: u8,
    /// Radio recomendado en píxeles de lienzo para detalles finos (ojos, pestañas, interior).
    pub fine_radius_px: f32,
    /// Radio recomendado en píxeles de lienzo para el contorno principal.
    pub main_radius_px: f32,
    /// Radio recomendado en píxeles de lienzo para trazos gruesos, sombras o rellenos rápidos.
    pub bold_radius_px: f32,
    /// Dureza recomendada del pincel (0.0 .. 1.0).
    pub recommended_hardness: f32,
    /// Tolerancia sugerida para la herramienta Bote de Relleno.
    pub recommended_fill_tolerance: u8,
    /// Color principal detectado para el contorno / tinta.
    pub outline_color: RgbaColor,
    /// Porcentaje aproximado de píxeles de línea frente al área total.
    pub ink_ratio_pct: u8,
    /// Paleta extraída ordenada por relevancia (contorno + colores para colorear).
    pub palette: Vec<PaletteEntry>,
}

/// Entrada de color sugerida para trazar o colorear el dibujo.
#[derive(Debug, Clone)]
pub struct PaletteEntry {
    pub color: RgbaColor,
    pub coverage_pct: u8,
    pub is_outline: bool,
    /// 0 = Contorno/Tinta, 1 = Color principal, 2 = Secundario, 3 = Fondo/Base, 4 = Detalle/Acento.
    pub role_code: u8,
}

impl TracingAiReport {
    /// Serializa el reporte en un manifiesto compacto de texto para cruzar JNI sin clases complejas.
    pub fn to_manifest(&self) -> String {
        let mut out = format!(
            "META|{}|{:.1}|{:.1}|{:.1}|{:.2}|{}|{}|{}",
            self.style_code,
            self.fine_radius_px,
            self.main_radius_px,
            self.bold_radius_px,
            self.recommended_hardness,
            self.recommended_fill_tolerance,
            self.outline_color.to_argb_int(),
            self.ink_ratio_pct
        );
        for entry in &self.palette {
            out.push('\n');
            out.push_str(&format!(
                "COLOR|{}|{}|{}|{}",
                entry.color.to_argb_int(),
                entry.coverage_pct,
                if entry.is_outline { 1 } else { 0 },
                entry.role_code
            ));
        }
        out
    }
}

#[inline]
fn argb_to_rgb_on_white(argb: i32) -> (u8, u8, u8, u8) {
    let u = argb as u32;
    let a = ((u >> 24) & 0xFF) as u8;
    let r = ((u >> 16) & 0xFF) as u8;
    let g = ((u >> 8) & 0xFF) as u8;
    let b = (u & 0xFF) as u8;
    if a >= 250 {
        return (r, g, b, a);
    }
    if a <= 8 {
        return (255, 255, 255, a);
    }
    let af = (a as f32) / 255.0;
    let inv = 1.0 - af;
    let cr = ((r as f32) * af + 255.0 * inv).round().clamp(0.0, 255.0) as u8;
    let cg = ((g as f32) * af + 255.0 * inv).round().clamp(0.0, 255.0) as u8;
    let cb = ((b as f32) * af + 255.0 * inv).round().clamp(0.0, 255.0) as u8;
    (cr, cg, cb, a)
}

#[inline]
fn rgb_luma(r: u8, g: u8, b: u8) -> f32 {
    0.299 * (r as f32) + 0.587 * (g as f32) + 0.114 * (b as f32)
}

#[inline]
fn rgb_chroma(r: u8, g: u8, b: u8) -> u8 {
    let max_c = r.max(g).max(b);
    let min_c = r.min(g).min(b);
    max_c - min_c
}

#[inline]
fn color_dist_sq(a: (f32, f32, f32), b: (f32, f32, f32)) -> f32 {
    let dr = a.0 - b.0;
    let dg = a.1 - b.1;
    let db = a.2 - b.2;
    // Ponderación perceptual aproximada (verde más sensible para el ojo humano).
    2.0 * dr * dr + 4.0 * dg * dg + 3.0 * db * db
}

/// Analiza la imagen de calco (`argb_pixels` de tamaño `width × height`) proyectada sobre un área
/// renderizada de ancho `rendered_width_on_canvas` píxeles del lienzo real.
pub fn analyze_tracing_image(
    argb_pixels: &[i32],
    width: u32,
    height: u32,
    rendered_width_on_canvas: f32,
) -> TracingAiReport {
    let w = width as usize;
    let h = height as usize;
    let expected = w.saturating_mul(h);

    if w < 4 || h < 4 || argb_pixels.len() < expected {
        return default_report();
    }

    let canvas_px_per_img_px = (rendered_width_on_canvas.max(64.0) / (w as f32)).clamp(0.1, 32.0);

    // 1. Precalcular RGB sobre fondo blanco, luminancia y croma.
    let mut rgb_buf = Vec::with_capacity(expected);
    let mut luma = Vec::with_capacity(expected);
    let mut luma_hist = [0u32; 256];
    let mut opaque_count: usize = 0;

    for &argb in &argb_pixels[..expected] {
        let (r, g, b, a) = argb_to_rgb_on_white(argb);
        rgb_buf.push((r, g, b, a));
        let l = rgb_luma(r, g, b);
        luma.push(l);
        if a > 24 {
            opaque_count += 1;
            let bin = (l.round() as i32).clamp(0, 255) as usize;
            luma_hist[bin] += 1;
        }
    }

    if opaque_count < 16 {
        return default_report();
    }

    // Estimar luminancia del papel/fondo (percentil 80 de píxeles opacos).
    let target_p80 = (opaque_count * 80) / 100;
    let mut acc = 0usize;
    let mut bg_luma = 245.0_f32;
    for (bin, &cnt) in luma_hist.iter().enumerate() {
        acc += cnt as usize;
        if acc >= target_p80 {
            bg_luma = (bin as f32).max(120.0);
            break;
        }
    }

    // 2. Calcular gradiente de Sobel y detectar píxeles de trazo/tinta vs bordes.
    let mut grad = vec![0.0_f32; expected];
    let mut grad_sum = 0.0_f64;
    let mut grad_samples = 0usize;

    for y in 1..(h - 1) {
        let row = y * w;
        for x in 1..(w - 1) {
            let tl = luma[row - w + x - 1];
            let tc = luma[row - w + x];
            let tr = luma[row - w + x + 1];
            let ml = luma[row + x - 1];
            let mr = luma[row + x + 1];
            let bl = luma[row + w + x - 1];
            let bc = luma[row + w + x];
            let br = luma[row + w + x + 1];

            let gx = -tl + tr - 2.0 * ml + 2.0 * mr - bl + br;
            let gy = -tl - 2.0 * tc - tr + bl + 2.0 * bc + br;
            let gmag = (gx * gx + gy * gy).sqrt() * 0.25;
            grad[row + x] = gmag;
            grad_sum += gmag as f64;
            grad_samples += 1;
        }
    }

    let mean_grad = if grad_samples > 0 {
        (grad_sum / (grad_samples as f64)) as f32
    } else {
        12.0
    };

    // Máscara de tinta oscura: píxeles oscuros respecto al fondo y de saturación baja/media,
    // o muy oscuros (líneas negras, sepia o azul oscuro de boceto).
    let dark_cutoff = (bg_luma * 0.58).clamp(55.0, 155.0);
    let mut ink_mask = vec![false; expected];
    let mut ink_count = 0usize;
    let mut ink_r_sum = 0u64;
    let mut ink_g_sum = 0u64;
    let mut ink_b_sum = 0u64;

    for i in 0..expected {
        let (r, g, b, a) = rgb_buf[i];
        if a <= 32 {
            continue;
        }
        let l = luma[i];
        let chroma = rgb_chroma(r, g, b);
        let is_dark_line = l <= dark_cutoff && (chroma <= 75 || l <= 85.0);
        if is_dark_line {
            ink_mask[i] = true;
            ink_count += 1;
            ink_r_sum += r as u64;
            ink_g_sum += g as u64;
            ink_b_sum += b as u64;
        }
    }

    let ink_ratio = (ink_count as f32) / (opaque_count.max(1) as f32);

    // Si hay muy pocos píxeles de tinta oscura (< 0.4%), usar detección de crestas de borde Sobel
    // (ideal para fotografías o ilustraciones pintadas sin contorno negro).
    let edge_threshold = (mean_grad * 2.2).clamp(18.0, 58.0);
    let mut edge_mask = vec![false; expected];
    let mut edge_count = 0usize;
    for i in 0..expected {
        if rgb_buf[i].3 > 32 && grad[i] >= edge_threshold {
            edge_mask[i] = true;
            edge_count += 1;
        }
    }

    let use_ink_mask = ink_count >= 24 && ink_ratio <= 0.45;
    let active_mask = if use_ink_mask { &ink_mask } else { &edge_mask };

    // 3. Medir grosor perpendicular de los trazos en 4 direcciones (0°, 90°, 45°, 135°).
    let max_scan = ((w.min(h) as f32) * 0.10).round().clamp(16.0, 56.0) as i32;
    let max_outline_width_img = (max_scan as f32) * 1.45;
    let mut widths_img_px: Vec<f32> = Vec::with_capacity(4096);

    let step = ((w * h) / 48_000).max(1);
    let mut idx = 0usize;
    while idx < expected {
        if active_mask[idx] {
            let x = (idx % w) as i32;
            let y = (idx / w) as i32;

            let span_h = measure_axis_span(active_mask, w, h, x, y, 1, 0, max_scan) as f32;
            let span_v = measure_axis_span(active_mask, w, h, x, y, 0, 1, max_scan) as f32;
            let span_d1 =
                (measure_axis_span(active_mask, w, h, x, y, 1, 1, max_scan) as f32) * 1.4142;
            let span_d2 =
                (measure_axis_span(active_mask, w, h, x, y, 1, -1, max_scan) as f32) * 1.4142;

            let min_span = span_h.min(span_v).min(span_d1).min(span_d2);
            if min_span >= 1.0 && min_span < max_outline_width_img {
                widths_img_px.push(min_span);
            }
        }
        idx += step;
    }

    // Ordenar grosores medidos para calcular percentiles reales (fino p20, principal p50, grueso p85).
    widths_img_px.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

    let (p20_img, p50_img, p85_img) = if widths_img_px.len() >= 12 {
        let n = widths_img_px.len();
        let q20 = widths_img_px[(n * 20) / 100];
        let q50 = widths_img_px[(n * 50) / 100];
        let q85 = widths_img_px[(n * 85) / 100];
        (q20, q50, q85)
    } else {
        // Respaldo geométrico proporcional si la imagen casi no tiene líneas definidas.
        let fallback_d = (w.min(h) as f32 * 0.008).clamp(2.0, 10.0);
        (fallback_d * 0.65, fallback_d, fallback_d * 1.75)
    };

    // Convertir diámetro en imagen -> radio en píxeles reales del lienzo (`BrushSettings.radiusPx`).
    let fine_diam_canvas = p20_img * canvas_px_per_img_px;
    let main_diam_canvas = p50_img * canvas_px_per_img_px;
    let bold_diam_canvas = (p85_img * canvas_px_per_img_px).max(main_diam_canvas * 1.45);

    let main_radius_px = (main_diam_canvas * 0.5).clamp(2.0, 48.0);
    let fine_radius_px = (fine_diam_canvas * 0.5)
        .min(main_radius_px * 0.78)
        .clamp(1.5, 32.0);
    let bold_radius_px = (bold_diam_canvas * 0.5)
        .max(main_radius_px * 1.45)
        .clamp(3.0, 80.0);

    // Determinar estilo de imagen y parámetros recomendados de dureza y tolerancia de relleno.
    let style_code = if widths_img_px.len() < 12 && edge_count < 40 {
        3 // Lienzo claro / pocos trazos
    } else if use_ink_mask && ink_ratio >= 0.015 {
        0 // Ilustración con contorno definido
    } else if use_ink_mask || (edge_count > 60 && mean_grad < 18.0) {
        1 // Boceto o trazos suaves
    } else {
        2 // Fotografía o pintura sin contorno
    };

    let (recommended_hardness, recommended_fill_tolerance) = match style_code {
        0 => (0.90, 26),
        1 => (0.76, 36),
        2 => (0.68, 42),
        _ => (0.85, 28),
    };

    let outline_color = if ink_count > 0 {
        RgbaColor {
            r: (ink_r_sum / (ink_count as u64)).min(255) as u8,
            g: (ink_g_sum / (ink_count as u64)).min(255) as u8,
            b: (ink_b_sum / (ink_count as u64)).min(255) as u8,
            a: 255,
        }
    } else {
        RgbaColor {
            r: 24,
            g: 24,
            b: 27,
            a: 255,
        }
    };

    // 4. Extraer paleta de colores para colorear mediante K-Means++ determinista.
    let palette = extract_coloring_palette(&rgb_buf, &luma, &ink_mask, outline_color, ink_ratio);

    let ink_ratio_pct = (ink_ratio * 100.0).round().clamp(0.0, 100.0) as u8;

    TracingAiReport {
        style_code,
        fine_radius_px,
        main_radius_px,
        bold_radius_px,
        recommended_hardness,
        recommended_fill_tolerance,
        outline_color,
        ink_ratio_pct,
        palette,
    }
}

/// Mide cuántos píxeles consecutivos pertenecen a la máscara a lo largo del eje `(dx, dy)`
/// pasando por `(x, y)` en ambos sentidos.
fn measure_axis_span(
    mask: &[bool],
    w: usize,
    h: usize,
    x: i32,
    y: i32,
    dx: i32,
    dy: i32,
    max_scan: i32,
) -> i32 {
    let mut count = 1;
    let wi = w as i32;
    let hi = h as i32;

    for dir in [-1, 1] {
        for step in 1..=max_scan {
            let nx = x + dx * dir * step;
            let ny = y + dy * dir * step;
            if nx < 0 || nx >= wi || ny < 0 || ny >= hi {
                break;
            }
            if mask[(ny as usize) * w + (nx as usize)] {
                count += 1;
            } else {
                break;
            }
        }
    }
    count
}

/// Agrupa los píxeles coloreados (ignorando papel blanco puro y líneas de tinta ya representadas)
/// mediante inicialización determinista por distancia máxima + 8 iteraciones de K-Means.
fn extract_coloring_palette(
    rgb_buf: &[(u8, u8, u8, u8)],
    luma: &[f32],
    ink_mask: &[bool],
    outline_color: RgbaColor,
    ink_ratio: f32,
) -> Vec<PaletteEntry> {
    let mut entries = Vec::new();
    let ink_pct = (ink_ratio * 100.0).round().clamp(1.0, 45.0) as u8;

    if ink_ratio >= 0.004 {
        entries.push(PaletteEntry {
            color: outline_color,
            coverage_pct: ink_pct,
            is_outline: true,
            role_code: 0,
        });
    }

    // Recopilar muestras de color (excluir blanco puro de papel y tinta ya capturada).
    let mut samples: Vec<(f32, f32, f32)> = Vec::with_capacity(3200);
    let stride = (rgb_buf.len() / 3200).max(1);
    let mut i = 0usize;
    while i < rgb_buf.len() {
        let (r, g, b, a) = rgb_buf[i];
        if a > 40 && !ink_mask[i] {
            let l = luma[i];
            let chroma = rgb_chroma(r, g, b);
            // Excluir papel blanco casi puro para no desperdiciar lugares en blanco de hoja.
            if !(l > 240.0 && chroma < 16) {
                samples.push((r as f32, g as f32, b as f32));
            }
        }
        i += stride;
    }

    // Si es un dibujo en blanco y negro / solo líneas sin relleno de color, garantizar al menos un color de tinta.
    if samples.len() < 12 {
        if entries.is_empty() {
            entries.push(PaletteEntry {
                color: outline_color,
                coverage_pct: 100,
                is_outline: true,
                role_code: 0,
            });
        }
        return entries;
    }

    let k = 7usize.min(samples.len());
    let mut centroids: Vec<(f32, f32, f32)> = Vec::with_capacity(k);

    // Primer centroide: muestra con mayor saturación/croma (evita elegir gris neutro primero).
    let mut best_first = 0usize;
    let mut best_chroma = -1.0_f32;
    for (idx, &(r, g, b)) in samples.iter().enumerate() {
        let max_c = r.max(g).max(b);
        let min_c = r.min(g).min(b);
        let c = max_c - min_c;
        if c > best_chroma {
            best_chroma = c;
            best_first = idx;
        }
    }
    centroids.push(samples[best_first]);

    // Centros siguientes deterministas (farthest-point sampling: garantiza capturar acentos pequeños como ojos o zapatos).
    let mut min_dists = vec![f32::MAX; samples.len()];
    while centroids.len() < k {
        let last = *centroids.last().unwrap();
        let mut farthest_idx = 0usize;
        let mut farthest_dist = -1.0_f32;
        for (idx, &s) in samples.iter().enumerate() {
            let d = color_dist_sq(s, last);
            if d < min_dists[idx] {
                min_dists[idx] = d;
            }
            if min_dists[idx] > farthest_dist {
                farthest_dist = min_dists[idx];
                farthest_idx = idx;
            }
        }
        if farthest_dist < 1800.0 {
            break;
        }
        centroids.push(samples[farthest_idx]);
    }

    let actual_k = centroids.len();
    let mut sums = vec![(0.0_f32, 0.0_f32, 0.0_f32, 0usize); actual_k];

    for _iter in 0..8 {
        for slot in &mut sums {
            *slot = (0.0, 0.0, 0.0, 0);
        }
        for &s in &samples {
            let mut best_c = 0usize;
            let mut best_d = f32::MAX;
            for (c_idx, &cent) in centroids.iter().enumerate() {
                let d = color_dist_sq(s, cent);
                if d < best_d {
                    best_d = d;
                    best_c = c_idx;
                }
            }
            sums[best_c].0 += s.0;
            sums[best_c].1 += s.1;
            sums[best_c].2 += s.2;
            sums[best_c].3 += 1;
        }
        for (c_idx, &(sr, sg, sb, cnt)) in sums.iter().enumerate() {
            if cnt > 0 {
                let f = cnt as f32;
                centroids[c_idx] = (sr / f, sg / f, sb / f);
            }
        }
    }

    // Contar cobertura final de cada clúster.
    let mut counts = vec![0usize; actual_k];
    for &s in &samples {
        let mut best_c = 0usize;
        let mut best_d = f32::MAX;
        for (c_idx, &cent) in centroids.iter().enumerate() {
            let d = color_dist_sq(s, cent);
            if d < best_d {
                best_d = d;
                best_c = c_idx;
            }
        }
        counts[best_c] += 1;
    }

    // Fusionar colores casi idénticos o demasiado cercanos al color de contorno ya agregado.
    let mut merged: Vec<(RgbaColor, usize)> = Vec::new();
    let mut order: Vec<usize> = (0..actual_k).collect();
    order.sort_by(|&a, &b| counts[b].cmp(&counts[a]));

    let outline_f = (
        outline_color.r as f32,
        outline_color.g as f32,
        outline_color.b as f32,
    );

    for idx in order {
        let cnt = counts[idx];
        if cnt == 0 {
            continue;
        }
        let c = centroids[idx];
        if ink_ratio >= 0.004 && color_dist_sq(c, outline_f) < 3200.0 {
            continue;
        }
        let rgba = RgbaColor {
            r: c.0.round().clamp(0.0, 255.0) as u8,
            g: c.1.round().clamp(0.0, 255.0) as u8,
            b: c.2.round().clamp(0.0, 255.0) as u8,
            a: 255,
        };

        let mut absorbed = false;
        for existing in &mut merged {
            let ef = (existing.0.r as f32, existing.0.g as f32, existing.0.b as f32);
            if color_dist_sq(c, ef) < 3600.0 {
                existing.1 += cnt;
                absorbed = true;
                break;
            }
        }
        if !absorbed {
            merged.push((rgba, cnt));
        }
    }

    merged.sort_by(|a, b| b.1.cmp(&a.1));
    let total_samples = samples.len().max(1);

    for (rank, (rgba, cnt)) in merged.into_iter().take(7).enumerate() {
        let pct = ((cnt as f32 / total_samples as f32) * 100.0)
            .round()
            .clamp(1.0, 99.0) as u8;
        let l = rgb_luma(rgba.r, rgba.g, rgba.b);
        let chroma = rgb_chroma(rgba.r, rgba.g, rgba.b);
        let role_code = if l > 210.0 && chroma < 25 {
            3 // Fondo / Base clara
        } else if rank == 0 {
            1 // Color principal
        } else if rank == 1 || pct >= 12 {
            2 // Color secundario
        } else {
            4 // Detalle / Acento
        };
        entries.push(PaletteEntry {
            color: rgba,
            coverage_pct: pct,
            is_outline: false,
            role_code,
        });
    }

    if entries.is_empty() {
        entries.push(PaletteEntry {
            color: outline_color,
            coverage_pct: 100,
            is_outline: true,
            role_code: 0,
        });
    }

    entries
}

fn default_report() -> TracingAiReport {
    TracingAiReport {
        style_code: 3,
        fine_radius_px: 3.0,
        main_radius_px: 6.5,
        bold_radius_px: 14.0,
        recommended_hardness: 0.88,
        recommended_fill_tolerance: 28,
        outline_color: RgbaColor {
            r: 20,
            g: 20,
            b: 20,
            a: 255,
        },
        ink_ratio_pct: 0,
        palette: vec![PaletteEntry {
            color: RgbaColor {
                r: 20,
                g: 20,
                b: 20,
                a: 255,
            },
            coverage_pct: 100,
            is_outline: true,
            role_code: 0,
        }],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pack_argb(r: u8, g: u8, b: u8) -> i32 {
        ((0xFFu32 << 24) | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)) as i32
    }

    #[test]
    fn detects_line_thickness_and_cartoon_palette() {
        let w = 240u32;
        let h = 240u32;
        let white = pack_argb(255, 255, 255);
        let black = pack_argb(18, 18, 18);
        let yellow = pack_argb(250, 212, 32);
        let cyan = pack_argb(46, 178, 232);

        let mut pixels = vec![white; (w * h) as usize];

        // Relleno amarillo interior y franja azul inferior
        for y in 40..150 {
            for x in 50..190 {
                pixels[(y * w + x) as usize] = yellow;
            }
        }
        for y in 150..210 {
            for x in 50..190 {
                pixels[(y * w + x) as usize] = cyan;
            }
        }
        // Contorno negro de 6 px de grosor alrededor del rectángulo
        for y in 37..213 {
            for x in 47..193 {
                let on_border = x < 53 || x >= 187 || y < 43 || y >= 207 || (y >= 147 && y < 153);
                if on_border {
                    pixels[(y * w + x) as usize] = black;
                }
            }
        }

        // Imagen 240px renderizada a 480px en el lienzo (escala 2x):
        // Grosor 6px * 2 = 12px diámetro -> radio esperado ~ 6.0 px.
        let report = analyze_tracing_image(&pixels, w, h, 480.0);
        assert_eq!(report.style_code, 0);
        assert!(
            (report.main_radius_px - 6.0).abs() <= 1.5,
            "main_radius_px inesperado: {}",
            report.main_radius_px
        );
        assert!(report.fine_radius_px <= report.main_radius_px);
        assert!(report.bold_radius_px >= report.main_radius_px);

        // Debe contener tinta negra, amarillo y cian
        let has_yellow = report
            .palette
            .iter()
            .any(|p| p.color.r > 210 && p.color.g > 170 && p.color.b < 90);
        let has_cyan = report
            .palette
            .iter()
            .any(|p| p.color.r < 95 && p.color.g > 140 && p.color.b > 190);
        assert!(has_yellow, "Faltó color amarillo en {:?}", report.palette);
        assert!(has_cyan, "Faltó color cian en {:?}", report.palette);
    }

    #[test]
    fn stroke_recommendation_scales_with_rendered_canvas_width() {
        let w = 160u32;
        let h = 160u32;
        let white = pack_argb(255, 255, 255);
        let black = pack_argb(0, 0, 0);
        let mut pixels = vec![white; (w * h) as usize];

        for y in 30..130 {
            for x in 78..82 {
                pixels[(y * w + x) as usize] = black;
            }
        }

        let r_small = analyze_tracing_image(&pixels, w, h, 160.0);
        let r_large = analyze_tracing_image(&pixels, w, h, 480.0);
        assert!(r_large.main_radius_px > r_small.main_radius_px * 2.2);
    }

    #[test]
    fn handles_blank_image_safely() {
        let pixels = vec![pack_argb(255, 255, 255); 64 * 64];
        let report = analyze_tracing_image(&pixels, 64, 64, 1080.0);
        assert_eq!(report.style_code, 3);
        assert!(report.main_radius_px.is_finite() && report.main_radius_px >= 1.5);
        assert!(!report.palette.is_empty());
    }
}
