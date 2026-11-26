//! Pure-Rust DSP utilities (image backdrop blur, reusable scratch buffers).

/// Fast, separable multi-pass running-sum box blur on ARGB_8888 pixels.
/// Uses a single reusable scratch buffer and clamped edges.
#[inline]
pub fn box_blur_argb(
    pixels: &mut [u32],
    width: usize,
    height: usize,
    radius: usize,
    passes: usize,
) {
    if width == 0 || height == 0 || radius == 0 || passes == 0 || pixels.len() < width * height {
        return;
    }

    let window = (radius * 2 + 1) as u32;
    let max_len = width.max(height);
    let mut line = vec![0u32; max_len];
    let r_i = radius as isize;

    for _ in 0..passes {
        // Horizontal pass
        let w_i = width as isize;
        for y in 0..height {
            let row_offset = y * width;
            line[..width].copy_from_slice(&pixels[row_offset..row_offset + width]);
            let mut r: u32 = 0;
            let mut g: u32 = 0;
            let mut b: u32 = 0;
            for k in -r_i..=r_i {
                let p = line[k.clamp(0, w_i - 1) as usize];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for x in 0..width {
                pixels[row_offset + x] =
                    0xFF00_0000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
                let out_p = line[((x as isize) - r_i).clamp(0, w_i - 1) as usize];
                let in_p = line[((x as isize) + r_i + 1).clamp(0, w_i - 1) as usize];
                r = r + ((in_p >> 16) & 0xFF) - ((out_p >> 16) & 0xFF);
                g = g + ((in_p >> 8) & 0xFF) - ((out_p >> 8) & 0xFF);
                b = b + (in_p & 0xFF) - (out_p & 0xFF);
            }
        }

        // Vertical pass
        let h_i = height as isize;
        for x in 0..width {
            for y in 0..height {
                line[y] = pixels[y * width + x];
            }
            let mut r: u32 = 0;
            let mut g: u32 = 0;
            let mut b: u32 = 0;
            for k in -r_i..=r_i {
                let p = line[k.clamp(0, h_i - 1) as usize];
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for y in 0..height {
                pixels[y * width + x] =
                    0xFF00_0000 | ((r / window) << 16) | ((g / window) << 8) | (b / window);
                let out_p = line[((y as isize) - r_i).clamp(0, h_i - 1) as usize];
                let in_p = line[((y as isize) + r_i + 1).clamp(0, h_i - 1) as usize];
                r = r + ((in_p >> 16) & 0xFF) - ((out_p >> 16) & 0xFF);
                g = g + ((in_p >> 8) & 0xFF) - ((out_p >> 8) & 0xFF);
                b = b + (in_p & 0xFF) - (out_p & 0xFF);
            }
        }
    }
}
