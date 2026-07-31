/*
 * Android batch scanner derived from Ghostty's SIMD VT fast-path architecture.
 *
 * Upstream: https://github.com/ghostty-org/ghostty
 * Commit:   15484b607eb5a518dedf1548247c923b8abaae7c
 * Files:    src/simd/vt.cpp, src/terminal/stream.zig
 * License:  MIT (see third_party/ghostty-vt/ LICENSE and UPSTREAM.md)
 *
 * The boundary is intentionally coarse: one JNI call classifies an entire PTY chunk. The Java
 * state machine remains authoritative, so this optimization cannot change VT semantics.
 */

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif
#if defined(__SSE2__)
#include <emmintrin.h>
#endif

#define TERMUX_MIN_ASCII_RUN 8

#if defined(__ARM_NEON)
#define TERMUX_ASCII_BACKEND 2
#elif defined(__SSE2__)
#define TERMUX_ASCII_BACKEND 3
#else
#define TERMUX_ASCII_BACKEND 1
#endif

static int is_printable_ascii(uint8_t value)
{
    return value >= 0x20 && value < 0x7f;
}

#if defined(__ARM_NEON)
static int simd_block_is_printable_ascii(const uint8_t* input)
{
    const uint8x16_t value = vld1q_u8(input);
    const uint8x16_t below_space = vcltq_u8(value, vdupq_n_u8(0x20));
    const uint8x16_t above_tilde = vcgtq_u8(value, vdupq_n_u8(0x7e));
#if defined(__aarch64__)
    return vmaxvq_u8(vorrq_u8(below_space, above_tilde)) == 0;
#else
    const uint64x2_t invalid = vreinterpretq_u64_u8(vorrq_u8(below_space, above_tilde));
    return (vgetq_lane_u64(invalid, 0) | vgetq_lane_u64(invalid, 1)) == 0;
#endif
}
#elif defined(__SSE2__)
static int simd_block_is_printable_ascii(const uint8_t* input)
{
    const __m128i value = _mm_loadu_si128((const __m128i*) input);
    const __m128i below_space = _mm_cmpeq_epi8(
        _mm_min_epu8(value, _mm_set1_epi8(0x1f)), value);
    const __m128i at_or_above_del = _mm_cmpeq_epi8(
        _mm_max_epu8(value, _mm_set1_epi8(0x7f)), value);
    return _mm_movemask_epi8(_mm_or_si128(below_space, at_or_above_del)) == 0;
}
#endif

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNativeAccelerator_nativeScanAsciiRuns(
    JNIEnv* env, jclass clazz, jbyteArray input_array, jint length, jobject ranges_buffer)
{
    (void) clazz;
    if (input_array == NULL || ranges_buffer == NULL || length < 0) return 0;

    const jsize input_length = (*env)->GetArrayLength(env, input_array);
    const jlong ranges_bytes = (*env)->GetDirectBufferCapacity(env, ranges_buffer);
    jint* ranges = (jint*) (*env)->GetDirectBufferAddress(env, ranges_buffer);
    if (length > input_length || ranges == NULL || ranges_bytes < 3 * (jlong) sizeof(jint)) return 0;
    const jint ranges_length = (jint) (ranges_bytes / (jlong) sizeof(jint));

    /*
     * JNI forbids calling other JNI functions while a GetPrimitiveArrayCritical region is held.
     * Resolve the direct output address first; the scan then makes no JNI calls until the read-only
     * input has been released. The direct buffer avoids a result-array copy on every PTY chunk.
     */
    jbyte* input_bytes = (*env)->GetPrimitiveArrayCritical(env, input_array, NULL);
    if (input_bytes == NULL) return 0;

    const uint8_t* input = (const uint8_t*) input_bytes;
    const size_t input_size = (size_t) length;
    const jint capacity = (ranges_length - 1) / 2;
    jint range_count = 0;
    size_t offset = 0;
    size_t scanned_until = input_size;

    while (offset < input_size) {
        while (offset < input_size && !is_printable_ascii(input[offset])) offset++;
        const size_t start = offset;

#if defined(__ARM_NEON) || defined(__SSE2__)
        while (offset + 16 <= input_size && simd_block_is_printable_ascii(input + offset)) {
            offset += 16;
        }
#endif
        while (offset < input_size && is_printable_ascii(input[offset])) offset++;

        if (offset - start < TERMUX_MIN_ASCII_RUN) continue;
        if (range_count >= capacity) {
            scanned_until = start;
            break;
        }
        ranges[1 + range_count * 2] = (jint) start;
        ranges[2 + range_count * 2] = (jint) offset;
        range_count++;
    }

    ranges[0] = (jint) scanned_until;
    (*env)->ReleasePrimitiveArrayCritical(env, input_array, input_bytes, JNI_ABORT);
    return range_count;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_TerminalNativeAccelerator_nativeAsciiBackend(JNIEnv* env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return TERMUX_ASCII_BACKEND;
}
