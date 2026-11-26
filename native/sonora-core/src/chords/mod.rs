//! Offline chord chroma extraction (8192-pt radix-2 FFT + Hann window + 12-bin pitch-class profile).
//!
//! Bit-for-bit mathematical parity with `ChordAnalyzer.classify()` in
//! `shared/src/commonMain/kotlin/com/music/bitchord/playback/chords/ChordAnalysis.kt`.

use std::f64::consts::PI;
use std::sync::OnceLock;

pub const TARGET_RATE: usize = 11_025;
pub const FFT_SIZE: usize = 8_192;
const FFT_MASK: usize = FFT_SIZE - 1;
const HALF_SIZE: usize = FFT_SIZE / 2;

struct ChordTables {
    window: [f64; FFT_SIZE],
    bit_rev: [usize; FFT_SIZE],
    half_cos: [f64; HALF_SIZE],
    half_sin: [f64; HALF_SIZE],
    bin_chroma: [i8; HALF_SIZE],
    bin_weight: [f64; HALF_SIZE],
    bin_min: usize,
    bin_max: usize,
}

static TABLES: OnceLock<ChordTables> = OnceLock::new();

fn tables() -> &'static ChordTables {
    TABLES.get_or_init(|| {
        let mut window = [0.0f64; FFT_SIZE];
        for i in 0..FFT_SIZE {
            window[i] = 0.5 - 0.5 * (2.0 * PI * (i as f64) / ((FFT_SIZE - 1) as f64)).cos();
        }

        let mut bit_rev = [0usize; FFT_SIZE];
        for i in 1..FFT_SIZE {
            let mut j = 0usize;
            let mut x = i;
            let mut bit = FFT_SIZE >> 1;
            while x != 0 {
                if (x & 1) != 0 {
                    j |= bit;
                }
                x >>= 1;
                bit >>= 1;
            }
            bit_rev[i] = j;
        }

        let mut half_cos = [0.0f64; HALF_SIZE];
        let mut half_sin = [0.0f64; HALF_SIZE];
        for k in 0..HALF_SIZE {
            let angle = -2.0 * PI * (k as f64) / (FFT_SIZE as f64);
            half_cos[k] = angle.cos();
            half_sin[k] = angle.sin();
        }

        let mut bin_chroma = [-1i8; HALF_SIZE];
        let mut bin_weight = [0.0f64; HALF_SIZE];
        let mut bin_min = HALF_SIZE;
        let mut bin_max = 0usize;
        let ln2 = 2.0f64.ln();
        for bin in 1..HALF_SIZE {
            let frequency = (bin as f64) * (TARGET_RATE as f64) / (FFT_SIZE as f64);
            if !(65.0..=1600.0).contains(&frequency) {
                continue;
            }
            let midi = 69.0 + 12.0 * (frequency / 440.0).ln() / ln2;
            let note = midi.round() as i32;
            let distance = (midi - (note as f64)).abs();
            if distance > 0.4 {
                continue;
            }
            bin_chroma[bin] = (((note % 12) + 12) % 12) as i8;
            bin_weight[bin] = 1.0 - distance / 0.5;
            if bin < bin_min {
                bin_min = bin;
            }
            if bin > bin_max {
                bin_max = bin;
            }
        }

        ChordTables {
            window,
            bit_rev,
            half_cos,
            half_sin,
            bin_chroma,
            bin_weight,
            bin_min,
            bin_max,
        }
    })
}

/// Computes the normalized 12-bin pitch-class chroma vector from an 8192-sample
/// circular ring buffer starting at `base`.
///
/// Writes 12 normalized doubles into `out_chroma`.
pub fn compute_chroma_frame(ring: &[f64], base: usize, out_chroma: &mut [f64]) -> bool {
    if ring.len() < FFT_SIZE || out_chroma.len() < 12 {
        return false;
    }
    let tbl = tables();

    let mut re = [0.0f64; FFT_SIZE];
    let mut im = [0.0f64; FFT_SIZE];
    let mut energy = 0.0f64;

    for i in 0..FFT_SIZE {
        let sample = ring[(base.wrapping_add(i)) & FFT_MASK];
        energy += sample * sample;
        re[i] = sample * tbl.window[i];
    }

    if energy / (FFT_SIZE as f64) < 1e-7 {
        out_chroma[..12].fill(0.0);
        return true;
    }

    // Bit-reversal permutation
    for i in 1..FFT_SIZE {
        let j = tbl.bit_rev[i];
        if i < j {
            re.swap(i, j);
        }
    }

    // Iterative radix-2 Cooley-Tukey FFT
    let mut length = 2usize;
    while length <= FFT_SIZE {
        let half = length >> 1;
        let stride = FFT_SIZE / length;
        let mut start = 0usize;
        while start < FFT_SIZE {
            for k in 0..half {
                let tw = k * stride;
                let wr = tbl.half_cos[tw];
                let wi = tbl.half_sin[tw];
                let a = start + k;
                let b = a + half;
                let rb = re[b];
                let ib = im[b];
                let br = rb * wr - ib * wi;
                let bi = rb * wi + ib * wr;
                re[b] = re[a] - br;
                im[b] = im[a] - bi;
                re[a] += br;
                im[a] += bi;
            }
            start += length;
        }
        length <<= 1;
    }

    // Chroma projection
    let mut chroma = [0.0f64; 12];
    for bin in tbl.bin_min..=tbl.bin_max {
        let c = tbl.bin_chroma[bin];
        if c < 0 {
            continue;
        }
        chroma[c as usize] += re[bin].hypot(im[bin]) * tbl.bin_weight[bin];
    }

    let norm = chroma.iter().map(|v| v * v).sum::<f64>().sqrt();
    if norm > 1e-9 {
        for i in 0..12 {
            out_chroma[i] = chroma[i] / norm;
        }
    } else {
        out_chroma[..12].fill(0.0);
    }
    true
}
