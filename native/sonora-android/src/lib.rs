//! Thin Android JNI bridge for `sonora-core`.
//!
//! Uses non-GC-blocking JNI array access (`GetIntArrayElements` / `GetDoubleArrayRegion` /
//! `SetDoubleArrayRegion`) so ART garbage collection is never stalled during FFT or blur.

use std::ffi::c_void;

type JniEnv = *mut *const *const c_void;
type JObject = *mut c_void;
type JArray = *mut c_void;
type JInt = i32;
type JBoolean = u8;

const IDX_GET_ARRAY_LENGTH: usize = 171;
const IDX_GET_INT_ARRAY_ELEMENTS: usize = 187;
const IDX_RELEASE_INT_ARRAY_ELEMENTS: usize = 195;
const IDX_GET_DOUBLE_ARRAY_REGION: usize = 206;
const IDX_SET_DOUBLE_ARRAY_REGION: usize = 214;

type GetArrayLengthFn = unsafe extern "C" fn(JniEnv, JArray) -> JInt;
type GetIntArrayElementsFn = unsafe extern "C" fn(JniEnv, JArray, *mut JBoolean) -> *mut JInt;
type ReleaseIntArrayElementsFn = unsafe extern "C" fn(JniEnv, JArray, *mut JInt, JInt);
type GetDoubleArrayRegionFn = unsafe extern "C" fn(JniEnv, JArray, JInt, JInt, *mut f64);
type SetDoubleArrayRegionFn = unsafe extern "C" fn(JniEnv, JArray, JInt, JInt, *const f64);

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
    let get_elems: GetIntArrayElementsFn =
        std::mem::transmute(*vtable.add(IDX_GET_INT_ARRAY_ELEMENTS));
    let rel_elems: ReleaseIntArrayElementsFn =
        std::mem::transmute(*vtable.add(IDX_RELEASE_INT_ARRAY_ELEMENTS));

    let len = get_len(env, pixels_arr);
    let expected = (width as usize).saturating_mul(height as usize);
    if len <= 0 || (len as usize) < expected {
        return;
    }

    let mut is_copy: JBoolean = 0;
    let raw_ptr = get_elems(env, pixels_arr, &mut is_copy);
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

    rel_elems(env, pixels_arr, raw_ptr, 0);
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
    let get_region: GetDoubleArrayRegionFn =
        std::mem::transmute(*vtable.add(IDX_GET_DOUBLE_ARRAY_REGION));
    let set_region: SetDoubleArrayRegionFn =
        std::mem::transmute(*vtable.add(IDX_SET_DOUBLE_ARRAY_REGION));

    let ring_len = get_len(env, ring_arr);
    let out_len = get_len(env, out_chroma_arr);
    if ring_len < sonora_core::chords::FFT_SIZE as JInt || out_len < 12 {
        return 0;
    }

    // Copy ring into a local buffer so the 8192-point FFT runs completely outside any JVM GC lock.
    let mut ring_buf = [0.0f64; sonora_core::chords::FFT_SIZE];
    get_region(
        env,
        ring_arr,
        0,
        sonora_core::chords::FFT_SIZE as JInt,
        ring_buf.as_mut_ptr(),
    );

    let mut out_buf = [0.0f64; 12];
    let ok = sonora_core::chords::compute_chroma_frame(&ring_buf, base as usize, &mut out_buf);
    if !ok {
        return 0;
    }

    set_region(env, out_chroma_arr, 0, 12, out_buf.as_ptr());
    1
}
