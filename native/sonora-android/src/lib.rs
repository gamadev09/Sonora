//! Thin, panic-safe Android JNI bridge for `sonora-core`.
//!
//! Exports:
//! - `Java_com_music_bitchord_ui_components_NativeBlur_boxBlur`
//! - `Java_com_music_bitchord_playback_chords_NativeChordDspAndroid_computeChroma`

use std::ffi::c_void;

type JniEnv = *mut *const *const c_void;
type JObject = *mut c_void;
type JArray = *mut c_void;
type JInt = i32;
type JBoolean = u8;

const IDX_GET_ARRAY_LENGTH: usize = 171;
const IDX_GET_PRIMITIVE_ARRAY_CRITICAL: usize = 222;
const IDX_RELEASE_PRIMITIVE_ARRAY_CRITICAL: usize = 223;

type GetArrayLengthFn = unsafe extern "C" fn(JniEnv, JArray) -> JInt;
type GetPrimitiveArrayCriticalFn =
    unsafe extern "C" fn(JniEnv, JArray, *mut JBoolean) -> *mut c_void;
type ReleasePrimitiveArrayCriticalFn =
    unsafe extern "C" fn(JniEnv, JArray, *mut c_void, JInt);

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_bitchord_ui_components_NativeBlur_boxBlur(
    env: JniEnv,
    _class: JObject,
    pixels_arr: JArray,
    width: JInt,
    height: JInt,
    radius: JInt,
    passes: JInt,
) {
    if env.is_null()
        || pixels_arr.is_null()
        || width <= 0
        || height <= 0
        || radius <= 0
        || passes <= 0
    {
        return;
    }

    let vtable = *env;
    let get_len: GetArrayLengthFn = std::mem::transmute(*vtable.add(IDX_GET_ARRAY_LENGTH));
    let get_crit: GetPrimitiveArrayCriticalFn =
        std::mem::transmute(*vtable.add(IDX_GET_PRIMITIVE_ARRAY_CRITICAL));
    let rel_crit: ReleasePrimitiveArrayCriticalFn =
        std::mem::transmute(*vtable.add(IDX_RELEASE_PRIMITIVE_ARRAY_CRITICAL));

    let len = get_len(env, pixels_arr);
    let expected = (width as usize).saturating_mul(height as usize);
    if len <= 0 || (len as usize) < expected {
        return;
    }

    let mut is_copy: JBoolean = 0;
    let raw_ptr = get_crit(env, pixels_arr, &mut is_copy);
    if raw_ptr.is_null() {
        return;
    }

    let slice = std::slice::from_raw_parts_mut(raw_ptr as *mut u32, len as usize);
    sonora_core::dsp::box_blur_argb(
        slice,
        width as usize,
        height as usize,
        radius as usize,
        passes as usize,
    );

    rel_crit(env, pixels_arr, raw_ptr, 0);
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_music_bitchord_playback_chords_NativeChordDspAndroid_computeChroma(
    env: JniEnv,
    _class: JObject,
    ring_arr: JArray,
    base: JInt,
    out_chroma_arr: JArray,
) -> JBoolean {
    if env.is_null() || ring_arr.is_null() || out_chroma_arr.is_null() {
        return 0;
    }

    let vtable = *env;
    let get_len: GetArrayLengthFn = std::mem::transmute(*vtable.add(IDX_GET_ARRAY_LENGTH));
    let get_crit: GetPrimitiveArrayCriticalFn =
        std::mem::transmute(*vtable.add(IDX_GET_PRIMITIVE_ARRAY_CRITICAL));
    let rel_crit: ReleasePrimitiveArrayCriticalFn =
        std::mem::transmute(*vtable.add(IDX_RELEASE_PRIMITIVE_ARRAY_CRITICAL));

    let ring_len = get_len(env, ring_arr);
    let out_len = get_len(env, out_chroma_arr);
    if ring_len < sonora_core::chords::FFT_SIZE as JInt || out_len < 12 {
        return 0;
    }

    let mut is_copy_ring: JBoolean = 0;
    let ring_ptr = get_crit(env, ring_arr, &mut is_copy_ring);
    if ring_ptr.is_null() {
        return 0;
    }

    let mut is_copy_out: JBoolean = 0;
    let out_ptr = get_crit(env, out_chroma_arr, &mut is_copy_out);
    if out_ptr.is_null() {
        rel_crit(env, ring_arr, ring_ptr, 2); // JNI_ABORT (read-only)
        return 0;
    }

    let ring_slice = std::slice::from_raw_parts(ring_ptr as *const f64, ring_len as usize);
    let out_slice = std::slice::from_raw_parts_mut(out_ptr as *mut f64, out_len as usize);

    let ok = sonora_core::chords::compute_chroma_frame(ring_slice, base as usize, out_slice);

    rel_crit(env, out_chroma_arr, out_ptr, 0);
    rel_crit(env, ring_arr, ring_ptr, 2); // JNI_ABORT (read-only, no copy-back needed)

    if ok { 1 } else { 0 }
}
