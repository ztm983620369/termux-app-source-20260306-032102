/*
 * JNI bridge to the complete libghostty-vt terminal and render-state C API.
 *
 * Upstream: https://github.com/ghostty-org/ghostty
 * Commit:   15484b607eb5a518dedf1548247c923b8abaae7c
 * License:  MIT (third_party/ghostty-vt/LICENSE)
 *
 * libghostty-vt is loaded dynamically so Termux always retains its fail-safe Java backend if an
 * ABI is missing or the pinned C ABI fails validation. One native handle owns a complete
 * GhosttyTerminal, GhosttyRenderState, and reusable row/cell iterators.
 */

#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <ghostty/vt.h>

#define TERMUX_GHOSTTY_STATS_COUNT 20
#define TERMUX_GHOSTTY_RENDER_METADATA_COUNT 18
#define TERMUX_GHOSTTY_RENDER_DELTA_METADATA_COUNT 26
#define TERMUX_GHOSTTY_STATE_COUNT 22
#define TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT 20
#define TERMUX_GHOSTTY_CELL_RECORD_BYTES 24
#define TERMUX_GHOSTTY_RENDER_DELTA_ABI 3
#define TERMUX_GHOSTTY_RENDER_BATCH_API 1
#define TERMUX_GHOSTTY_MAX_GRAPHEME_CODEPOINTS (1024U * 1024U)
#define TERMUX_GHOSTTY_MAX_HOST_CONTROL_BYTES 8192U
#define TERMUX_GHOSTTY_MAX_HYPERLINK_BYTES 8192U
#define TERMUX_GHOSTTY_MAX_SELECTION_HYPERLINKS 32U
#define TERMUX_GHOSTTY_PINNED_COMMIT "15484b607eb5a518dedf1548247c923b8abaae7c"

#define TERMUX_GHOSTTY_CELL_BOLD (1U << 0)
#define TERMUX_GHOSTTY_CELL_ITALIC (1U << 1)
#define TERMUX_GHOSTTY_CELL_UNDERLINE (1U << 2)
#define TERMUX_GHOSTTY_CELL_STRIKETHROUGH (1U << 3)
#define TERMUX_GHOSTTY_CELL_FAINT (1U << 4)
#define TERMUX_GHOSTTY_CELL_BLINK (1U << 5)
#define TERMUX_GHOSTTY_CELL_INVERSE (1U << 6)
#define TERMUX_GHOSTTY_CELL_INVISIBLE (1U << 7)
#define TERMUX_GHOSTTY_CELL_OVERLINE (1U << 8)
#define TERMUX_GHOSTTY_CELL_UNDERLINE_SHIFT 12U
#define TERMUX_GHOSTTY_CELL_WIDE_SHIFT 16U

#define TERMUX_GHOSTTY_MODE_CURSOR_KEYS (UINT64_C(1) << 0)
#define TERMUX_GHOSTTY_MODE_REVERSE_VIDEO (UINT64_C(1) << 1)
#define TERMUX_GHOSTTY_MODE_KEYPAD (UINT64_C(1) << 2)
#define TERMUX_GHOSTTY_MODE_MOUSE (UINT64_C(1) << 3)
#define TERMUX_GHOSTTY_MODE_MOUSE_MOTION (UINT64_C(1) << 4)
#define TERMUX_GHOSTTY_MODE_MOUSE_SGR (UINT64_C(1) << 5)
#define TERMUX_GHOSTTY_MODE_FOCUS (UINT64_C(1) << 6)
#define TERMUX_GHOSTTY_MODE_BRACKETED_PASTE (UINT64_C(1) << 7)
#define TERMUX_GHOSTTY_MODE_SYNC_OUTPUT (UINT64_C(1) << 8)
#define TERMUX_GHOSTTY_MODE_WRAPAROUND (UINT64_C(1) << 9)

typedef struct {
    void* library;
    bool available;
    bool simd;
    GhosttyOptimizeMode optimize;
    char version[128];
    char error[256];

    GhosttyResult (*build_info)(GhosttyBuildInfo, void*);
    GhosttyResult (*terminal_new)(const GhosttyAllocator*, GhosttyTerminal*, GhosttyTerminalOptions);
    void (*terminal_free)(GhosttyTerminal);
    GhosttyResult (*terminal_resize)(GhosttyTerminal, uint16_t, uint16_t, uint32_t, uint32_t);
    void (*terminal_reset)(GhosttyTerminal);
    GhosttyResult (*terminal_set)(GhosttyTerminal, GhosttyTerminalOption, const void*);
    GhosttyResult (*terminal_mode_get)(GhosttyTerminal, GhosttyMode, bool*);
    GhosttyResult (*terminal_mode_set)(GhosttyTerminal, GhosttyMode, bool);
    void (*terminal_scroll_viewport)(GhosttyTerminal, GhosttyTerminalScrollViewport);
    void (*terminal_vt_write)(GhosttyTerminal, const uint8_t*, size_t);
    GhosttyResult (*terminal_get)(GhosttyTerminal, GhosttyTerminalData, void*);
    GhosttyResult (*terminal_get_multi)(GhosttyTerminal, size_t,
                                        const GhosttyTerminalData*, void**, size_t*);
    GhosttyResult (*terminal_grid_ref)(GhosttyTerminal, GhosttyPoint, GhosttyGridRef*);
    GhosttyResult (*terminal_grid_ref_track)(GhosttyTerminal, GhosttyPoint,
                                             GhosttyTrackedGridRef*);
    GhosttyResult (*terminal_point_from_grid_ref)(GhosttyTerminal, const GhosttyGridRef*,
                                                   GhosttyPointTag, GhosttyPointCoordinate*);
    void (*tracked_grid_ref_free)(GhosttyTrackedGridRef);
    GhosttyResult (*tracked_grid_ref_point)(GhosttyTrackedGridRef, GhosttyPointTag,
                                            GhosttyPointCoordinate*);
    GhosttyResult (*tracked_grid_ref_set)(GhosttyTrackedGridRef, GhosttyTerminal, GhosttyPoint);
    GhosttyResult (*terminal_select_word)(GhosttyTerminal,
                                           const GhosttyTerminalSelectWordOptions*,
                                           GhosttySelection*);
    GhosttyResult (*terminal_select_all)(GhosttyTerminal, GhosttySelection*);
    GhosttyResult (*terminal_selection_format_buf)(GhosttyTerminal,
                                                    GhosttyTerminalSelectionFormatOptions,
                                                    uint8_t*, size_t, size_t*);
    GhosttyResult (*grid_ref_cell)(const GhosttyGridRef*, GhosttyCell*);
    GhosttyResult (*grid_ref_hyperlink_uri)(const GhosttyGridRef*, uint8_t*, size_t, size_t*);
    GhosttyResult (*mouse_encoder_new)(const GhosttyAllocator*, GhosttyMouseEncoder*);
    void (*mouse_encoder_free)(GhosttyMouseEncoder);
    void (*mouse_encoder_setopt)(GhosttyMouseEncoder, GhosttyMouseEncoderOption, const void*);
    void (*mouse_encoder_setopt_from_terminal)(GhosttyMouseEncoder, GhosttyTerminal);
    GhosttyResult (*mouse_encoder_encode)(GhosttyMouseEncoder, GhosttyMouseEvent,
                                          char*, size_t, size_t*);
    GhosttyResult (*mouse_event_new)(const GhosttyAllocator*, GhosttyMouseEvent*);
    void (*mouse_event_free)(GhosttyMouseEvent);
    void (*mouse_event_set_action)(GhosttyMouseEvent, GhosttyMouseAction);
    void (*mouse_event_set_button)(GhosttyMouseEvent, GhosttyMouseButton);
    void (*mouse_event_clear_button)(GhosttyMouseEvent);
    void (*mouse_event_set_position)(GhosttyMouseEvent, GhosttyMousePosition);
    GhosttyResult (*paste_encode)(char*, size_t, bool, char*, size_t, size_t*);
    GhosttyResult (*focus_encode)(GhosttyFocusEvent, char*, size_t, size_t*);
    GhosttyResult (*key_encoder_new)(const GhosttyAllocator*, GhosttyKeyEncoder*);
    void (*key_encoder_free)(GhosttyKeyEncoder);
    void (*key_encoder_setopt_from_terminal)(GhosttyKeyEncoder, GhosttyTerminal);
    GhosttyResult (*key_encoder_encode)(GhosttyKeyEncoder, GhosttyKeyEvent,
                                        char*, size_t, size_t*);
    GhosttyResult (*key_event_new)(const GhosttyAllocator*, GhosttyKeyEvent*);
    void (*key_event_free)(GhosttyKeyEvent);
    void (*key_event_set_action)(GhosttyKeyEvent, GhosttyKeyAction);
    void (*key_event_set_key)(GhosttyKeyEvent, GhosttyKey);
    void (*key_event_set_mods)(GhosttyKeyEvent, GhosttyMods);
    void (*key_event_set_utf8)(GhosttyKeyEvent, const char*, size_t);
    void (*key_event_set_unshifted_codepoint)(GhosttyKeyEvent, uint32_t);

    GhosttyResult (*render_state_new)(const GhosttyAllocator*, GhosttyRenderState*);
    void (*render_state_free)(GhosttyRenderState);
    GhosttyResult (*render_state_update)(GhosttyRenderState, GhosttyTerminal);
    GhosttyResult (*render_state_begin_update)(GhosttyRenderState, GhosttyTerminal);
    GhosttyResult (*render_state_end_update)(GhosttyRenderState);
    GhosttyResult (*render_state_get)(GhosttyRenderState, GhosttyRenderStateData, void*);
    GhosttyResult (*render_state_get_multi)(GhosttyRenderState, size_t,
                                            const GhosttyRenderStateData*, void**, size_t*);
    GhosttyResult (*render_state_colors_get)(GhosttyRenderState,
                                             GhosttyRenderStateColors*);
    GhosttyResult (*render_state_set)(GhosttyRenderState, GhosttyRenderStateOption, const void*);
    GhosttyResult (*row_iterator_new)(const GhosttyAllocator*, GhosttyRenderStateRowIterator*);
    void (*row_iterator_free)(GhosttyRenderStateRowIterator);
    bool (*row_iterator_next)(GhosttyRenderStateRowIterator);
    GhosttyResult (*row_get)(GhosttyRenderStateRowIterator, GhosttyRenderStateRowData, void*);
    GhosttyResult (*row_get_multi)(GhosttyRenderStateRowIterator, size_t,
                                   const GhosttyRenderStateRowData*, void**, size_t*);
    GhosttyResult (*row_set)(GhosttyRenderStateRowIterator, GhosttyRenderStateRowOption, const void*);
    GhosttyResult (*row_cells_new)(const GhosttyAllocator*, GhosttyRenderStateRowCells*);
    void (*row_cells_free)(GhosttyRenderStateRowCells);
    bool (*row_cells_next)(GhosttyRenderStateRowCells);
    GhosttyResult (*row_cells_get)(GhosttyRenderStateRowCells,
                                   GhosttyRenderStateRowCellsData,
                                   void*);
    GhosttyResult (*row_cells_get_multi)(GhosttyRenderStateRowCells, size_t,
                                         const GhosttyRenderStateRowCellsData*, void**, size_t*);
    GhosttyResult (*cell_get)(GhosttyCell, GhosttyCellData, void*);
    GhosttyResult (*cell_get_multi)(GhosttyCell, size_t,
                                    const GhosttyCellData*, void**, size_t*);
} TermuxGhosttyApi;

typedef struct {
    uint8_t* data;
    size_t length;
    size_t capacity;
    bool valid;
} TermuxGhosttyRetainedRow;

typedef struct {
    pthread_mutex_t mutex;
    pthread_mutex_t render_mutex;
    JavaVM* java_vm;
    jweak callback_target;
    jmethodID callback_write_pty;
    jmethodID callback_bell;
    jmethodID callback_title_changed;
    jmethodID callback_clipboard_write;
    jmethodID callback_host_control;
    JNIEnv* callback_env;
    GhosttyTerminal terminal;
    GhosttyRenderState render_state;
    GhosttyRenderStateRowIterator row_iterator;
    GhosttyRenderStateRowCells row_cells;
    GhosttyMouseEncoder mouse_encoder;
    GhosttyMouseEvent mouse_event;
    GhosttyKeyEncoder key_encoder;
    GhosttyKeyEvent key_event;
    GhosttyTrackedGridRef resize_anchor;
    uint32_t* grapheme_codepoints;
    size_t grapheme_capacity;
    uint64_t writes;
    uint64_t bytes;
    uint64_t render_updates;
    uint64_t render_delta_packets;
    uint64_t render_delta_rows;
    uint64_t state_generation;
    uint16_t columns;
    uint16_t rows;
    uint32_t cell_width;
    uint32_t cell_height;
    bool render_delta_initialized;
    int32_t render_delta_top_row;
    uint16_t render_delta_columns;
    uint16_t render_delta_rows_count;
    uint64_t render_delta_state_generation;
    bool render_delta_cursor_visible;
    int32_t render_delta_cursor_logical_row;
    TermuxGhosttyRetainedRow* render_delta_row_cache;
    size_t render_delta_row_cache_count;
    bool render_delta_row_cache_complete;
    uint64_t render_delta_semantic_candidates;
    uint64_t render_delta_semantic_suppressed;
    uint64_t render_delta_semantic_packets;
    uint8_t host_control_state;
    uint8_t host_control_prefix_index;
    uint8_t host_control_payload[TERMUX_GHOSTTY_MAX_HOST_CONTROL_BYTES];
    size_t host_control_payload_len;
} TermuxGhosttyBackend;

static TermuxGhosttyApi g_api;
static pthread_once_t g_api_once = PTHREAD_ONCE_INIT;

static void set_load_error(const char* message)
{
    snprintf(g_api.error, sizeof(g_api.error), "%s", message == NULL ? "unknown error" : message);
}

#define RESOLVE_API(member, symbol_name)                                                        \
    do {                                                                                         \
        void* resolved_symbol = dlsym(g_api.library, symbol_name);                               \
        if (resolved_symbol == NULL) {                                                           \
            set_load_error(dlerror());                                                          \
            goto load_failed;                                                                   \
        }                                                                                        \
        memcpy(&g_api.member, &resolved_symbol, sizeof(resolved_symbol));                        \
    } while (0)

static void load_ghostty_api(void)
{
    memset(&g_api, 0, sizeof(g_api));
    g_api.library = dlopen("libghostty-vt.so", RTLD_NOW | RTLD_LOCAL);
    if (g_api.library == NULL) {
        set_load_error(dlerror());
        return;
    }

    RESOLVE_API(build_info, "ghostty_build_info");
    RESOLVE_API(terminal_new, "ghostty_terminal_new");
    RESOLVE_API(terminal_free, "ghostty_terminal_free");
    RESOLVE_API(terminal_resize, "ghostty_terminal_resize");
    RESOLVE_API(terminal_reset, "ghostty_terminal_reset");
    RESOLVE_API(terminal_set, "ghostty_terminal_set");
    RESOLVE_API(terminal_mode_get, "ghostty_terminal_mode_get");
    RESOLVE_API(terminal_mode_set, "ghostty_terminal_mode_set");
    RESOLVE_API(terminal_scroll_viewport, "ghostty_terminal_scroll_viewport");
    RESOLVE_API(terminal_vt_write, "ghostty_terminal_vt_write");
    RESOLVE_API(terminal_get, "ghostty_terminal_get");
    RESOLVE_API(terminal_get_multi, "ghostty_terminal_get_multi");
    RESOLVE_API(terminal_grid_ref, "ghostty_terminal_grid_ref");
    RESOLVE_API(terminal_grid_ref_track, "ghostty_terminal_grid_ref_track");
    RESOLVE_API(terminal_point_from_grid_ref, "ghostty_terminal_point_from_grid_ref");
    RESOLVE_API(tracked_grid_ref_free, "ghostty_tracked_grid_ref_free");
    RESOLVE_API(tracked_grid_ref_point, "ghostty_tracked_grid_ref_point");
    RESOLVE_API(tracked_grid_ref_set, "ghostty_tracked_grid_ref_set");
    RESOLVE_API(terminal_select_word, "ghostty_terminal_select_word");
    RESOLVE_API(terminal_select_all, "ghostty_terminal_select_all");
    RESOLVE_API(terminal_selection_format_buf, "ghostty_terminal_selection_format_buf");
    RESOLVE_API(grid_ref_cell, "ghostty_grid_ref_cell");
    RESOLVE_API(grid_ref_hyperlink_uri, "ghostty_grid_ref_hyperlink_uri");
    RESOLVE_API(mouse_encoder_new, "ghostty_mouse_encoder_new");
    RESOLVE_API(mouse_encoder_free, "ghostty_mouse_encoder_free");
    RESOLVE_API(mouse_encoder_setopt, "ghostty_mouse_encoder_setopt");
    RESOLVE_API(mouse_encoder_setopt_from_terminal, "ghostty_mouse_encoder_setopt_from_terminal");
    RESOLVE_API(mouse_encoder_encode, "ghostty_mouse_encoder_encode");
    RESOLVE_API(mouse_event_new, "ghostty_mouse_event_new");
    RESOLVE_API(mouse_event_free, "ghostty_mouse_event_free");
    RESOLVE_API(mouse_event_set_action, "ghostty_mouse_event_set_action");
    RESOLVE_API(mouse_event_set_button, "ghostty_mouse_event_set_button");
    RESOLVE_API(mouse_event_clear_button, "ghostty_mouse_event_clear_button");
    RESOLVE_API(mouse_event_set_position, "ghostty_mouse_event_set_position");
    RESOLVE_API(paste_encode, "ghostty_paste_encode");
    RESOLVE_API(focus_encode, "ghostty_focus_encode");
    RESOLVE_API(key_encoder_new, "ghostty_key_encoder_new");
    RESOLVE_API(key_encoder_free, "ghostty_key_encoder_free");
    RESOLVE_API(key_encoder_setopt_from_terminal, "ghostty_key_encoder_setopt_from_terminal");
    RESOLVE_API(key_encoder_encode, "ghostty_key_encoder_encode");
    RESOLVE_API(key_event_new, "ghostty_key_event_new");
    RESOLVE_API(key_event_free, "ghostty_key_event_free");
    RESOLVE_API(key_event_set_action, "ghostty_key_event_set_action");
    RESOLVE_API(key_event_set_key, "ghostty_key_event_set_key");
    RESOLVE_API(key_event_set_mods, "ghostty_key_event_set_mods");
    RESOLVE_API(key_event_set_utf8, "ghostty_key_event_set_utf8");
    RESOLVE_API(key_event_set_unshifted_codepoint,
                "ghostty_key_event_set_unshifted_codepoint");
    RESOLVE_API(render_state_new, "ghostty_render_state_new");
    RESOLVE_API(render_state_free, "ghostty_render_state_free");
    RESOLVE_API(render_state_update, "ghostty_render_state_update");
    RESOLVE_API(render_state_begin_update, "ghostty_render_state_begin_update");
    RESOLVE_API(render_state_end_update, "ghostty_render_state_end_update");
    RESOLVE_API(render_state_get, "ghostty_render_state_get");
    RESOLVE_API(render_state_get_multi, "ghostty_render_state_get_multi");
    RESOLVE_API(render_state_colors_get, "ghostty_render_state_colors_get");
    RESOLVE_API(render_state_set, "ghostty_render_state_set");
    RESOLVE_API(row_iterator_new, "ghostty_render_state_row_iterator_new");
    RESOLVE_API(row_iterator_free, "ghostty_render_state_row_iterator_free");
    RESOLVE_API(row_iterator_next, "ghostty_render_state_row_iterator_next");
    RESOLVE_API(row_get, "ghostty_render_state_row_get");
    RESOLVE_API(row_get_multi, "ghostty_render_state_row_get_multi");
    RESOLVE_API(row_set, "ghostty_render_state_row_set");
    RESOLVE_API(row_cells_new, "ghostty_render_state_row_cells_new");
    RESOLVE_API(row_cells_free, "ghostty_render_state_row_cells_free");
    RESOLVE_API(row_cells_next, "ghostty_render_state_row_cells_next");
    RESOLVE_API(row_cells_get, "ghostty_render_state_row_cells_get");
    RESOLVE_API(row_cells_get_multi, "ghostty_render_state_row_cells_get_multi");
    RESOLVE_API(cell_get, "ghostty_cell_get");
    RESOLVE_API(cell_get_multi, "ghostty_cell_get_multi");

    GhosttyString version = {0};
    if (g_api.build_info(GHOSTTY_BUILD_INFO_VERSION_STRING, &version) == GHOSTTY_SUCCESS &&
        version.ptr != NULL && version.len > 0) {
        size_t length = version.len < sizeof(g_api.version) - 1 ? version.len : sizeof(g_api.version) - 1;
        memcpy(g_api.version, version.ptr, length);
        g_api.version[length] = '\0';
    } else {
        snprintf(g_api.version, sizeof(g_api.version), "unknown");
    }
    if (g_api.build_info(GHOSTTY_BUILD_INFO_SIMD, &g_api.simd) != GHOSTTY_SUCCESS ||
        g_api.build_info(GHOSTTY_BUILD_INFO_OPTIMIZE, &g_api.optimize) != GHOSTTY_SUCCESS) {
        set_load_error("libghostty-vt build-info contract failed");
        goto load_failed;
    }

    g_api.available = true;
    return;

load_failed:
    dlclose(g_api.library);
    g_api.library = NULL;
}

static bool ensure_api(void)
{
    pthread_once(&g_api_once, load_ghostty_api);
    return g_api.available;
}

static TermuxGhosttyBackend* backend_from_handle(jlong handle)
{
    return (TermuxGhosttyBackend*) (intptr_t) handle;
}

static uint64_t hash_u64(uint64_t hash, uint64_t value)
{
    for (unsigned int index = 0; index < 8; index++) {
        hash ^= (uint8_t) (value >> (index * 8));
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

static uint64_t hash_style_color(uint64_t hash, GhosttyStyleColor color)
{
    hash = hash_u64(hash, (uint64_t) color.tag);
    switch (color.tag) {
        case GHOSTTY_STYLE_COLOR_PALETTE:
            return hash_u64(hash, color.value.palette);
        case GHOSTTY_STYLE_COLOR_RGB:
            hash = hash_u64(hash, color.value.rgb.r);
            hash = hash_u64(hash, color.value.rgb.g);
            return hash_u64(hash, color.value.rgb.b);
        default:
            return hash;
    }
}

static uint32_t rgb_to_argb(GhosttyColorRgb color)
{
    return UINT32_C(0xff000000) | ((uint32_t) color.r << 16U) |
           ((uint32_t) color.g << 8U) | (uint32_t) color.b;
}

static uint32_t style_color_to_argb(GhosttyStyleColor color,
                                    const GhosttyColorRgb* palette,
                                    uint32_t fallback)
{
    switch (color.tag) {
        case GHOSTTY_STYLE_COLOR_PALETTE:
            return rgb_to_argb(palette[color.value.palette]);
        case GHOSTTY_STYLE_COLOR_RGB:
            return rgb_to_argb(color.value.rgb);
        default:
            return fallback;
    }
}

#if !TERMUX_GHOSTTY_RENDER_BATCH_API
static uint32_t utf8_length(uint32_t codepoint)
{
    if (codepoint <= UINT32_C(0x7f)) return 1;
    if (codepoint <= UINT32_C(0x7ff)) return 2;
    if (codepoint <= UINT32_C(0xffff)) return 3;
    return 4;
}

static uint32_t write_utf8(uint8_t* destination, uint32_t codepoint)
{
    if (codepoint <= UINT32_C(0x7f)) {
        destination[0] = (uint8_t) codepoint;
        return 1;
    }
    if (codepoint <= UINT32_C(0x7ff)) {
        destination[0] = (uint8_t) (UINT32_C(0xc0) | (codepoint >> 6U));
        destination[1] = (uint8_t) (UINT32_C(0x80) | (codepoint & UINT32_C(0x3f)));
        return 2;
    }
    if (codepoint <= UINT32_C(0xffff)) {
        destination[0] = (uint8_t) (UINT32_C(0xe0) | (codepoint >> 12U));
        destination[1] = (uint8_t) (UINT32_C(0x80) | ((codepoint >> 6U) & UINT32_C(0x3f)));
        destination[2] = (uint8_t) (UINT32_C(0x80) | (codepoint & UINT32_C(0x3f)));
        return 3;
    }
    destination[0] = (uint8_t) (UINT32_C(0xf0) | (codepoint >> 18U));
    destination[1] = (uint8_t) (UINT32_C(0x80) | ((codepoint >> 12U) & UINT32_C(0x3f)));
    destination[2] = (uint8_t) (UINT32_C(0x80) | ((codepoint >> 6U) & UINT32_C(0x3f)));
    destination[3] = (uint8_t) (UINT32_C(0x80) | (codepoint & UINT32_C(0x3f)));
    return 4;
}
#endif

static void write_packet_u32(uint8_t* destination, size_t offset, uint32_t value)
{
    memcpy(destination + offset, &value, sizeof(value));
}

static uint32_t read_packet_u32(const uint8_t* source, size_t offset)
{
    uint32_t value = 0;
    memcpy(&value, source + offset, sizeof(value));
    return value;
}

static bool align_packet_offset(size_t value, size_t* aligned)
{
    if (aligned == NULL || value > SIZE_MAX - (sizeof(uint32_t) - 1U)) return false;
    *aligned = (value + (sizeof(uint32_t) - 1U)) & ~(sizeof(uint32_t) - 1U);
    return true;
}

static void invalidate_render_delta_row_cache(TermuxGhosttyBackend* backend)
{
    if (backend == NULL) return;
    for (size_t row = 0; row < backend->render_delta_row_cache_count; row++) {
        backend->render_delta_row_cache[row].valid = false;
        backend->render_delta_row_cache[row].length = 0;
    }
    backend->render_delta_row_cache_complete = false;
}

static void free_render_delta_row_cache(TermuxGhosttyBackend* backend)
{
    if (backend == NULL) return;
    for (size_t row = 0; row < backend->render_delta_row_cache_count; row++) {
        free(backend->render_delta_row_cache[row].data);
    }
    free(backend->render_delta_row_cache);
    backend->render_delta_row_cache = NULL;
    backend->render_delta_row_cache_count = 0;
    backend->render_delta_row_cache_complete = false;
}

static bool ensure_render_delta_row_cache(TermuxGhosttyBackend* backend, size_t rows)
{
    if (backend == NULL || rows == 0) return false;
    if (backend->render_delta_row_cache != NULL &&
        backend->render_delta_row_cache_count == rows) return true;
    free_render_delta_row_cache(backend);
    if (rows > SIZE_MAX / sizeof(TermuxGhosttyRetainedRow)) return false;
    backend->render_delta_row_cache = calloc(rows, sizeof(TermuxGhosttyRetainedRow));
    if (backend->render_delta_row_cache == NULL) return false;
    backend->render_delta_row_cache_count = rows;
    return true;
}

static bool retained_row_matches(const TermuxGhosttyRetainedRow* retained,
                                 const uint8_t* packet,
                                 size_t row_payload_offset,
                                 size_t row_payload_length,
                                 uint16_t columns)
{
    if (retained == NULL || !retained->valid || retained->data == NULL || packet == NULL ||
        retained->length != row_payload_length) return false;
    size_t row_table_bytes = (size_t) columns * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    if (row_payload_length < row_table_bytes) return false;
    for (size_t column = 0; column < columns; column++) {
        size_t record = column * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
        if (memcmp(retained->data + record, packet + row_payload_offset + record,
                   4U * sizeof(uint32_t)) != 0 ||
            read_packet_u32(retained->data, record + 20U) !=
                read_packet_u32(packet, row_payload_offset + record + 20U)) return false;
        uint32_t absolute_text_offset =
            read_packet_u32(packet, row_payload_offset + record + 16U);
        if (absolute_text_offset < row_payload_offset ||
            (size_t) absolute_text_offset - row_payload_offset !=
                read_packet_u32(retained->data, record + 16U)) return false;
    }
    return memcmp(retained->data + row_table_bytes,
                  packet + row_payload_offset + row_table_bytes,
                  row_payload_length - row_table_bytes) == 0;
}

static bool capture_retained_row(TermuxGhosttyRetainedRow* retained,
                                 const uint8_t* packet,
                                 size_t row_payload_offset,
                                 size_t row_payload_length,
                                 uint16_t columns)
{
    if (retained == NULL || packet == NULL) return false;
    if (retained->capacity < row_payload_length) {
        uint8_t* resized = realloc(retained->data, row_payload_length);
        if (resized == NULL) return false;
        retained->data = resized;
        retained->capacity = row_payload_length;
    }
    memcpy(retained->data, packet + row_payload_offset, row_payload_length);
    for (size_t column = 0; column < columns; column++) {
        size_t record = column * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
        uint32_t absolute_text_offset = read_packet_u32(retained->data, record + 16U);
        if (absolute_text_offset < row_payload_offset ||
            (size_t) absolute_text_offset - row_payload_offset > UINT32_MAX) {
            retained->valid = false;
            retained->length = 0;
            return false;
        }
        write_packet_u32(retained->data, record + 16U,
                         absolute_text_offset - (uint32_t) row_payload_offset);
    }
    retained->length = row_payload_length;
    retained->valid = true;
    return true;
}

static bool update_render_delta_row_cache(TermuxGhosttyBackend* backend,
                                          const uint8_t* packet,
                                          size_t bytes_used,
                                          uint16_t columns,
                                          uint16_t rows,
                                          bool full_frame)
{
    if (backend == NULL || packet == NULL || columns == 0 || rows == 0 ||
        !ensure_render_delta_row_cache(backend, rows)) {
        invalidate_render_delta_row_cache(backend);
        return false;
    }
    if (full_frame) invalidate_render_delta_row_cache(backend);
    else if (!backend->render_delta_row_cache_complete) return false;

    size_t directory_bytes = (size_t) rows * sizeof(uint32_t);
    size_t row_table_bytes = (size_t) columns * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    if (bytes_used < directory_bytes || row_table_bytes > bytes_used) goto cache_invalid;
    for (size_t row = 0; row < rows; row++) {
        uint32_t payload_value = read_packet_u32(packet, row * sizeof(uint32_t));
        if (payload_value == 0U) {
            if (full_frame) goto cache_invalid;
            continue;
        }
        size_t payload = payload_value;
        if (payload < directory_bytes || payload > bytes_used - row_table_bytes) {
            goto cache_invalid;
        }
        size_t text_bytes = 0;
        for (size_t column = 0; column < columns; column++) {
            size_t record = payload + column * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
            uint32_t text_length = read_packet_u32(packet, record + 20U);
            if (text_bytes > SIZE_MAX - text_length) goto cache_invalid;
            text_bytes += text_length;
        }
        if (payload > SIZE_MAX - row_table_bytes ||
            payload + row_table_bytes > SIZE_MAX - text_bytes ||
            payload + row_table_bytes + text_bytes > bytes_used ||
            !capture_retained_row(&backend->render_delta_row_cache[row], packet, payload,
                                  row_table_bytes + text_bytes, columns)) {
            goto cache_invalid;
        }
    }
    if (full_frame) backend->render_delta_row_cache_complete = true;
    return true;

cache_invalid:
    invalidate_render_delta_row_cache(backend);
    return false;
}

static bool ensure_grapheme_capacity(TermuxGhosttyBackend* backend, uint32_t required)
{
    if (required <= backend->grapheme_capacity) return true;
    if (required > TERMUX_GHOSTTY_MAX_GRAPHEME_CODEPOINTS) return false;
    size_t capacity = backend->grapheme_capacity == 0 ? 16 : backend->grapheme_capacity;
    while (capacity < required) capacity *= 2;
    uint32_t* resized = realloc(backend->grapheme_codepoints, capacity * sizeof(uint32_t));
    if (resized == NULL) return false;
    backend->grapheme_codepoints = resized;
    backend->grapheme_capacity = capacity;
    return true;
}

typedef struct {
    uint16_t columns;
    uint16_t rows;
    GhosttyRenderStateDirty dirty;
    GhosttyRenderStateColors colors;
    GhosttyRenderStateCursorVisualStyle cursor_style;
    bool cursor_mode_visible;
    bool cursor_viewport_has_value;
    bool cursor_wide_tail;
    uint16_t cursor_x;
    uint16_t cursor_y;
    size_t scrollback_rows;
} TermuxGhosttyRenderFrame;

typedef struct {
    uint32_t foreground_argb;
    uint32_t background_argb;
    uint32_t underline_argb;
    uint32_t flags;
    uint32_t encoded_length;
} TermuxGhosttyRenderCell;

#if TERMUX_GHOSTTY_RENDER_BATCH_API
static bool multi_complete(GhosttyResult result, size_t written, size_t expected)
{
    return result == GHOSTTY_SUCCESS && written == expected;
}
#endif

static bool read_render_frame_locked(TermuxGhosttyBackend* backend,
                                     TermuxGhosttyRenderFrame* frame)
{
    if (backend == NULL || frame == NULL) return false;
    memset(frame, 0, sizeof(*frame));
    GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    frame->colors = colors;
    frame->cursor_style = GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;

#if TERMUX_GHOSTTY_RENDER_BATCH_API
    static const GhosttyRenderStateData frame_keys[] = {
        GHOSTTY_RENDER_STATE_DATA_COLS,
        GHOSTTY_RENDER_STATE_DATA_ROWS,
        GHOSTTY_RENDER_STATE_DATA_DIRTY,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE,
        GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
    };
    void* frame_values[] = {
        &frame->columns,
        &frame->rows,
        &frame->dirty,
        &frame->cursor_style,
        &frame->cursor_mode_visible,
        &frame->cursor_viewport_has_value,
        &backend->row_iterator,
    };
    size_t frame_written = 0;
    GhosttyResult frame_result = g_api.render_state_get_multi(
        backend->render_state, sizeof(frame_keys) / sizeof(frame_keys[0]),
        frame_keys, frame_values, &frame_written);
    if (!multi_complete(frame_result, frame_written,
                        sizeof(frame_keys) / sizeof(frame_keys[0])) ||
        g_api.render_state_colors_get(backend->render_state, &frame->colors) != GHOSTTY_SUCCESS) {
        return false;
    }
#else
    if (g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_COLS,
                               &frame->columns) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS,
                               &frame->rows) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY,
                               &frame->dirty) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_COLOR_BACKGROUND,
                               &frame->colors.background) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_COLOR_FOREGROUND,
                               &frame->colors.foreground) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR_HAS_VALUE,
                               &frame->colors.cursor_has_value) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_COLOR_PALETTE,
                               frame->colors.palette) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
                               &frame->cursor_style) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_CURSOR_VISIBLE,
                               &frame->cursor_mode_visible) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_HAS_VALUE,
                               &frame->cursor_viewport_has_value) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &backend->row_iterator) != GHOSTTY_SUCCESS) {
        return false;
    }
    if (frame->colors.cursor_has_value &&
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_COLOR_CURSOR,
                               &frame->colors.cursor) != GHOSTTY_SUCCESS) {
        return false;
    }
#endif

    if (!frame->cursor_viewport_has_value) return true;

#if TERMUX_GHOSTTY_RENDER_BATCH_API
    static const GhosttyRenderStateData cursor_keys[] = {
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y,
        GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL,
    };
    void* cursor_values[] = {
        &frame->cursor_x,
        &frame->cursor_y,
        &frame->cursor_wide_tail,
    };
    size_t cursor_written = 0;
    GhosttyResult cursor_result = g_api.render_state_get_multi(
        backend->render_state, sizeof(cursor_keys) / sizeof(cursor_keys[0]),
        cursor_keys, cursor_values, &cursor_written);
    return multi_complete(cursor_result, cursor_written,
                          sizeof(cursor_keys) / sizeof(cursor_keys[0]));
#else
    return g_api.render_state_get(backend->render_state,
                                  GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_X,
                                  &frame->cursor_x) == GHOSTTY_SUCCESS &&
           g_api.render_state_get(backend->render_state,
                                  GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_Y,
                                  &frame->cursor_y) == GHOSTTY_SUCCESS &&
           g_api.render_state_get(backend->render_state,
                                  GHOSTTY_RENDER_STATE_DATA_CURSOR_VIEWPORT_WIDE_TAIL,
                                  &frame->cursor_wide_tail) == GHOSTTY_SUCCESS;
#endif
}

static bool read_render_cell_locked(TermuxGhosttyBackend* backend,
                                    GhosttyColorRgb default_foreground,
                                    GhosttyColorRgb default_background,
                                    const GhosttyColorRgb* palette,
                                    uint8_t* packet,
                                    size_t capacity,
                                    size_t text_offset,
                                    TermuxGhosttyRenderCell* output)
{
    if (backend == NULL || palette == NULL || packet == NULL || output == NULL) return false;
    memset(output, 0, sizeof(*output));

    GhosttyCell raw_cell = 0;
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
#if TERMUX_GHOSTTY_RENDER_BATCH_API
    GhosttyCellContentTag content_tag = GHOSTTY_CELL_CONTENT_CODEPOINT;
#endif
    GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
    uint32_t encoded_length = 0;

#if TERMUX_GHOSTTY_RENDER_BATCH_API
    GhosttyBuffer text = {
        .ptr = text_offset <= capacity ? packet + text_offset : NULL,
        .cap = text_offset <= capacity ? capacity - text_offset : 0,
        .len = 0,
    };
    static const GhosttyRenderStateRowCellsData cell_keys[] = {
        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8,
    };
    void* cell_values[] = {&raw_cell, &style, &text};
    size_t cell_written = 0;
    GhosttyResult cell_result = g_api.row_cells_get_multi(
        backend->row_cells, sizeof(cell_keys) / sizeof(cell_keys[0]),
        cell_keys, cell_values, &cell_written);
    bool complete = multi_complete(cell_result, cell_written,
                                   sizeof(cell_keys) / sizeof(cell_keys[0]));
    bool measured = cell_result == GHOSTTY_OUT_OF_SPACE && cell_written == 2;
    if ((!complete && !measured) || text.len > UINT32_MAX) return false;
    encoded_length = (uint32_t) text.len;

    static const GhosttyCellData cell_metadata_keys[] = {
        GHOSTTY_CELL_DATA_WIDE,
        GHOSTTY_CELL_DATA_CONTENT_TAG,
    };
    void* cell_metadata_values[] = {&wide, &content_tag};
    size_t cell_metadata_written = 0;
    GhosttyResult cell_metadata_result = g_api.cell_get_multi(
        raw_cell, sizeof(cell_metadata_keys) / sizeof(cell_metadata_keys[0]),
        cell_metadata_keys, cell_metadata_values, &cell_metadata_written);
    if (!multi_complete(cell_metadata_result, cell_metadata_written,
                        sizeof(cell_metadata_keys) / sizeof(cell_metadata_keys[0]))) {
        return false;
    }
#else
    uint32_t grapheme_length = 0;
    bool has_styling = false;
    if (g_api.row_cells_get(backend->row_cells,
                            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW,
                            &raw_cell) != GHOSTTY_SUCCESS ||
        g_api.row_cells_get(backend->row_cells,
                            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
                            &grapheme_length) != GHOSTTY_SUCCESS ||
        g_api.row_cells_get(backend->row_cells,
                            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING,
                            &has_styling) != GHOSTTY_SUCCESS) {
        return false;
    }
    if (has_styling &&
        g_api.row_cells_get(backend->row_cells,
                            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                            &style) != GHOSTTY_SUCCESS) {
        return false;
    }
    if (grapheme_length > 0) {
        if (!ensure_grapheme_capacity(backend, grapheme_length) ||
            g_api.row_cells_get(backend->row_cells,
                                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                                backend->grapheme_codepoints) != GHOSTTY_SUCCESS) {
            return false;
        }
        for (uint32_t index = 0; index < grapheme_length; index++) {
            uint32_t codepoint = backend->grapheme_codepoints[index];
            if (codepoint > UINT32_C(0x10ffff) ||
                (codepoint >= UINT32_C(0xd800) && codepoint <= UINT32_C(0xdfff))) {
                codepoint = UINT32_C(0xfffd);
            }
            uint32_t length = utf8_length(codepoint);
            if (encoded_length > UINT32_MAX - length) return false;
            encoded_length += length;
        }
        if (text_offset <= capacity && encoded_length <= capacity - text_offset) {
            size_t written = 0;
            for (uint32_t index = 0; index < grapheme_length; index++) {
                uint32_t codepoint = backend->grapheme_codepoints[index];
                if (codepoint > UINT32_C(0x10ffff) ||
                    (codepoint >= UINT32_C(0xd800) && codepoint <= UINT32_C(0xdfff))) {
                    codepoint = UINT32_C(0xfffd);
                }
                written += write_utf8(packet + text_offset + written, codepoint);
            }
        }
    }
    if (g_api.cell_get(raw_cell, GHOSTTY_CELL_DATA_WIDE, &wide) != GHOSTTY_SUCCESS) return false;
#endif

    GhosttyColorRgb resolved_foreground = default_foreground;
    GhosttyColorRgb resolved_background = default_background;
#if TERMUX_GHOSTTY_RENDER_BATCH_API
    bool has_foreground_color = style.fg_color.tag != GHOSTTY_STYLE_COLOR_NONE;
    bool has_background_color = style.bg_color.tag != GHOSTTY_STYLE_COLOR_NONE ||
        content_tag == GHOSTTY_CELL_CONTENT_BG_COLOR_PALETTE ||
        content_tag == GHOSTTY_CELL_CONTENT_BG_COLOR_RGB;
    if (has_foreground_color && has_background_color) {
        static const GhosttyRenderStateRowCellsData color_keys[] = {
            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
            GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
        };
        void* color_values[] = {&resolved_foreground, &resolved_background};
        size_t color_written = 0;
        GhosttyResult color_result = g_api.row_cells_get_multi(
            backend->row_cells, sizeof(color_keys) / sizeof(color_keys[0]),
            color_keys, color_values, &color_written);
        if (!multi_complete(color_result, color_written,
                            sizeof(color_keys) / sizeof(color_keys[0]))) {
            return false;
        }
    } else {
        if (has_foreground_color &&
            g_api.row_cells_get(backend->row_cells,
                                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
                                &resolved_foreground) != GHOSTTY_SUCCESS) {
            return false;
        }
        if (has_background_color &&
            g_api.row_cells_get(backend->row_cells,
                                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
                                &resolved_background) != GHOSTTY_SUCCESS) {
            return false;
        }
    }
#else
    GhosttyResult color_result = g_api.row_cells_get(
        backend->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
        &resolved_foreground);
    if (color_result != GHOSTTY_SUCCESS && color_result != GHOSTTY_INVALID_VALUE &&
        color_result != GHOSTTY_NO_VALUE) return false;
    color_result = g_api.row_cells_get(
        backend->row_cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
        &resolved_background);
    if (color_result != GHOSTTY_SUCCESS && color_result != GHOSTTY_INVALID_VALUE &&
        color_result != GHOSTTY_NO_VALUE) return false;
#endif

    output->foreground_argb = rgb_to_argb(resolved_foreground);
    if (style.bold && style.fg_color.tag == GHOSTTY_STYLE_COLOR_PALETTE &&
        style.fg_color.value.palette < 8) {
        output->foreground_argb = rgb_to_argb(palette[style.fg_color.value.palette + 8]);
    }
    output->background_argb = rgb_to_argb(resolved_background);
    output->underline_argb =
        style_color_to_argb(style.underline_color, palette, output->foreground_argb);
    output->flags = ((uint32_t) wide << TERMUX_GHOSTTY_CELL_WIDE_SHIFT) |
                    (((uint32_t) style.underline & UINT32_C(0x0f))
                     << TERMUX_GHOSTTY_CELL_UNDERLINE_SHIFT);
    if (style.bold) output->flags |= TERMUX_GHOSTTY_CELL_BOLD;
    if (style.italic) output->flags |= TERMUX_GHOSTTY_CELL_ITALIC;
    if (style.underline != 0) output->flags |= TERMUX_GHOSTTY_CELL_UNDERLINE;
    if (style.strikethrough) output->flags |= TERMUX_GHOSTTY_CELL_STRIKETHROUGH;
    if (style.faint) output->flags |= TERMUX_GHOSTTY_CELL_FAINT;
    if (style.blink) output->flags |= TERMUX_GHOSTTY_CELL_BLINK;
    if (style.inverse) output->flags |= TERMUX_GHOSTTY_CELL_INVERSE;
    if (style.invisible) output->flags |= TERMUX_GHOSTTY_CELL_INVISIBLE;
    if (style.overline) output->flags |= TERMUX_GHOSTTY_CELL_OVERLINE;
    output->encoded_length = encoded_length;
    return true;
}

static jobject callback_target_local(TermuxGhosttyBackend* backend)
{
    JNIEnv* env = backend == NULL ? NULL : backend->callback_env;
    if (env == NULL || backend->callback_target == NULL) return NULL;
    return (*env)->NewLocalRef(env, backend->callback_target);
}

static jbyteArray byte_array_from_native(JNIEnv* env, const uint8_t* data, size_t len)
{
    if (env == NULL || len > INT32_MAX || (len > 0 && data == NULL)) return NULL;
    jbyteArray value = (*env)->NewByteArray(env, (jsize) len);
    if (value != NULL && len > 0) {
        (*env)->SetByteArrayRegion(env, value, 0, (jsize) len, (const jbyte*) data);
    }
    return value;
}

static void effect_write_pty(GhosttyTerminal terminal,
                             void* userdata,
                             const uint8_t* data,
                             size_t len)
{
    (void) terminal;
    TermuxGhosttyBackend* backend = userdata;
    jobject target = callback_target_local(backend);
    if (target == NULL) return;
    JNIEnv* env = backend->callback_env;
    jbyteArray value = byte_array_from_native(env, data, len);
    if (value != NULL) {
        (*env)->CallVoidMethod(env, target, backend->callback_write_pty, value);
        (*env)->DeleteLocalRef(env, value);
    }
    (*env)->DeleteLocalRef(env, target);
}

static void effect_bell(GhosttyTerminal terminal, void* userdata)
{
    (void) terminal;
    TermuxGhosttyBackend* backend = userdata;
    jobject target = callback_target_local(backend);
    if (target == NULL) return;
    JNIEnv* env = backend->callback_env;
    (*env)->CallVoidMethod(env, target, backend->callback_bell);
    (*env)->DeleteLocalRef(env, target);
}

static void effect_title_changed(GhosttyTerminal terminal, void* userdata)
{
    TermuxGhosttyBackend* backend = userdata;
    jobject target = callback_target_local(backend);
    if (target == NULL) return;
    JNIEnv* env = backend->callback_env;
    GhosttyString title = {0};
    if (g_api.terminal_get(terminal, GHOSTTY_TERMINAL_DATA_TITLE, &title) == GHOSTTY_SUCCESS) {
        jbyteArray value = byte_array_from_native(env, title.ptr, title.len);
        if (value != NULL) {
            (*env)->CallVoidMethod(env, target, backend->callback_title_changed, value);
            (*env)->DeleteLocalRef(env, value);
        }
    }
    (*env)->DeleteLocalRef(env, target);
}

static GhosttyClipboardWriteResult effect_clipboard_write(
    GhosttyTerminal terminal,
    void* userdata,
    const GhosttyClipboardWrite* write)
{
    (void) terminal;
    TermuxGhosttyBackend* backend = userdata;
    if (write == NULL) return GHOSTTY_CLIPBOARD_WRITE_RESULT_INVALID_DATA;
    jobject target = callback_target_local(backend);
    if (target == NULL) return GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED;
    JNIEnv* env = backend->callback_env;
    const GhosttyClipboardContent* selected = NULL;
    for (size_t index = 0; index < write->contents_len; index++) {
        const GhosttyClipboardContent* candidate = &write->contents[index];
        if (selected == NULL) selected = candidate;
        if ((candidate->mime.len == 10 &&
             memcmp(candidate->mime.ptr, "text/plain", 10) == 0) ||
            (candidate->mime.len == 24 &&
             memcmp(candidate->mime.ptr, "text/plain;charset=utf-8", 24) == 0)) {
            selected = candidate;
            break;
        }
    }
    const uint8_t* data = selected == NULL ? NULL : selected->data.ptr;
    size_t len = selected == NULL ? 0 : selected->data.len;
    jbyteArray value = byte_array_from_native(env, data, len);
    jboolean accepted = JNI_FALSE;
    if (value != NULL) {
        accepted = (*env)->CallBooleanMethod(
            env, target, backend->callback_clipboard_write, value);
        (*env)->DeleteLocalRef(env, value);
    }
    (*env)->DeleteLocalRef(env, target);
    return accepted == JNI_TRUE ? GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS
                                : GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED;
}

static void dispatch_host_control(TermuxGhosttyBackend* backend)
{
    jobject target = callback_target_local(backend);
    if (target == NULL) return;
    JNIEnv* env = backend->callback_env;
    jbyteArray value = byte_array_from_native(
        env, backend->host_control_payload, backend->host_control_payload_len);
    if (value != NULL) {
        (*env)->CallVoidMethod(env, target, backend->callback_host_control, value);
        (*env)->DeleteLocalRef(env, value);
    }
    (*env)->DeleteLocalRef(env, target);
}

/* Preserve Termux's private OSC 8900 host channel without waking the Java VT parser. */
static void scan_host_control(TermuxGhosttyBackend* backend,
                              const uint8_t* data,
                              size_t len)
{
    static const uint8_t prefix[] = {'8', '9', '0', '0', ';'};
    for (size_t index = 0; index < len; index++) {
        uint8_t byte = data[index];
        switch (backend->host_control_state) {
            case 0: /* ground */
                if (byte == 0x1b) backend->host_control_state = 1;
                else if (byte == 0x9d) {
                    backend->host_control_state = 2;
                    backend->host_control_prefix_index = 0;
                }
                break;
            case 1: /* ESC */
                if (byte == ']') {
                    backend->host_control_state = 2;
                    backend->host_control_prefix_index = 0;
                } else {
                    backend->host_control_state = byte == 0x1b ? 1 : 0;
                }
                break;
            case 2: /* matching 8900; */
                if (byte == prefix[backend->host_control_prefix_index]) {
                    backend->host_control_prefix_index++;
                    if (backend->host_control_prefix_index == sizeof(prefix)) {
                        backend->host_control_state = 3;
                        backend->host_control_payload_len = 0;
                    }
                } else if (byte == 0x07) {
                    backend->host_control_state = 0;
                } else if (byte == 0x1b) {
                    backend->host_control_state = 6;
                } else {
                    backend->host_control_state = 5;
                }
                break;
            case 3: /* capturing payload */
                if (byte == 0x07) {
                    dispatch_host_control(backend);
                    backend->host_control_state = 0;
                } else if (byte == 0x1b) {
                    backend->host_control_state = 4;
                } else if (backend->host_control_payload_len <
                           TERMUX_GHOSTTY_MAX_HOST_CONTROL_BYTES) {
                    backend->host_control_payload[backend->host_control_payload_len++] = byte;
                } else {
                    backend->host_control_state = 5;
                }
                break;
            case 4: /* ESC while capturing */
                if (byte == '\\') {
                    dispatch_host_control(backend);
                    backend->host_control_state = 0;
                } else if (backend->host_control_payload_len + 2 <=
                           TERMUX_GHOSTTY_MAX_HOST_CONTROL_BYTES) {
                    backend->host_control_payload[backend->host_control_payload_len++] = 0x1b;
                    backend->host_control_payload[backend->host_control_payload_len++] = byte;
                    backend->host_control_state = 3;
                } else {
                    backend->host_control_state = byte == 0x1b ? 6 : 5;
                }
                break;
            case 5: /* discarding unrelated or oversized OSC */
                if (byte == 0x07) backend->host_control_state = 0;
                else if (byte == 0x1b) backend->host_control_state = 6;
                break;
            case 6: /* ESC while discarding */
                if (byte == '\\') backend->host_control_state = 0;
                else backend->host_control_state = byte == 0x1b ? 6 : 5;
                break;
            default:
                backend->host_control_state = 0;
                break;
        }
    }
}

static bool effect_size(GhosttyTerminal terminal,
                        void* userdata,
                        GhosttySizeReportSize* out_size)
{
    (void) terminal;
    TermuxGhosttyBackend* backend = userdata;
    if (backend == NULL || out_size == NULL) return false;
    out_size->rows = backend->rows;
    out_size->columns = backend->columns;
    out_size->cell_width = backend->cell_width;
    out_size->cell_height = backend->cell_height;
    return true;
}

static bool effect_color_scheme(GhosttyTerminal terminal,
                                void* userdata,
                                GhosttyColorScheme* out_scheme)
{
    (void) userdata;
    if (out_scheme == NULL) return false;
    GhosttyColorRgb background = {0};
    if (g_api.terminal_get(terminal, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND,
                           &background) != GHOSTTY_SUCCESS) return false;
    unsigned int luminance = 299U * background.r + 587U * background.g +
                             114U * background.b;
    *out_scheme = luminance >= 128000U ? GHOSTTY_COLOR_SCHEME_LIGHT
                                       : GHOSTTY_COLOR_SCHEME_DARK;
    return true;
}

static bool effect_device_attributes(GhosttyTerminal terminal,
                                     void* userdata,
                                     GhosttyDeviceAttributes* out_attrs)
{
    (void) terminal;
    (void) userdata;
    if (out_attrs == NULL) return false;
    memset(out_attrs, 0, sizeof(*out_attrs));
    static const uint16_t features[] = {
        GHOSTTY_DA_FEATURE_COLUMNS_132,
        GHOSTTY_DA_FEATURE_PRINTER,
        GHOSTTY_DA_FEATURE_SELECTIVE_ERASE,
        GHOSTTY_DA_FEATURE_NATIONAL_REPLACEMENT,
        GHOSTTY_DA_FEATURE_TECHNICAL_CHARACTERS,
        GHOSTTY_DA_FEATURE_WINDOWING,
        GHOSTTY_DA_FEATURE_HORIZONTAL_SCROLLING,
        GHOSTTY_DA_FEATURE_ANSI_COLOR,
    };
    out_attrs->primary.conformance_level = GHOSTTY_DA_CONFORMANCE_LEVEL_4;
    memcpy(out_attrs->primary.features, features, sizeof(features));
    out_attrs->primary.num_features = sizeof(features) / sizeof(features[0]);
    out_attrs->secondary.device_type = GHOSTTY_DA_DEVICE_TYPE_VT420;
    out_attrs->secondary.firmware_version = 320;
    return true;
}

static GhosttyString effect_xtversion(GhosttyTerminal terminal, void* userdata)
{
    (void) terminal;
    (void) userdata;
    static const char version[] = "Termux Ghostty/15484b6";
    GhosttyString result = {.ptr = (const uint8_t*) version, .len = sizeof(version) - 1};
    return result;
}

static bool terminal_mode(GhosttyTerminal terminal, GhosttyMode mode)
{
    bool value = false;
    return g_api.terminal_mode_get(terminal, mode, &value) == GHOSTTY_SUCCESS && value;
}

static uint64_t palette_hash(const GhosttyColorRgb* palette)
{
    uint64_t hash = UINT64_C(1469598103934665603);
    for (size_t index = 0; index < 256; index++) {
        hash ^= palette[index].r;
        hash *= UINT64_C(1099511628211);
        hash ^= palette[index].g;
        hash *= UINT64_C(1099511628211);
        hash ^= palette[index].b;
        hash *= UINT64_C(1099511628211);
    }
    return hash;
}

static bool fill_state_locked(TermuxGhosttyBackend* backend, jlong* values)
{
    uint16_t columns = 0, rows = 0, cursor_x = 0, cursor_y = 0;
    bool cursor_visible = false, mouse_tracking = false, vt_processing_error = false;
    GhosttyTerminalScreen active_screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    GhosttyTerminalScrollbar scrollbar = {0};
    size_t total_rows = 0, scrollback_rows = 0;
    GhosttyColorRgb foreground = {0}, background = {0}, cursor = {0};
    GhosttyColorRgb palette[256] = {{0}};
    GhosttyRenderStateCursorVisualStyle cursor_style =
        GHOSTTY_RENDER_STATE_CURSOR_VISUAL_STYLE_BLOCK;

    if (g_api.render_state_update(backend->render_state, backend->terminal) != GHOSTTY_SUCCESS) {
        return false;
    }

#if TERMUX_GHOSTTY_RENDER_BATCH_API
    static const GhosttyTerminalData terminal_keys[] = {
        GHOSTTY_TERMINAL_DATA_COLS,
        GHOSTTY_TERMINAL_DATA_ROWS,
        GHOSTTY_TERMINAL_DATA_CURSOR_X,
        GHOSTTY_TERMINAL_DATA_CURSOR_Y,
        GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE,
        GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,
        GHOSTTY_TERMINAL_DATA_SCROLLBAR,
        GHOSTTY_TERMINAL_DATA_TOTAL_ROWS,
        GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
        GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING,
        GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR,
    };
    void* terminal_values[] = {
        &columns,
        &rows,
        &cursor_x,
        &cursor_y,
        &cursor_visible,
        &active_screen,
        &scrollbar,
        &total_rows,
        &scrollback_rows,
        &mouse_tracking,
        &vt_processing_error,
    };
    size_t terminal_written = 0;
    GhosttyRenderStateColors render_colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
    GhosttyResult terminal_result = g_api.terminal_get_multi(
        backend->terminal, sizeof(terminal_keys) / sizeof(terminal_keys[0]),
        terminal_keys, terminal_values, &terminal_written);
    if (!multi_complete(terminal_result, terminal_written,
                        sizeof(terminal_keys) / sizeof(terminal_keys[0])) ||
        g_api.render_state_colors_get(backend->render_state, &render_colors) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
                               &cursor_style) != GHOSTTY_SUCCESS) {
        return false;
    }
    foreground = render_colors.foreground;
    background = render_colors.background;
    cursor = render_colors.cursor_has_value ? render_colors.cursor : foreground;
    memcpy(palette, render_colors.palette, sizeof(palette));
#else
    if (g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLS, &columns) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_ROWS, &rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_X, &cursor_x) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &cursor_y) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE, &cursor_visible) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active_screen) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &scrollbar) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS, &total_rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &scrollback_rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING, &mouse_tracking) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLOR_FOREGROUND, &foreground) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLOR_BACKGROUND, &background) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLOR_CURSOR, &cursor) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLOR_PALETTE, palette) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR, &vt_processing_error) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state,
                               GHOSTTY_RENDER_STATE_DATA_CURSOR_VISUAL_STYLE,
                               &cursor_style) != GHOSTTY_SUCCESS) return false;
#endif

    uint64_t modes = 0;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_DECCKM)) modes |= TERMUX_GHOSTTY_MODE_CURSOR_KEYS;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_REVERSE_COLORS)) modes |= TERMUX_GHOSTTY_MODE_REVERSE_VIDEO;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_KEYPAD_KEYS)) modes |= TERMUX_GHOSTTY_MODE_KEYPAD;
    if (mouse_tracking) modes |= TERMUX_GHOSTTY_MODE_MOUSE;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_BUTTON_MOUSE) ||
        terminal_mode(backend->terminal, GHOSTTY_MODE_ANY_MOUSE)) modes |= TERMUX_GHOSTTY_MODE_MOUSE_MOTION;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_SGR_MOUSE)) modes |= TERMUX_GHOSTTY_MODE_MOUSE_SGR;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_FOCUS_EVENT)) modes |= TERMUX_GHOSTTY_MODE_FOCUS;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_BRACKETED_PASTE)) modes |= TERMUX_GHOSTTY_MODE_BRACKETED_PASTE;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_SYNC_OUTPUT)) modes |= TERMUX_GHOSTTY_MODE_SYNC_OUTPUT;
    if (terminal_mode(backend->terminal, GHOSTTY_MODE_WRAPAROUND)) modes |= TERMUX_GHOSTTY_MODE_WRAPAROUND;

    backend->render_updates++;
    backend->state_generation++;
    values[0] = 1;
    values[1] = columns;
    values[2] = rows;
    values[3] = cursor_x;
    values[4] = cursor_y;
    values[5] = cursor_visible ? 1 : 0;
    values[6] = cursor_style;
    values[7] = active_screen;
    values[8] = (jlong) scrollback_rows;
    values[9] = (jlong) total_rows;
    values[10] = (jlong) scrollbar.total;
    values[11] = (jlong) scrollbar.offset;
    values[12] = (jlong) scrollbar.len;
    values[13] = (jlong) modes;
    values[14] = (jlong) (uint64_t) rgb_to_argb(foreground);
    values[15] = (jlong) (uint64_t) rgb_to_argb(background);
    values[16] = (jlong) (uint64_t) rgb_to_argb(cursor);
    values[17] = vt_processing_error ? 1 : 0;
    values[18] = (jlong) palette_hash(palette);
    values[19] = (jlong) backend->state_generation;
    values[20] = (jlong) backend->bytes;
    values[21] = (jlong) backend->writes;
    return true;
}

static bool apply_colors_locked(JNIEnv* env,
                                TermuxGhosttyBackend* backend,
                                jintArray colors_array)
{
    if (colors_array == NULL || (*env)->GetArrayLength(env, colors_array) < 259) return false;
    jint colors[259];
    (*env)->GetIntArrayRegion(env, colors_array, 0, 259, colors);
    if ((*env)->ExceptionCheck(env)) return false;
    GhosttyColorRgb palette[256];
    for (size_t index = 0; index < 256; index++) {
        uint32_t value = (uint32_t) colors[index];
        palette[index] = (GhosttyColorRgb) {
            .r = (uint8_t) (value >> 16U),
            .g = (uint8_t) (value >> 8U),
            .b = (uint8_t) value,
        };
    }
    uint32_t fg_value = (uint32_t) colors[256];
    uint32_t bg_value = (uint32_t) colors[257];
    uint32_t cursor_value = (uint32_t) colors[258];
    GhosttyColorRgb foreground = {
        .r = (uint8_t) (fg_value >> 16U), .g = (uint8_t) (fg_value >> 8U), .b = (uint8_t) fg_value};
    GhosttyColorRgb background = {
        .r = (uint8_t) (bg_value >> 16U), .g = (uint8_t) (bg_value >> 8U), .b = (uint8_t) bg_value};
    GhosttyColorRgb cursor = {
        .r = (uint8_t) (cursor_value >> 16U), .g = (uint8_t) (cursor_value >> 8U), .b = (uint8_t) cursor_value};
    return g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, palette) == GHOSTTY_SUCCESS &&
           g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &foreground) == GHOSTTY_SUCCESS &&
           g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &background) == GHOSTTY_SUCCESS &&
           g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &cursor) == GHOSTTY_SUCCESS;
}

static void free_backend(JNIEnv* env, TermuxGhosttyBackend* backend)
{
    if (backend == NULL) return;
    if (backend->resize_anchor != NULL) g_api.tracked_grid_ref_free(backend->resize_anchor);
    if (backend->key_event != NULL) g_api.key_event_free(backend->key_event);
    if (backend->key_encoder != NULL) g_api.key_encoder_free(backend->key_encoder);
    if (backend->mouse_event != NULL) g_api.mouse_event_free(backend->mouse_event);
    if (backend->mouse_encoder != NULL) g_api.mouse_encoder_free(backend->mouse_encoder);
    if (backend->row_cells != NULL) g_api.row_cells_free(backend->row_cells);
    if (backend->row_iterator != NULL) g_api.row_iterator_free(backend->row_iterator);
    if (backend->render_state != NULL) g_api.render_state_free(backend->render_state);
    if (backend->terminal != NULL) g_api.terminal_free(backend->terminal);
    if (env != NULL && backend->callback_target != NULL) {
        (*env)->DeleteWeakGlobalRef(env, backend->callback_target);
    }
    free(backend->grapheme_codepoints);
    free_render_delta_row_cache(backend);
    pthread_mutex_destroy(&backend->render_mutex);
    pthread_mutex_destroy(&backend->mutex);
    free(backend);
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeIsAvailable(JNIEnv* env, jclass clazz)
{
    (void) env;
    (void) clazz;
    return ensure_api() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeLibraryInfo(JNIEnv* env, jclass clazz)
{
    (void) clazz;
    if (!ensure_api()) return (*env)->NewStringUTF(env, g_api.error);

#if defined(__aarch64__)
    const char* abi = "arm64-v8a";
#elif defined(__arm__)
    const char* abi = "armeabi-v7a";
#elif defined(__x86_64__)
    const char* abi = "x86_64";
#elif defined(__i386__)
    const char* abi = "x86";
#else
    const char* abi = "unknown";
#endif
    const char* optimize = "unknown";
    switch (g_api.optimize) {
        case GHOSTTY_OPTIMIZE_DEBUG: optimize = "Debug"; break;
        case GHOSTTY_OPTIMIZE_RELEASE_SAFE: optimize = "ReleaseSafe"; break;
        case GHOSTTY_OPTIMIZE_RELEASE_SMALL: optimize = "ReleaseSmall"; break;
        case GHOSTTY_OPTIMIZE_RELEASE_FAST: optimize = "ReleaseFast"; break;
        default: break;
    }

    const char* render_query = TERMUX_GHOSTTY_RENDER_BATCH_API ? "batch-v1" : "scalar-compat";
    const char* grapheme_utf8 = TERMUX_GHOSTTY_RENDER_BATCH_API ? "ghostty" : "termux-compat";
    const char* cell_metadata = TERMUX_GHOSTTY_RENDER_BATCH_API ? "batch-v1" : "scalar-compat";
    const char* color_query = TERMUX_GHOSTTY_RENDER_BATCH_API ? "selective" : "always";
    char info[448];
    snprintf(info, sizeof(info),
             "libghostty-vt/%s commit=%s abi=%s simd=%s optimize=%s render_query=%s "
             "grapheme_utf8=%s cell_metadata=%s color_query=%s",
             g_api.version,
             TERMUX_GHOSTTY_PINNED_COMMIT,
             abi,
             g_api.simd ? "true" : "false",
             optimize,
             render_query,
             grapheme_utf8,
             cell_metadata,
             color_query);
    return (*env)->NewStringUTF(env, info);
}

JNIEXPORT jlong JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeOpen(JNIEnv* env,
                                                            jclass clazz,
                                                            jint columns,
                                                            jint rows,
                                                            jlong max_scrollback,
                                                            jint cell_width,
                                                            jint cell_height,
                                                            jobject callback_target,
                                                            jintArray colors)
{
    (void) clazz;
    if (!ensure_api() || columns <= 0 || columns > UINT16_MAX || rows <= 0 || rows > UINT16_MAX ||
        max_scrollback < 0 || (uint64_t) max_scrollback > (uint64_t) SIZE_MAX ||
        cell_width < 0 || cell_height < 0 ||
        callback_target == NULL || colors == NULL) return 0;

    TermuxGhosttyBackend* backend = calloc(1, sizeof(*backend));
    if (backend == NULL) return 0;
    if (pthread_mutex_init(&backend->mutex, NULL) != 0) {
        free(backend);
        return 0;
    }
    if (pthread_mutex_init(&backend->render_mutex, NULL) != 0) {
        pthread_mutex_destroy(&backend->mutex);
        free(backend);
        return 0;
    }
    if ((*env)->GetJavaVM(env, &backend->java_vm) != JNI_OK) {
        free_backend(env, backend);
        return 0;
    }
    backend->callback_target = (*env)->NewWeakGlobalRef(env, callback_target);
    jclass callback_class = (*env)->GetObjectClass(env, callback_target);
    if (backend->callback_target == NULL || callback_class == NULL) {
        free_backend(env, backend);
        return 0;
    }
    backend->callback_write_pty =
        (*env)->GetMethodID(env, callback_class, "onNativeWritePty", "([B)V");
    backend->callback_bell =
        (*env)->GetMethodID(env, callback_class, "onNativeBell", "()V");
    backend->callback_title_changed =
        (*env)->GetMethodID(env, callback_class, "onNativeTitleChanged", "([B)V");
    backend->callback_clipboard_write =
        (*env)->GetMethodID(env, callback_class, "onNativeClipboardWrite", "([B)Z");
    backend->callback_host_control =
        (*env)->GetMethodID(env, callback_class, "onNativeHostControl", "([B)V");
    (*env)->DeleteLocalRef(env, callback_class);
    if (backend->callback_write_pty == NULL || backend->callback_bell == NULL ||
        backend->callback_title_changed == NULL || backend->callback_clipboard_write == NULL ||
        backend->callback_host_control == NULL ||
        (*env)->ExceptionCheck(env)) {
        free_backend(env, backend);
        return 0;
    }

    GhosttyTerminalOptions options = {
        .cols = (uint16_t) columns,
        .rows = (uint16_t) rows,
        .max_scrollback = (size_t) max_scrollback,
    };
    if (g_api.terminal_new(NULL, &backend->terminal, options) != GHOSTTY_SUCCESS ||
        g_api.render_state_new(NULL, &backend->render_state) != GHOSTTY_SUCCESS ||
        g_api.row_iterator_new(NULL, &backend->row_iterator) != GHOSTTY_SUCCESS ||
        g_api.row_cells_new(NULL, &backend->row_cells) != GHOSTTY_SUCCESS ||
        g_api.mouse_encoder_new(NULL, &backend->mouse_encoder) != GHOSTTY_SUCCESS ||
        g_api.mouse_event_new(NULL, &backend->mouse_event) != GHOSTTY_SUCCESS ||
        g_api.key_encoder_new(NULL, &backend->key_encoder) != GHOSTTY_SUCCESS ||
        g_api.key_event_new(NULL, &backend->key_event) != GHOSTTY_SUCCESS) {
        free_backend(env, backend);
        return 0;
    }
    backend->columns = (uint16_t) columns;
    backend->rows = (uint16_t) rows;
    backend->cell_width = (uint32_t) cell_width;
    backend->cell_height = (uint32_t) cell_height;
    if (g_api.terminal_resize(backend->terminal, (uint16_t) columns, (uint16_t) rows,
                              (uint32_t) cell_width, (uint32_t) cell_height) != GHOSTTY_SUCCESS ||
        !apply_colors_locked(env, backend, colors) ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_USERDATA, backend) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_WRITE_PTY,
                           (const void*) effect_write_pty) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_BELL,
                           (const void*) effect_bell) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_TITLE_CHANGED,
                           (const void*) effect_title_changed) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE,
                           (const void*) effect_clipboard_write) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_SIZE,
                           (const void*) effect_size) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_COLOR_SCHEME,
                           (const void*) effect_color_scheme) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES,
                           (const void*) effect_device_attributes) != GHOSTTY_SUCCESS ||
        g_api.terminal_set(backend->terminal, GHOSTTY_TERMINAL_OPT_XTVERSION,
                           (const void*) effect_xtversion) != GHOSTTY_SUCCESS) {
        free_backend(env, backend);
        return 0;
    }
    return (jlong) (intptr_t) backend;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeWrite(JNIEnv* env,
                                                             jclass clazz,
                                                             jlong handle,
                                                             jbyteArray input_array,
                                                             jint length,
                                                             jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || input_array == NULL || state_array == NULL || length < 0 ||
        length > (*env)->GetArrayLength(env, input_array) ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;

    pthread_mutex_lock(&backend->mutex);
    jbyte* input = (*env)->GetByteArrayElements(env, input_array, NULL);
    if (input == NULL) {
        pthread_mutex_unlock(&backend->mutex);
        return JNI_FALSE;
    }
    backend->callback_env = env;
    g_api.terminal_vt_write(backend->terminal, (const uint8_t*) input, (size_t) length);
    scan_host_control(backend, (const uint8_t*) input, (size_t) length);
    backend->writes++;
    backend->bytes += (uint64_t) length;
    backend->callback_env = NULL;
    (*env)->ReleaseByteArrayElements(env, input_array, input, JNI_ABORT);
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    bool success = !(*env)->ExceptionCheck(env) && fill_state_locked(backend, state);
    pthread_mutex_unlock(&backend->mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeResize(JNIEnv* env,
                                                              jclass clazz,
                                                              jlong handle,
                                                              jint columns,
                                                              jint rows,
                                                              jint cell_width,
                                                              jint cell_height,
                                                              jint viewport_top_row,
                                                              jint anchor_column,
                                                              jint anchor_viewport_row,
                                                              jint target_viewport_row,
                                                              jint visible_scrollback_rows,
                                                              jlongArray anchor_array,
                                                              jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || columns <= 0 || columns > UINT16_MAX || rows <= 0 ||
        rows > UINT16_MAX || cell_width < 0 || cell_height < 0 ||
        visible_scrollback_rows < 0 || anchor_array == NULL ||
        (*env)->GetArrayLength(env, anchor_array) < TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT ||
        state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    pthread_mutex_lock(&backend->render_mutex);
    pthread_mutex_lock(&backend->mutex);
    uint16_t old_columns = backend->columns;
    uint16_t old_rows = backend->rows;
    uint32_t old_cell_width = backend->cell_width;
    uint32_t old_cell_height = backend->cell_height;
    backend->columns = (uint16_t) columns;
    backend->rows = (uint16_t) rows;
    backend->cell_width = (uint32_t) cell_width;
    backend->cell_height = (uint32_t) cell_height;

    jlong anchor[TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT];
    for (size_t index = 0; index < TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT; index++) {
        anchor[index] = -1;
    }
    bool anchor_requested = anchor_column >= 0 && anchor_column < old_columns &&
                            anchor_viewport_row >= -1 && anchor_viewport_row <= old_rows &&
                            target_viewport_row >= 0 && target_viewport_row < rows;
    bool anchor_tracked = false;
    bool anchor_precondition_exact = false;
    anchor[0] = anchor_requested ? 1 : 0;
    anchor[1] = 0;
    anchor[2] = 0;
    anchor[3] = viewport_top_row;
    anchor[4] = anchor_viewport_row;
    anchor[5] = target_viewport_row;
    if (anchor_requested) {
        size_t old_scrollback_rows = 0;
        GhosttyTerminalScrollbar old_scrollbar = {0};
        bool old_state_valid =
            g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                               &old_scrollback_rows) == GHOSTTY_SUCCESS;
        int64_t old_scrollback_i64 = old_scrollback_rows > (size_t) INT64_MAX
            ? INT64_MAX : (int64_t) old_scrollback_rows;
        int64_t old_requested_offset = viewport_top_row > 0
            ? old_scrollback_i64
            : old_scrollback_i64 + (int64_t) viewport_top_row;
        size_t old_committed_offset = old_requested_offset <= 0
            ? 0U
            : (uint64_t) old_requested_offset >= (uint64_t) old_scrollback_rows
                ? old_scrollback_rows
                : (size_t) old_requested_offset;
        anchor[8] = old_state_valid ? (jlong) old_scrollback_rows : -1;
        anchor[9] = old_state_valid ? (jlong) old_requested_offset : -1;

        GhosttyTerminalScrollViewport viewport = {
            .tag = GHOSTTY_SCROLL_VIEWPORT_ROW,
            .value.row = old_committed_offset,
        };
        g_api.terminal_scroll_viewport(backend->terminal, viewport);
        old_state_valid = old_state_valid &&
            g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                               &old_scrollbar) == GHOSTTY_SUCCESS;
        anchor[10] = old_state_valid ? (jlong) old_scrollbar.offset : -1;

        int64_t expected_screen_row = old_state_valid
            ? (int64_t) old_scrollbar.offset + (int64_t) anchor_viewport_row : -1;
        uint64_t old_total_rows = (uint64_t) old_scrollback_rows + old_rows;
        bool anchor_point_valid = old_state_valid && expected_screen_row >= 0 &&
            (uint64_t) expected_screen_row < old_total_rows &&
            (uint64_t) expected_screen_row <= UINT32_MAX;
        GhosttyPoint point = {
            .tag = anchor_viewport_row >= 0 && anchor_viewport_row < old_rows
                ? GHOSTTY_POINT_TAG_VIEWPORT : GHOSTTY_POINT_TAG_SCREEN,
            .value.coordinate = {
                .x = (uint16_t) anchor_column,
                .y = (uint32_t) (anchor_viewport_row >= 0 &&
                    anchor_viewport_row < old_rows
                        ? anchor_viewport_row : expected_screen_row),
            },
        };
        GhosttyResult track_result = GHOSTTY_INVALID_VALUE;
        if (anchor_point_valid) {
            track_result = backend->resize_anchor == NULL
                ? g_api.terminal_grid_ref_track(backend->terminal, point,
                                                &backend->resize_anchor)
                : g_api.tracked_grid_ref_set(backend->resize_anchor,
                                             backend->terminal, point);
        }
        anchor_tracked = track_result == GHOSTTY_SUCCESS && backend->resize_anchor != NULL;
        anchor[1] = anchor_tracked ? 1 : 0;
        if (old_state_valid && anchor_tracked) {
            GhosttyPointCoordinate old_screen_point = {0};
            if (g_api.tracked_grid_ref_point(backend->resize_anchor,
                                             GHOSTTY_POINT_TAG_SCREEN,
                                             &old_screen_point) == GHOSTTY_SUCCESS) {
                anchor[11] = (jlong) old_screen_point.y;
                anchor_precondition_exact = expected_screen_row == old_screen_point.y &&
                    old_scrollbar.offset == old_committed_offset;
            }
        }
        anchor[12] = anchor_precondition_exact ? 1 : 0;
    }

    backend->callback_env = env;
    GhosttyResult result = g_api.terminal_resize(backend->terminal,
                                                 (uint16_t) columns,
                                                 (uint16_t) rows,
                                                 (uint32_t) cell_width,
                                                 (uint32_t) cell_height);
    backend->callback_env = NULL;

    if (result == GHOSTTY_SUCCESS && anchor_requested) {
        GhosttyPointCoordinate screen_point = {0};
        size_t scrollback_rows = 0;
        bool new_state_valid =
            g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                               &scrollback_rows) == GHOSTTY_SUCCESS;
        size_t accessible_rows = (size_t) visible_scrollback_rows;
        if (accessible_rows > scrollback_rows) accessible_rows = scrollback_rows;
        size_t minimum_offset = scrollback_rows - accessible_rows;
        size_t maximum_offset = scrollback_rows;
        anchor[13] = new_state_valid ? (jlong) scrollback_rows : -1;
        anchor[16] = new_state_valid ? (jlong) minimum_offset : -1;
        anchor[17] = new_state_valid ? (jlong) maximum_offset : -1;

        bool tracked_after_resize = anchor_tracked && anchor_precondition_exact &&
            g_api.tracked_grid_ref_point(backend->resize_anchor, GHOSTTY_POINT_TAG_SCREEN,
                                         &screen_point) == GHOSTTY_SUCCESS;
        int64_t requested_offset;
        if (tracked_after_resize) {
            requested_offset = (int64_t) screen_point.y - (int64_t) target_viewport_row;
            anchor[14] = (jlong) screen_point.y;
        } else {
            /*
             * A tracked cell can become unavailable only when resize legitimately discards it.
             * Preserve the prior relative viewport in that case; never inherit Ghostty's
             * internal resize viewport or jump to an arbitrary history edge.
             */
            int64_t scrollback_i64 = scrollback_rows > (size_t) INT64_MAX
                ? INT64_MAX : (int64_t) scrollback_rows;
            requested_offset = viewport_top_row > 0
                ? scrollback_i64
                : scrollback_i64 + (int64_t) viewport_top_row;
        }
        anchor[15] = new_state_valid ? (jlong) requested_offset : -1;

        if (new_state_valid) {
            size_t clamped_offset = requested_offset <= (int64_t) minimum_offset
                ? minimum_offset
                : (uint64_t) requested_offset >= (uint64_t) maximum_offset
                    ? maximum_offset
                    : (size_t) requested_offset;
            GhosttyTerminalScrollViewport viewport = {
                .tag = GHOSTTY_SCROLL_VIEWPORT_ROW,
                .value.row = clamped_offset,
            };
            g_api.terminal_scroll_viewport(backend->terminal, viewport);
            anchor[7] = (jlong) clamped_offset;

            GhosttyTerminalScrollbar committed_scrollbar = {0};
            if (g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBAR,
                                   &committed_scrollbar) == GHOSTTY_SUCCESS) {
                anchor[19] = (jlong) committed_scrollbar.offset;
                int64_t committed_top = (int64_t) committed_scrollbar.offset -
                    (int64_t) scrollback_rows;
                anchor[18] = (jlong) committed_top;
                if (tracked_after_resize) {
                    int64_t resolved_row = (int64_t) screen_point.y -
                        (int64_t) committed_scrollbar.offset;
                    anchor[6] = (jlong) resolved_row;
                    bool requested_inside_bounds = requested_offset >=
                            (int64_t) minimum_offset &&
                        (uint64_t) requested_offset <= (uint64_t) maximum_offset;
                    bool commit_round_tripped =
                        committed_scrollbar.offset == clamped_offset;
                    if (commit_round_tripped && requested_inside_bounds &&
                        resolved_row == target_viewport_row) {
                        anchor[2] = 1;
                    } else if (commit_round_tripped && !requested_inside_bounds) {
                        /*
                         * Outcome 2 is reserved for a mathematically unavoidable history/live
                         * boundary clamp. A failed commit or an in-bounds focal mismatch remains
                         * outcome 0 so Java cannot mistake a broken transaction for a valid clamp.
                         */
                        anchor[2] = 2;
                    }
                }
            }
        }
    }

    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    bool success = result == GHOSTTY_SUCCESS && !(*env)->ExceptionCheck(env) &&
                   fill_state_locked(backend, state);
    if (!success) {
        backend->columns = old_columns;
        backend->rows = old_rows;
        backend->cell_width = old_cell_width;
        backend->cell_height = old_cell_height;
    } else {
        /* Resize/reflow may repin Ghostty's viewport even when the Java top row is unchanged. */
        backend->render_delta_initialized = false;
    }
    pthread_mutex_unlock(&backend->mutex);
    pthread_mutex_unlock(&backend->render_mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, anchor_array, 0,
                              TERMUX_GHOSTTY_RESIZE_ANCHOR_COUNT, anchor);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeState(JNIEnv* env,
                                                             jclass clazz,
                                                             jlong handle,
                                                             jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    pthread_mutex_lock(&backend->mutex);
    bool success = fill_state_locked(backend, state);
    pthread_mutex_unlock(&backend->mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeReset(JNIEnv* env,
                                                             jclass clazz,
                                                             jlong handle,
                                                             jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    pthread_mutex_lock(&backend->render_mutex);
    pthread_mutex_lock(&backend->mutex);
    backend->callback_env = env;
    g_api.terminal_reset(backend->terminal);
    backend->callback_env = NULL;
    bool success = !(*env)->ExceptionCheck(env) && fill_state_locked(backend, state);
    if (success) backend->render_delta_initialized = false;
    pthread_mutex_unlock(&backend->mutex);
    pthread_mutex_unlock(&backend->render_mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSetColors(JNIEnv* env,
                                                                 jclass clazz,
                                                                 jlong handle,
                                                                 jintArray colors,
                                                                 jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || colors == NULL || state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    pthread_mutex_lock(&backend->mutex);
    bool success = apply_colors_locked(env, backend, colors) &&
                   !(*env)->ExceptionCheck(env) && fill_state_locked(backend, state);
    pthread_mutex_unlock(&backend->mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSetDefaultCursorStyle(
    JNIEnv* env,
    jclass clazz,
    jlong handle,
    jint termux_style,
    jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || termux_style < 0 || termux_style > 2 || state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    GhosttyTerminalCursorStyle style = termux_style == 1
        ? GHOSTTY_TERMINAL_CURSOR_STYLE_UNDERLINE
        : termux_style == 2
            ? GHOSTTY_TERMINAL_CURSOR_STYLE_BAR
            : GHOSTTY_TERMINAL_CURSOR_STYLE_BLOCK;
    static const uint8_t apply_default[] = {0x1b, '[', '0', ' ', 'q'};
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    pthread_mutex_lock(&backend->mutex);
    bool success = g_api.terminal_set(
                       backend->terminal,
                       GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE,
                       &style) == GHOSTTY_SUCCESS;
    if (success) {
        backend->callback_env = env;
        g_api.terminal_vt_write(
            backend->terminal, apply_default, sizeof(apply_default));
        backend->callback_env = NULL;
        success = !(*env)->ExceptionCheck(env) && fill_state_locked(backend, state);
    }
    pthread_mutex_unlock(&backend->mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSetMode(JNIEnv* env,
                                                               jclass clazz,
                                                               jlong handle,
                                                               jint mode_value,
                                                               jboolean value,
                                                               jlongArray state_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || mode_value < 0 || mode_value > 32767 || state_array == NULL ||
        (*env)->GetArrayLength(env, state_array) < TERMUX_GHOSTTY_STATE_COUNT) return JNI_FALSE;
    jlong state[TERMUX_GHOSTTY_STATE_COUNT] = {0};
    pthread_mutex_lock(&backend->mutex);
    bool success = g_api.terminal_mode_set(backend->terminal,
                                            ghostty_mode_new((uint16_t) mode_value, false),
                                            value == JNI_TRUE) == GHOSTTY_SUCCESS &&
                   fill_state_locked(backend, state);
    pthread_mutex_unlock(&backend->mutex);
    if (!success) return JNI_FALSE;
    (*env)->SetLongArrayRegion(env, state_array, 0, TERMUX_GHOSTTY_STATE_COUNT, state);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

static GhosttyKey android_key_to_ghostty(jint key)
{
    if (key >= 7 && key <= 16) {
        static const GhosttyKey digits[] = {
            GHOSTTY_KEY_DIGIT_0, GHOSTTY_KEY_DIGIT_1, GHOSTTY_KEY_DIGIT_2,
            GHOSTTY_KEY_DIGIT_3, GHOSTTY_KEY_DIGIT_4, GHOSTTY_KEY_DIGIT_5,
            GHOSTTY_KEY_DIGIT_6, GHOSTTY_KEY_DIGIT_7, GHOSTTY_KEY_DIGIT_8,
            GHOSTTY_KEY_DIGIT_9,
        };
        return digits[key - 7];
    }
    if (key >= 29 && key <= 54) return (GhosttyKey) (GHOSTTY_KEY_A + key - 29);
    switch (key) {
        case 4: return GHOSTTY_KEY_ESCAPE;             /* KEYCODE_BACK */
        case 19: return GHOSTTY_KEY_ARROW_UP;
        case 20: return GHOSTTY_KEY_ARROW_DOWN;
        case 21: return GHOSTTY_KEY_ARROW_LEFT;
        case 22: return GHOSTTY_KEY_ARROW_RIGHT;
        case 23: return GHOSTTY_KEY_ENTER;             /* DPAD_CENTER */
        case 55: return GHOSTTY_KEY_COMMA;
        case 56: return GHOSTTY_KEY_PERIOD;
        case 57: return GHOSTTY_KEY_ALT_LEFT;
        case 58: return GHOSTTY_KEY_ALT_RIGHT;
        case 59: return GHOSTTY_KEY_SHIFT_LEFT;
        case 60: return GHOSTTY_KEY_SHIFT_RIGHT;
        case 61: return GHOSTTY_KEY_TAB;
        case 62: return GHOSTTY_KEY_SPACE;
        case 66: return GHOSTTY_KEY_ENTER;
        case 67: return GHOSTTY_KEY_BACKSPACE;
        case 68: return GHOSTTY_KEY_BACKQUOTE;
        case 69: return GHOSTTY_KEY_MINUS;
        case 70: return GHOSTTY_KEY_EQUAL;
        case 71: return GHOSTTY_KEY_BRACKET_LEFT;
        case 72: return GHOSTTY_KEY_BRACKET_RIGHT;
        case 73: return GHOSTTY_KEY_BACKSLASH;
        case 74: return GHOSTTY_KEY_SEMICOLON;
        case 75: return GHOSTTY_KEY_QUOTE;
        case 76: return GHOSTTY_KEY_SLASH;
        case 82: return GHOSTTY_KEY_CONTEXT_MENU;
        case 92: return GHOSTTY_KEY_PAGE_UP;
        case 93: return GHOSTTY_KEY_PAGE_DOWN;
        case 111: return GHOSTTY_KEY_ESCAPE;
        case 112: return GHOSTTY_KEY_DELETE;
        case 113: return GHOSTTY_KEY_CONTROL_LEFT;
        case 114: return GHOSTTY_KEY_CONTROL_RIGHT;
        case 115: return GHOSTTY_KEY_CAPS_LOCK;
        case 116: return GHOSTTY_KEY_SCROLL_LOCK;
        case 117: return GHOSTTY_KEY_META_LEFT;
        case 118: return GHOSTTY_KEY_META_RIGHT;
        case 119: return GHOSTTY_KEY_FN;
        case 120: return GHOSTTY_KEY_PRINT_SCREEN;
        case 121: return GHOSTTY_KEY_PAUSE;
        case 122: return GHOSTTY_KEY_HOME;
        case 123: return GHOSTTY_KEY_END;
        case 124: return GHOSTTY_KEY_INSERT;
        case 131: return GHOSTTY_KEY_F1;
        case 132: return GHOSTTY_KEY_F2;
        case 133: return GHOSTTY_KEY_F3;
        case 134: return GHOSTTY_KEY_F4;
        case 135: return GHOSTTY_KEY_F5;
        case 136: return GHOSTTY_KEY_F6;
        case 137: return GHOSTTY_KEY_F7;
        case 138: return GHOSTTY_KEY_F8;
        case 139: return GHOSTTY_KEY_F9;
        case 140: return GHOSTTY_KEY_F10;
        case 141: return GHOSTTY_KEY_F11;
        case 142: return GHOSTTY_KEY_F12;
        case 143: return GHOSTTY_KEY_NUM_LOCK;
        case 144: return GHOSTTY_KEY_NUMPAD_0;
        case 145: return GHOSTTY_KEY_NUMPAD_1;
        case 146: return GHOSTTY_KEY_NUMPAD_2;
        case 147: return GHOSTTY_KEY_NUMPAD_3;
        case 148: return GHOSTTY_KEY_NUMPAD_4;
        case 149: return GHOSTTY_KEY_NUMPAD_5;
        case 150: return GHOSTTY_KEY_NUMPAD_6;
        case 151: return GHOSTTY_KEY_NUMPAD_7;
        case 152: return GHOSTTY_KEY_NUMPAD_8;
        case 153: return GHOSTTY_KEY_NUMPAD_9;
        case 154: return GHOSTTY_KEY_NUMPAD_DIVIDE;
        case 155: return GHOSTTY_KEY_NUMPAD_MULTIPLY;
        case 156: return GHOSTTY_KEY_NUMPAD_SUBTRACT;
        case 157: return GHOSTTY_KEY_NUMPAD_ADD;
        case 158: return GHOSTTY_KEY_NUMPAD_DECIMAL;
        case 159: return GHOSTTY_KEY_NUMPAD_COMMA;
        case 160: return GHOSTTY_KEY_NUMPAD_ENTER;
        case 161: return GHOSTTY_KEY_NUMPAD_EQUAL;
        case 162: return GHOSTTY_KEY_NUMPAD_PAREN_LEFT;
        case 163: return GHOSTTY_KEY_NUMPAD_PAREN_RIGHT;
        default: return GHOSTTY_KEY_UNIDENTIFIED;
    }
}

static bool ghostty_key_is_printable(GhosttyKey key)
{
    return (key >= GHOSTTY_KEY_BACKQUOTE && key <= GHOSTTY_KEY_SLASH) ||
           key == GHOSTTY_KEY_SPACE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeEncodeKey(JNIEnv* env,
                                                                 jclass clazz,
                                                                 jlong handle,
                                                                 jint android_key,
                                                                 jint key_mod,
                                                                 jint action)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    GhosttyKey key = android_key_to_ghostty(android_key);
    if (backend == NULL || key == GHOSTTY_KEY_UNIDENTIFIED || action < 0 || action > 2 ||
        (action != GHOSTTY_KEY_ACTION_RELEASE && ghostty_key_is_printable(key))) {
        return NULL;
    }
    uint32_t mods_in = (uint32_t) key_mod;
    GhosttyMods mods = 0;
    if ((mods_in & UINT32_C(0x20000000)) != 0) mods |= GHOSTTY_MODS_SHIFT;
    if ((mods_in & UINT32_C(0x40000000)) != 0) mods |= GHOSTTY_MODS_CTRL;
    if ((mods_in & UINT32_C(0x80000000)) != 0) mods |= GHOSTTY_MODS_ALT;
    if ((mods_in & UINT32_C(0x10000000)) != 0) mods |= GHOSTTY_MODS_NUM_LOCK;

    pthread_mutex_lock(&backend->mutex);
    g_api.key_encoder_setopt_from_terminal(backend->key_encoder, backend->terminal);
    g_api.key_event_set_action(backend->key_event, (GhosttyKeyAction) action);
    g_api.key_event_set_key(backend->key_event, key);
    g_api.key_event_set_mods(backend->key_event, mods);
    g_api.key_event_set_utf8(backend->key_event, "", 0);
    g_api.key_event_set_unshifted_codepoint(backend->key_event, 0);
    char output[128];
    size_t written = 0;
    GhosttyResult result = g_api.key_encoder_encode(
        backend->key_encoder, backend->key_event, output, sizeof(output), &written);
    jbyteArray value = result == GHOSTTY_SUCCESS && written <= sizeof(output)
        ? byte_array_from_native(env, (const uint8_t*) output, written)
        : NULL;
    pthread_mutex_unlock(&backend->mutex);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeEncodeText(JNIEnv* env,
                                                                  jclass clazz,
                                                                  jlong handle,
                                                                  jbyteArray utf8_array,
                                                                  jint android_key,
                                                                  jint action,
                                                                  jint unshifted_codepoint,
                                                                  jint key_mod)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || utf8_array == NULL || action < 0 || action > 2 ||
        unshifted_codepoint < 0 ||
        unshifted_codepoint > 0x10ffff ||
        (unshifted_codepoint >= 0xd800 && unshifted_codepoint <= 0xdfff)) return NULL;
    jsize utf8_len = (*env)->GetArrayLength(env, utf8_array);
    jbyte* utf8 = (*env)->GetByteArrayElements(env, utf8_array, NULL);
    if (utf8 == NULL) return NULL;
    uint32_t mods_in = (uint32_t) key_mod;
    GhosttyMods mods = 0;
    if ((mods_in & UINT32_C(0x20000000)) != 0) mods |= GHOSTTY_MODS_SHIFT;
    if ((mods_in & UINT32_C(0x40000000)) != 0) mods |= GHOSTTY_MODS_CTRL;
    if ((mods_in & UINT32_C(0x80000000)) != 0) mods |= GHOSTTY_MODS_ALT;
    if ((mods_in & UINT32_C(0x10000000)) != 0) mods |= GHOSTTY_MODS_NUM_LOCK;

    pthread_mutex_lock(&backend->mutex);
    g_api.key_encoder_setopt_from_terminal(backend->key_encoder, backend->terminal);
    g_api.key_event_set_action(backend->key_event, (GhosttyKeyAction) action);
    g_api.key_event_set_key(backend->key_event, android_key_to_ghostty(android_key));
    g_api.key_event_set_mods(backend->key_event, mods);
    g_api.key_event_set_utf8(backend->key_event, (const char*) utf8, (size_t) utf8_len);
    g_api.key_event_set_unshifted_codepoint(
        backend->key_event, (uint32_t) unshifted_codepoint);
    char output[128];
    size_t written = 0;
    GhosttyResult result = g_api.key_encoder_encode(
        backend->key_encoder, backend->key_event, output, sizeof(output), &written);
    (*env)->ReleaseByteArrayElements(env, utf8_array, utf8, JNI_ABORT);
    jbyteArray value = result == GHOSTTY_SUCCESS && written <= sizeof(output)
        ? byte_array_from_native(env, (const uint8_t*) output, written)
        : NULL;
    pthread_mutex_unlock(&backend->mutex);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeEncodeMouse(JNIEnv* env,
                                                                   jclass clazz,
                                                                   jlong handle,
                                                                   jint button,
                                                                   jint column,
                                                                   jint row,
                                                                   jboolean pressed)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || column < 1 || row < 1) return NULL;
    pthread_mutex_lock(&backend->mutex);
    g_api.mouse_encoder_setopt_from_terminal(backend->mouse_encoder, backend->terminal);
    uint64_t screen_width_u64 = (uint64_t) backend->columns * backend->cell_width;
    uint64_t screen_height_u64 = (uint64_t) backend->rows * backend->cell_height;
    GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
    size.screen_width = (uint32_t) (screen_width_u64 > UINT32_MAX ? UINT32_MAX : screen_width_u64);
    size.screen_height = (uint32_t) (screen_height_u64 > UINT32_MAX ? UINT32_MAX : screen_height_u64);
    size.cell_width = backend->cell_width == 0 ? 1 : backend->cell_width;
    size.cell_height = backend->cell_height == 0 ? 1 : backend->cell_height;
    g_api.mouse_encoder_setopt(backend->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, &size);
    bool any_button = pressed == JNI_TRUE && button != 64 && button != 65;
    g_api.mouse_encoder_setopt(backend->mouse_encoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                               &any_button);

    GhosttyMouseAction action = button == 32
        ? GHOSTTY_MOUSE_ACTION_MOTION
        : pressed == JNI_TRUE ? GHOSTTY_MOUSE_ACTION_PRESS : GHOSTTY_MOUSE_ACTION_RELEASE;
    GhosttyMouseButton mapped_button = GHOSTTY_MOUSE_BUTTON_UNKNOWN;
    switch (button) {
        case 0:
        case 32: mapped_button = GHOSTTY_MOUSE_BUTTON_LEFT; break;
        case 64: mapped_button = GHOSTTY_MOUSE_BUTTON_FOUR; break;
        case 65: mapped_button = GHOSTTY_MOUSE_BUTTON_FIVE; break;
        default: break;
    }
    g_api.mouse_event_set_action(backend->mouse_event, action);
    if (mapped_button == GHOSTTY_MOUSE_BUTTON_UNKNOWN) {
        g_api.mouse_event_clear_button(backend->mouse_event);
    } else {
        g_api.mouse_event_set_button(backend->mouse_event, mapped_button);
    }
    GhosttyMousePosition position = {
        .x = ((float) column - 0.5f) * (float) size.cell_width,
        .y = ((float) row - 0.5f) * (float) size.cell_height,
    };
    g_api.mouse_event_set_position(backend->mouse_event, position);
    char output[128];
    size_t written = 0;
    GhosttyResult result = g_api.mouse_encoder_encode(
        backend->mouse_encoder, backend->mouse_event, output, sizeof(output), &written);
    jbyteArray value = result == GHOSTTY_SUCCESS && written <= sizeof(output)
        ? byte_array_from_native(env, (const uint8_t*) output, written)
        : NULL;
    pthread_mutex_unlock(&backend->mutex);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeEncodePaste(JNIEnv* env,
                                                                   jclass clazz,
                                                                   jlong handle,
                                                                   jbyteArray input_array)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || input_array == NULL) return NULL;
    jsize input_len = (*env)->GetArrayLength(env, input_array);
    if (input_len < 0 || (size_t) input_len > SIZE_MAX - 32U) return NULL;
    size_t capacity = (size_t) input_len + 32U;
    char* input = malloc(input_len == 0 ? 1U : (size_t) input_len);
    char* output = malloc(capacity == 0 ? 1U : capacity);
    if (input == NULL || output == NULL) {
        free(input);
        free(output);
        return NULL;
    }
    if (input_len > 0) {
        (*env)->GetByteArrayRegion(env, input_array, 0, input_len, (jbyte*) input);
        if ((*env)->ExceptionCheck(env)) {
            free(input);
            free(output);
            return NULL;
        }
    }
    pthread_mutex_lock(&backend->mutex);
    bool bracketed = terminal_mode(backend->terminal, GHOSTTY_MODE_BRACKETED_PASTE);
    size_t written = 0;
    GhosttyResult result = g_api.paste_encode(
        input, (size_t) input_len, bracketed, output, capacity, &written);
    pthread_mutex_unlock(&backend->mutex);
    jbyteArray value = result == GHOSTTY_SUCCESS && written <= capacity
        ? byte_array_from_native(env, (const uint8_t*) output, written)
        : NULL;
    free(input);
    free(output);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeEncodeFocus(JNIEnv* env,
                                                                   jclass clazz,
                                                                   jlong handle,
                                                                   jboolean focused)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return NULL;
    pthread_mutex_lock(&backend->mutex);
    if (!terminal_mode(backend->terminal, GHOSTTY_MODE_FOCUS_EVENT)) {
        pthread_mutex_unlock(&backend->mutex);
        return (*env)->NewByteArray(env, 0);
    }
    char output[16];
    size_t written = 0;
    GhosttyResult result = g_api.focus_encode(
        focused == JNI_TRUE ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST,
        output, sizeof(output), &written);
    jbyteArray value = result == GHOSTTY_SUCCESS && written <= sizeof(output)
        ? byte_array_from_native(env, (const uint8_t*) output, written)
        : NULL;
    pthread_mutex_unlock(&backend->mutex);
    return value;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSnapshot(JNIEnv* env,
                                                                jclass clazz,
                                                                jlong handle,
                                                                jlongArray output)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || output == NULL ||
        (*env)->GetArrayLength(env, output) < TERMUX_GHOSTTY_STATS_COUNT) return JNI_FALSE;

    jlong stats[TERMUX_GHOSTTY_STATS_COUNT] = {0};
    pthread_mutex_lock(&backend->mutex);
    if (g_api.render_state_update(backend->render_state, backend->terminal) != GHOSTTY_SUCCESS) {
        pthread_mutex_unlock(&backend->mutex);
        return JNI_FALSE;
    }
    backend->render_updates++;

    uint16_t columns = 0;
    uint16_t rows = 0;
    uint16_t cursor_x = 0;
    uint16_t cursor_y = 0;
    bool cursor_visible = false;
    GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    GhosttyTerminalScreen active_screen = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
    size_t scrollback_rows = 0;
    bool vt_processing_error = false;
#if TERMUX_GHOSTTY_RENDER_BATCH_API
    static const GhosttyRenderStateData render_keys[] = {
        GHOSTTY_RENDER_STATE_DATA_COLS,
        GHOSTTY_RENDER_STATE_DATA_ROWS,
        GHOSTTY_RENDER_STATE_DATA_DIRTY,
        GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
    };
    void* render_values[] = {&columns, &rows, &dirty, &backend->row_iterator};
    size_t render_written = 0;
    static const GhosttyTerminalData terminal_keys[] = {
        GHOSTTY_TERMINAL_DATA_CURSOR_X,
        GHOSTTY_TERMINAL_DATA_CURSOR_Y,
        GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE,
        GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN,
        GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
        GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR,
    };
    void* terminal_values[] = {
        &cursor_x,
        &cursor_y,
        &cursor_visible,
        &active_screen,
        &scrollback_rows,
        &vt_processing_error,
    };
    size_t terminal_written = 0;
    GhosttyResult render_result = g_api.render_state_get_multi(
        backend->render_state, sizeof(render_keys) / sizeof(render_keys[0]),
        render_keys, render_values, &render_written);
    GhosttyResult terminal_result = g_api.terminal_get_multi(
        backend->terminal, sizeof(terminal_keys) / sizeof(terminal_keys[0]),
        terminal_keys, terminal_values, &terminal_written);
    if (!multi_complete(render_result, render_written,
                        sizeof(render_keys) / sizeof(render_keys[0])) ||
        !multi_complete(terminal_result, terminal_written,
                        sizeof(terminal_keys) / sizeof(terminal_keys[0]))) {
        pthread_mutex_unlock(&backend->mutex);
        return JNI_FALSE;
    }
#else
    if (g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_COLS, &columns) !=
            GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows) !=
            GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty) !=
            GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_X, &cursor_x) !=
            GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &cursor_y) !=
            GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_CURSOR_VISIBLE,
                           &cursor_visible) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &active_screen) !=
            GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                           &scrollback_rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_VT_PROCESSING_ERROR,
                           &vt_processing_error) != GHOSTTY_SUCCESS ||
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &backend->row_iterator) != GHOSTTY_SUCCESS) {
        pthread_mutex_unlock(&backend->mutex);
        return JNI_FALSE;
    }
#endif

    uint64_t row_count = 0;
    uint64_t cell_count = 0;
    uint64_t text_cell_count = 0;
    uint64_t grapheme_count = 0;
    uint64_t styled_cell_count = 0;
    uint64_t hash = UINT64_C(1469598103934665603);
    while (g_api.row_iterator_next(backend->row_iterator)) {
        hash = hash_u64(hash, UINT64_C(0x524f570000000000) | row_count);
        if (g_api.row_get(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                          &backend->row_cells) != GHOSTTY_SUCCESS) {
            pthread_mutex_unlock(&backend->mutex);
            return JNI_FALSE;
        }

        uint64_t column = 0;
        while (g_api.row_cells_next(backend->row_cells)) {
            uint32_t grapheme_length = 0;
            bool has_styling = false;
            GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
#if TERMUX_GHOSTTY_RENDER_BATCH_API
            static const GhosttyRenderStateRowCellsData cell_keys[] = {
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING,
                GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
            };
            void* cell_values[] = {&grapheme_length, &has_styling, &style};
            size_t cell_written = 0;
            GhosttyResult cell_result = g_api.row_cells_get_multi(
                backend->row_cells, sizeof(cell_keys) / sizeof(cell_keys[0]),
                cell_keys, cell_values, &cell_written);
            if (!multi_complete(cell_result, cell_written,
                                sizeof(cell_keys) / sizeof(cell_keys[0]))) {
                pthread_mutex_unlock(&backend->mutex);
                return JNI_FALSE;
            }
#else
            if (g_api.row_cells_get(backend->row_cells,
                                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN,
                                    &grapheme_length) != GHOSTTY_SUCCESS ||
                g_api.row_cells_get(backend->row_cells,
                                    GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING,
                                    &has_styling) != GHOSTTY_SUCCESS) {
                pthread_mutex_unlock(&backend->mutex);
                return JNI_FALSE;
            }
#endif
            hash = hash_u64(hash, column++);
            hash = hash_u64(hash, grapheme_length);
            cell_count++;
            if (grapheme_length > 0) {
                if (!ensure_grapheme_capacity(backend, grapheme_length) ||
                    g_api.row_cells_get(backend->row_cells,
                                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                                        backend->grapheme_codepoints) != GHOSTTY_SUCCESS) {
                    pthread_mutex_unlock(&backend->mutex);
                    return JNI_FALSE;
                }
                text_cell_count++;
                grapheme_count += grapheme_length;
                for (uint32_t index = 0; index < grapheme_length; index++) {
                    hash = hash_u64(hash, backend->grapheme_codepoints[index]);
                }
            }
            if (has_styling) {
#if !TERMUX_GHOSTTY_RENDER_BATCH_API
                if (g_api.row_cells_get(backend->row_cells,
                                        GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                                        &style) != GHOSTTY_SUCCESS) {
                    pthread_mutex_unlock(&backend->mutex);
                    return JNI_FALSE;
                }
#endif
                styled_cell_count++;
                hash = hash_style_color(hash, style.fg_color);
                hash = hash_style_color(hash, style.bg_color);
                hash = hash_style_color(hash, style.underline_color);
                hash = hash_u64(hash, style.bold);
                hash = hash_u64(hash, style.italic);
                hash = hash_u64(hash, style.faint);
                hash = hash_u64(hash, style.blink);
                hash = hash_u64(hash, style.inverse);
                hash = hash_u64(hash, style.invisible);
                hash = hash_u64(hash, style.strikethrough);
                hash = hash_u64(hash, style.overline);
                hash = hash_u64(hash, (uint64_t) style.underline);
            }
        }
        bool clean_row = false;
        g_api.row_set(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY, &clean_row);
        row_count++;
    }
    GhosttyRenderStateDirty clean = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
    g_api.render_state_set(backend->render_state, GHOSTTY_RENDER_STATE_OPTION_DIRTY, &clean);
    /* Diagnostic snapshots consume render dirties without updating the retained delta consumer. */
    backend->render_delta_initialized = false;

    stats[0] = (jlong) backend->writes;
    stats[1] = (jlong) backend->bytes;
    stats[2] = columns;
    stats[3] = rows;
    stats[4] = cursor_x;
    stats[5] = cursor_y;
    stats[6] = cursor_visible ? 1 : 0;
    stats[7] = dirty;
    stats[8] = (jlong) row_count;
    stats[9] = (jlong) cell_count;
    stats[10] = (jlong) text_cell_count;
    stats[11] = (jlong) grapheme_count;
    stats[12] = (jlong) hash;
    stats[13] = (jlong) styled_cell_count;
    stats[14] = active_screen;
    stats[15] = (jlong) scrollback_rows;
    stats[16] = vt_processing_error ? 1 : 0;
    stats[17] = (jlong) backend->render_updates;
    stats[18] = g_api.simd ? 1 : 0;
    stats[19] = g_api.optimize;
    pthread_mutex_unlock(&backend->mutex);

    (*env)->SetLongArrayRegion(env, output, 0, TERMUX_GHOSTTY_STATS_COUNT, stats);
    return (*env)->ExceptionCheck(env) ? JNI_FALSE : JNI_TRUE;
}

/*
 * Build one complete, immutable-at-return render packet in a caller-owned direct buffer.
 *
 * The cell table is row-major with six native-endian uint32 values per cell:
 *   foreground ARGB, background ARGB, underline ARGB, flags, UTF-8 offset, UTF-8 length.
 * UTF-8 grapheme bytes follow the fixed-size table in the same row-major order. This keeps the JNI
 * boundary O(1) per frame and lets Java merge adjacent cells into GPU runs without JNI per cell.
 * Return values: 1 = success, 2 = buffer too small (metadata[1] is required bytes), 0 = failure.
 */
JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeRenderSnapshot(JNIEnv* env,
                                                                      jclass clazz,
                                                                      jlong handle,
                                                                      jobject output,
                                                                      jlongArray metadata,
                                                                      jint top_row)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || output == NULL || metadata == NULL ||
        (*env)->GetArrayLength(env, metadata) < TERMUX_GHOSTTY_RENDER_METADATA_COUNT) return 0;

    uint8_t* packet = (*env)->GetDirectBufferAddress(env, output);
    jlong direct_capacity = (*env)->GetDirectBufferCapacity(env, output);
    if (packet == NULL || direct_capacity < 0) return 0;
    size_t capacity = (size_t) direct_capacity;
    jlong values[TERMUX_GHOSTTY_RENDER_METADATA_COUNT] = {0};
    bool success = false;

    pthread_mutex_lock(&backend->render_mutex);
    pthread_mutex_lock(&backend->mutex);

    GhosttyTerminalScrollViewport viewport = {.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM};
    g_api.terminal_scroll_viewport(backend->terminal, viewport);
    if (top_row < 0) {
        viewport.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
        viewport.value.delta = (intptr_t) top_row;
        g_api.terminal_scroll_viewport(backend->terminal, viewport);
    }

    GhosttyResult begin_result =
        g_api.render_state_begin_update(backend->render_state, backend->terminal);
    size_t captured_scrollback_rows = 0;
    bool captured_terminal_state = begin_result == GHOSTTY_SUCCESS &&
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                           &captured_scrollback_rows) == GHOSTTY_SUCCESS;
    uint64_t captured_bytes = backend->bytes;
    pthread_mutex_unlock(&backend->mutex);
    GhosttyResult end_result = g_api.render_state_end_update(backend->render_state);
    if (!captured_terminal_state || end_result != GHOSTTY_SUCCESS) {
        goto render_fail_render_locked;
    }
    backend->render_updates++;

    TermuxGhosttyRenderFrame frame;
    if (!read_render_frame_locked(backend, &frame)) goto render_fail_render_locked;
    frame.scrollback_rows = captured_scrollback_rows;
    uint16_t columns = frame.columns;
    uint16_t rows = frame.rows;
    GhosttyRenderStateDirty dirty = frame.dirty;
    GhosttyColorRgb background = frame.colors.background;
    GhosttyColorRgb foreground = frame.colors.foreground;
    GhosttyColorRgb cursor_color = frame.colors.cursor;
    GhosttyColorRgb* palette = frame.colors.palette;
    bool cursor_has_color = frame.colors.cursor_has_value;
    bool cursor_mode_visible = frame.cursor_mode_visible;
    bool cursor_viewport_has_value = frame.cursor_viewport_has_value;
    bool cursor_wide_tail = frame.cursor_wide_tail;
    uint16_t cursor_x = frame.cursor_x;
    uint16_t cursor_y = frame.cursor_y;
    GhosttyRenderStateCursorVisualStyle cursor_style = frame.cursor_style;
    size_t scrollback_rows = frame.scrollback_rows;

    size_t cell_count = (size_t) columns * (size_t) rows;
    if (cell_count > SIZE_MAX / TERMUX_GHOSTTY_CELL_RECORD_BYTES) {
        goto render_fail_render_locked;
    }
    size_t table_bytes = cell_count * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    size_t text_bytes = 0;
    size_t row_index = 0;

    while (g_api.row_iterator_next(backend->row_iterator)) {
        if (row_index >= rows ||
            g_api.row_get(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                          &backend->row_cells) != GHOSTTY_SUCCESS) {
            goto render_fail_render_locked;
        }

        size_t column_index = 0;
        while (g_api.row_cells_next(backend->row_cells)) {
            if (column_index >= columns) goto render_fail_render_locked;

            size_t record_index = row_index * (size_t) columns + column_index;
            size_t record_offset = record_index * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
            if (table_bytes > SIZE_MAX - text_bytes) goto render_fail_render_locked;
            size_t text_offset = table_bytes + text_bytes;
            TermuxGhosttyRenderCell cell;
            if (!read_render_cell_locked(backend, foreground, background, palette,
                                         packet, capacity, text_offset, &cell) ||
                text_offset > SIZE_MAX - cell.encoded_length) {
                goto render_fail_render_locked;
            }
            size_t required_after_cell = text_offset + cell.encoded_length;
            if (capacity >= table_bytes && required_after_cell <= capacity &&
                text_offset <= UINT32_MAX) {
                write_packet_u32(packet, record_offset + 0, cell.foreground_argb);
                write_packet_u32(packet, record_offset + 4, cell.background_argb);
                write_packet_u32(packet, record_offset + 8, cell.underline_argb);
                write_packet_u32(packet, record_offset + 12, cell.flags);
                write_packet_u32(packet, record_offset + 16, (uint32_t) text_offset);
                write_packet_u32(packet, record_offset + 20, cell.encoded_length);
            }
            text_bytes += cell.encoded_length;
            column_index++;
        }
        if (column_index != columns) goto render_fail_render_locked;
        row_index++;
    }
    if (row_index != rows) goto render_fail_render_locked;

    size_t required_bytes = table_bytes + text_bytes;
    success = required_bytes <= capacity;
    values[0] = 1;
    values[1] = (jlong) required_bytes;
    values[2] = TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    values[3] = columns;
    values[4] = rows;
    values[5] = (jlong) (uint64_t) rgb_to_argb(background);
    values[6] = (jlong) (uint64_t) rgb_to_argb(foreground);
    values[7] = (jlong) (uint64_t) (cursor_has_color ? rgb_to_argb(cursor_color)
                                                     : rgb_to_argb(foreground));
    values[8] = cursor_x;
    values[9] = cursor_y;
    values[10] = cursor_mode_visible && cursor_viewport_has_value ? 1 : 0;
    values[11] = cursor_style;
    values[12] = cursor_wide_tail ? 1 : 0;
    values[13] = dirty;
    values[14] = (jlong) scrollback_rows;
    values[15] = (jlong) table_bytes;
    values[16] = (jlong) backend->render_updates;
    values[17] = (jlong) captured_bytes;

    if (success &&
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &backend->row_iterator) == GHOSTTY_SUCCESS) {
        while (g_api.row_iterator_next(backend->row_iterator)) {
            bool clean_row = false;
            g_api.row_set(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY, &clean_row);
        }
        GhosttyRenderStateDirty clean = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
        g_api.render_state_set(backend->render_state, GHOSTTY_RENDER_STATE_OPTION_DIRTY, &clean);
        backend->render_delta_initialized = false;
    }
    pthread_mutex_unlock(&backend->render_mutex);

    (*env)->SetLongArrayRegion(env, metadata, 0, TERMUX_GHOSTTY_RENDER_METADATA_COUNT, values);
    if ((*env)->ExceptionCheck(env)) return 0;
    return success ? 1 : 2;

render_fail_render_locked:
    pthread_mutex_unlock(&backend->render_mutex);
    return 0;
}

/*
 * Build a retained render delta. The packet begins with one uint32 payload offset per viewport row.
 * A zero offset means the row is unchanged. Each present row contains a fixed cell table followed by
 * its UTF-8 arena, so Java can retain clean rows without copying or decoding them again.
 *
 * Metadata ABI 3:
 *   0 abi, 1 required bytes, 2 cell record bytes, 3 columns, 4 rows,
 *   5 background, 6 foreground, 7 cursor color, 8 cursor x, 9 cursor y,
 *   10 cursor visible, 11 cursor style, 12 cursor wide tail, 13 Ghostty dirty state,
 *   14 scrollback rows, 15 directory bytes, 16 render update generation, 17 PTY bytes,
 *   18 changed row count, 19 full frame, 20 requested top row, 21 state generation,
 *   22 exact-compare candidates, 23 exact rows suppressed, 24 cumulative suppressed rows,
 *   25 cumulative semantic packets.
 *
 * Return values: 1 = success, 2 = buffer too small, 0 = failure. Dirty flags are consumed only
 * after a successful packet, so a resize retry cannot lose a row update.
 */
JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeRenderDelta(JNIEnv* env,
                                                                   jclass clazz,
                                                                   jlong handle,
                                                                   jobject output,
                                                                   jlongArray metadata,
                                                                   jint top_row,
                                                                   jboolean force_full)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL || output == NULL || metadata == NULL ||
        (*env)->GetArrayLength(env, metadata) < TERMUX_GHOSTTY_RENDER_DELTA_METADATA_COUNT) return 0;

    uint8_t* packet = (*env)->GetDirectBufferAddress(env, output);
    jlong direct_capacity = (*env)->GetDirectBufferCapacity(env, output);
    if (packet == NULL || direct_capacity < 0) return 0;
    size_t capacity = (size_t) direct_capacity;
    jlong values[TERMUX_GHOSTTY_RENDER_DELTA_METADATA_COUNT] = {0};
    bool success = false;

    if (top_row > 0) top_row = 0;

    pthread_mutex_lock(&backend->render_mutex);
    pthread_mutex_lock(&backend->mutex);

    bool viewport_changed = !backend->render_delta_initialized ||
                            backend->render_delta_top_row != (int32_t) top_row;
    if (viewport_changed) {
        GhosttyTerminalScrollViewport viewport = {.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM};
        g_api.terminal_scroll_viewport(backend->terminal, viewport);
        if (top_row < 0) {
            viewport.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
            viewport.value.delta = (intptr_t) top_row;
            g_api.terminal_scroll_viewport(backend->terminal, viewport);
        }
    }

    GhosttyResult begin_result =
        g_api.render_state_begin_update(backend->render_state, backend->terminal);
    size_t captured_scrollback_rows = 0;
    bool captured_terminal_state = begin_result == GHOSTTY_SUCCESS &&
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                           &captured_scrollback_rows) == GHOSTTY_SUCCESS;
    uint64_t captured_state_generation = backend->state_generation;
    uint64_t captured_bytes = backend->bytes;
    pthread_mutex_unlock(&backend->mutex);
    GhosttyResult end_result = g_api.render_state_end_update(backend->render_state);
    if (!captured_terminal_state || end_result != GHOSTTY_SUCCESS) {
        goto render_delta_fail_render_locked;
    }
    backend->render_updates++;

    TermuxGhosttyRenderFrame frame;
    if (!read_render_frame_locked(backend, &frame)) goto render_delta_fail_render_locked;
    frame.scrollback_rows = captured_scrollback_rows;
    uint16_t columns = frame.columns;
    uint16_t rows = frame.rows;
    GhosttyRenderStateDirty dirty = frame.dirty;
    GhosttyColorRgb background = frame.colors.background;
    GhosttyColorRgb foreground = frame.colors.foreground;
    GhosttyColorRgb cursor_color = frame.colors.cursor;
    GhosttyColorRgb* palette = frame.colors.palette;
    bool cursor_has_color = frame.colors.cursor_has_value;
    bool cursor_mode_visible = frame.cursor_mode_visible;
    bool cursor_viewport_has_value = frame.cursor_viewport_has_value;
    bool cursor_wide_tail = frame.cursor_wide_tail;
    uint16_t cursor_x = frame.cursor_x;
    uint16_t cursor_y = frame.cursor_y;
    GhosttyRenderStateCursorVisualStyle cursor_style = frame.cursor_style;
    size_t scrollback_rows = frame.scrollback_rows;

    bool dimensions_changed = !backend->render_delta_initialized ||
                              backend->render_delta_columns != columns ||
                              backend->render_delta_rows_count != rows;
    if (dimensions_changed || viewport_changed) invalidate_render_delta_row_cache(backend);
    bool viewport_reuse = viewport_changed && backend->render_delta_initialized &&
                          !dimensions_changed && force_full != JNI_TRUE &&
                          backend->render_delta_state_generation == captured_state_generation;
    bool semantic_delta_enabled = force_full != JNI_TRUE && !dimensions_changed &&
                                  !viewport_changed &&
                                  backend->render_delta_row_cache_complete &&
                                  backend->render_delta_row_cache_count == rows;
    bool semantic_full_delta = semantic_delta_enabled &&
                               dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL;
    /* A same-generation viewport move retains overlap even when Ghostty reports global dirty. */
    bool full_frame = force_full == JNI_TRUE || dimensions_changed ||
                      (!viewport_reuse &&
                       (viewport_changed ||
                        (dirty == GHOSTTY_RENDER_STATE_DIRTY_FULL && !semantic_full_delta)));
    int64_t old_viewport_top = backend->render_delta_top_row;
    int64_t old_viewport_bottom = old_viewport_top + backend->render_delta_rows_count;
    bool current_cursor_visible = cursor_mode_visible && cursor_viewport_has_value;
    int64_t current_cursor_logical_row =
        (int64_t) top_row + (current_cursor_visible ? cursor_y : 0);
    size_t directory_bytes = (size_t) rows * sizeof(uint32_t);
    size_t row_table_bytes = (size_t) columns * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    if ((columns != 0 && row_table_bytes / TERMUX_GHOSTTY_CELL_RECORD_BYTES != columns) ||
        directory_bytes > UINT32_MAX || row_table_bytes > UINT32_MAX) {
        goto render_delta_fail_render_locked;
    }
    if (capacity >= directory_bytes) memset(packet, 0, directory_bytes);

    size_t payload_offset = directory_bytes;
    size_t row_index = 0;
    size_t changed_rows = 0;
    size_t semantic_candidates = 0;
    size_t semantic_suppressed = 0;
    while (g_api.row_iterator_next(backend->row_iterator)) {
        if (row_index >= rows) goto render_delta_fail_render_locked;

        bool row_dirty = false;
#if TERMUX_GHOSTTY_RENDER_BATCH_API
        static const GhosttyRenderStateRowData row_keys[] = {
            GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
            GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
        };
        void* row_values[] = {&row_dirty, &backend->row_cells};
        size_t row_written = 0;
        GhosttyResult row_result = g_api.row_get_multi(
            backend->row_iterator, sizeof(row_keys) / sizeof(row_keys[0]),
            row_keys, row_values, &row_written);
        if (!multi_complete(row_result, row_written,
                            sizeof(row_keys) / sizeof(row_keys[0]))) {
            goto render_delta_fail_render_locked;
        }
#else
        if (g_api.row_get(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_DIRTY,
                          &row_dirty) != GHOSTTY_SUCCESS) {
            goto render_delta_fail_render_locked;
        }
#endif
        int64_t logical_row = (int64_t) top_row + (int64_t) row_index;
        bool newly_exposed = viewport_reuse &&
                             (logical_row < old_viewport_top ||
                              logical_row >= old_viewport_bottom);
        bool cursor_sensitive =
            (backend->render_delta_cursor_visible &&
             logical_row == backend->render_delta_cursor_logical_row) ||
            (current_cursor_visible && logical_row == current_cursor_logical_row);
        bool include_row = full_frame || semantic_full_delta ||
                           (viewport_reuse ? (newly_exposed || cursor_sensitive) : row_dirty);
        if (!include_row) {
            row_index++;
            continue;
        }

        if (payload_offset > UINT32_MAX || payload_offset > SIZE_MAX - row_table_bytes) {
            goto render_delta_fail_render_locked;
        }
#if !TERMUX_GHOSTTY_RENDER_BATCH_API
        if (g_api.row_get(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                          &backend->row_cells) != GHOSTTY_SUCCESS) {
            goto render_delta_fail_render_locked;
        }
#endif
        size_t row_payload_offset = payload_offset;
        size_t row_text_bytes = 0;
        if (capacity >= directory_bytes) {
            write_packet_u32(packet, row_index * sizeof(uint32_t),
                             (uint32_t) row_payload_offset);
        }

        size_t column_index = 0;
        while (g_api.row_cells_next(backend->row_cells)) {
            if (column_index >= columns) goto render_delta_fail_render_locked;

            if (row_payload_offset > SIZE_MAX - row_table_bytes ||
                row_payload_offset + row_table_bytes > SIZE_MAX - row_text_bytes) {
                goto render_delta_fail_render_locked;
            }
            size_t record_offset = row_payload_offset +
                column_index * TERMUX_GHOSTTY_CELL_RECORD_BYTES;
            size_t text_offset = row_payload_offset + row_table_bytes + row_text_bytes;
            TermuxGhosttyRenderCell cell;
            if (!read_render_cell_locked(backend, foreground, background, palette,
                                         packet, capacity, text_offset, &cell) ||
                text_offset > SIZE_MAX - cell.encoded_length) {
                goto render_delta_fail_render_locked;
            }
            size_t required_after_cell = text_offset + cell.encoded_length;
            if (required_after_cell <= capacity && text_offset <= UINT32_MAX) {
                write_packet_u32(packet, record_offset + 0, cell.foreground_argb);
                write_packet_u32(packet, record_offset + 4, cell.background_argb);
                write_packet_u32(packet, record_offset + 8, cell.underline_argb);
                write_packet_u32(packet, record_offset + 12, cell.flags);
                write_packet_u32(packet, record_offset + 16, (uint32_t) text_offset);
                write_packet_u32(packet, record_offset + 20, cell.encoded_length);
            }
            row_text_bytes += cell.encoded_length;
            column_index++;
        }
        if (column_index != columns || row_payload_offset > SIZE_MAX - row_table_bytes ||
            row_payload_offset + row_table_bytes > SIZE_MAX - row_text_bytes) {
            goto render_delta_fail_render_locked;
        }
        size_t row_payload_length = row_table_bytes + row_text_bytes;
        size_t row_payload_end = row_payload_offset + row_payload_length;
        bool suppress_row = false;
        if (semantic_delta_enabled && !cursor_sensitive) {
            semantic_candidates++;
            suppress_row = row_payload_end <= capacity &&
                           retained_row_matches(
                               &backend->render_delta_row_cache[row_index], packet,
                               row_payload_offset, row_payload_length, columns);
        }
        if (suppress_row) {
            write_packet_u32(packet, row_index * sizeof(uint32_t), 0U);
            semantic_suppressed++;
        } else {
            if (!align_packet_offset(row_payload_end, &payload_offset)) {
                goto render_delta_fail_render_locked;
            }
            changed_rows++;
        }
        row_index++;
    }
    if (row_index != rows || payload_offset > INT32_MAX) goto render_delta_fail_render_locked;

    success = payload_offset <= capacity;
    values[0] = TERMUX_GHOSTTY_RENDER_DELTA_ABI;
    values[1] = (jlong) payload_offset;
    values[2] = TERMUX_GHOSTTY_CELL_RECORD_BYTES;
    values[3] = columns;
    values[4] = rows;
    values[5] = (jlong) (uint64_t) rgb_to_argb(background);
    values[6] = (jlong) (uint64_t) rgb_to_argb(foreground);
    values[7] = (jlong) (uint64_t) (cursor_has_color ? rgb_to_argb(cursor_color)
                                                     : rgb_to_argb(foreground));
    values[8] = cursor_x;
    values[9] = cursor_y;
    values[10] = cursor_mode_visible && cursor_viewport_has_value ? 1 : 0;
    values[11] = cursor_style;
    values[12] = cursor_wide_tail ? 1 : 0;
    values[13] = dirty;
    values[14] = (jlong) scrollback_rows;
    values[15] = (jlong) directory_bytes;
    values[16] = (jlong) backend->render_updates;
    values[17] = (jlong) captured_bytes;
    values[18] = (jlong) changed_rows;
    values[19] = full_frame ? 1 : 0;
    values[20] = top_row;
    values[21] = (jlong) captured_state_generation;
    values[22] = (jlong) semantic_candidates;
    values[23] = (jlong) semantic_suppressed;

    if (success &&
        g_api.render_state_get(backend->render_state, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &backend->row_iterator) == GHOSTTY_SUCCESS) {
        update_render_delta_row_cache(backend, packet, payload_offset, columns, rows, full_frame);
        while (g_api.row_iterator_next(backend->row_iterator)) {
            bool clean_row = false;
            g_api.row_set(backend->row_iterator, GHOSTTY_RENDER_STATE_ROW_OPTION_DIRTY, &clean_row);
        }
        GhosttyRenderStateDirty clean = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
        g_api.render_state_set(backend->render_state, GHOSTTY_RENDER_STATE_OPTION_DIRTY, &clean);
        backend->render_delta_initialized = true;
        backend->render_delta_top_row = (int32_t) top_row;
        backend->render_delta_columns = columns;
        backend->render_delta_rows_count = rows;
        backend->render_delta_state_generation = captured_state_generation;
        backend->render_delta_cursor_visible = current_cursor_visible;
        backend->render_delta_cursor_logical_row =
            current_cursor_visible ? (int32_t) current_cursor_logical_row : 0;
        backend->render_delta_packets++;
        backend->render_delta_rows += changed_rows;
        backend->render_delta_semantic_candidates += semantic_candidates;
        backend->render_delta_semantic_suppressed += semantic_suppressed;
        if (semantic_candidates > 0) backend->render_delta_semantic_packets++;
    }
    values[24] = (jlong) backend->render_delta_semantic_suppressed;
    values[25] = (jlong) backend->render_delta_semantic_packets;
    pthread_mutex_unlock(&backend->render_mutex);

    (*env)->SetLongArrayRegion(env, metadata, 0,
                               TERMUX_GHOSTTY_RENDER_DELTA_METADATA_COUNT, values);
    if ((*env)->ExceptionCheck(env)) return 0;
    return success ? 1 : 2;

render_delta_fail_render_locked:
    pthread_mutex_unlock(&backend->render_mutex);
    return 0;
}

JNIEXPORT jboolean JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeRecoverRender(JNIEnv* env,
                                                                    jclass clazz,
                                                                    jlong handle)
{
    (void) env;
    (void) clazz;
    TermuxGhosttyBackend* backend = (TermuxGhosttyBackend*) (intptr_t) handle;
    if (backend == NULL || !ensure_api()) return JNI_FALSE;

    GhosttyRenderState render_state = NULL;
    GhosttyRenderStateRowIterator row_iterator = NULL;
    GhosttyRenderStateRowCells row_cells = NULL;
    bool success = false;

    pthread_mutex_lock(&backend->render_mutex);
    pthread_mutex_lock(&backend->mutex);
    if (backend->terminal != NULL &&
        g_api.render_state_new(NULL, &render_state) == GHOSTTY_SUCCESS &&
        g_api.row_iterator_new(NULL, &row_iterator) == GHOSTTY_SUCCESS &&
        g_api.row_cells_new(NULL, &row_cells) == GHOSTTY_SUCCESS &&
        g_api.render_state_update(render_state, backend->terminal) == GHOSTTY_SUCCESS) {
        GhosttyRenderState old_render_state = backend->render_state;
        GhosttyRenderStateRowIterator old_row_iterator = backend->row_iterator;
        GhosttyRenderStateRowCells old_row_cells = backend->row_cells;
        backend->render_state = render_state;
        backend->row_iterator = row_iterator;
        backend->row_cells = row_cells;
        backend->render_delta_initialized = false;
        backend->render_delta_top_row = 0;
        backend->render_delta_columns = 0;
        backend->render_delta_rows_count = 0;
        backend->render_delta_state_generation = 0;
        backend->render_delta_cursor_visible = false;
        backend->render_delta_cursor_logical_row = 0;
        backend->render_updates++;
        render_state = NULL;
        row_iterator = NULL;
        row_cells = NULL;
        if (old_row_cells != NULL) g_api.row_cells_free(old_row_cells);
        if (old_row_iterator != NULL) g_api.row_iterator_free(old_row_iterator);
        if (old_render_state != NULL) g_api.render_state_free(old_render_state);
        success = true;
    }
    pthread_mutex_unlock(&backend->mutex);
    pthread_mutex_unlock(&backend->render_mutex);

    if (row_cells != NULL) g_api.row_cells_free(row_cells);
    if (row_iterator != NULL) g_api.row_iterator_free(row_iterator);
    if (render_state != NULL) g_api.render_state_free(render_state);
    return success ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray format_selection_locked(JNIEnv* env,
                                          TermuxGhosttyBackend* backend,
                                          const GhosttySelection* selection,
                                          bool unwrap,
                                          bool trim)
{
    GhosttyTerminalSelectionFormatOptions options =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
    options.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
    options.unwrap = unwrap;
    options.trim = trim;
    options.selection = selection;
    size_t required = 0;
    GhosttyResult result = g_api.terminal_selection_format_buf(
        backend->terminal, options, NULL, 0, &required);
    if (result == GHOSTTY_NO_VALUE || required == 0) return (*env)->NewByteArray(env, 0);
    if (result != GHOSTTY_OUT_OF_SPACE || required > 64U * 1024U * 1024U ||
        required > INT32_MAX) return NULL;
    uint8_t* buffer = malloc(required);
    if (buffer == NULL) return NULL;
    size_t written = 0;
    result = g_api.terminal_selection_format_buf(
        backend->terminal, options, buffer, required, &written);
    jbyteArray output = NULL;
    if (result == GHOSTTY_SUCCESS && written <= required && written <= INT32_MAX) {
        output = byte_array_from_native(env, buffer, written);
    }
    free(buffer);
    return output;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeFormatRange(JNIEnv* env,
                                                                   jclass clazz,
                                                                   jlong handle,
                                                                   jint x1,
                                                                   jint y1,
                                                                   jint x2,
                                                                   jint y2,
                                                                   jboolean unwrap,
                                                                   jboolean trim)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return NULL;
    pthread_mutex_lock(&backend->mutex);
    size_t scrollback_rows = 0;
    size_t total_rows = 0;
    uint16_t columns = 0;
    if (g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                           &scrollback_rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS,
                           &total_rows) != GHOSTTY_SUCCESS ||
        g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLS,
                           &columns) != GHOSTTY_SUCCESS ||
        total_rows == 0 || columns == 0) {
        pthread_mutex_unlock(&backend->mutex);
        return NULL;
    }
    int64_t screen_y1 = (int64_t) scrollback_rows + y1;
    int64_t screen_y2 = (int64_t) scrollback_rows + y2;
    if (screen_y1 < 0) screen_y1 = 0;
    if (screen_y2 < 0) screen_y2 = 0;
    if ((uint64_t) screen_y1 >= total_rows) screen_y1 = (int64_t) total_rows - 1;
    if ((uint64_t) screen_y2 >= total_rows) screen_y2 = (int64_t) total_rows - 1;
    uint16_t column1 = (uint16_t) (x1 < 0 ? 0 : x1 >= columns ? columns - 1 : x1);
    uint16_t column2 = (uint16_t) (x2 < 0 ? 0 : x2 >= columns ? columns - 1 : x2);
    GhosttyPoint start_point = {
        .tag = GHOSTTY_POINT_TAG_SCREEN,
        .value = {.coordinate = {.x = column1, .y = (uint32_t) screen_y1}},
    };
    GhosttyPoint end_point = {
        .tag = GHOSTTY_POINT_TAG_SCREEN,
        .value = {.coordinate = {.x = column2, .y = (uint32_t) screen_y2}},
    };
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    selection.start = (GhosttyGridRef) GHOSTTY_INIT_SIZED(GhosttyGridRef);
    selection.end = (GhosttyGridRef) GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyResult start_result =
        g_api.terminal_grid_ref(backend->terminal, start_point, &selection.start);
    GhosttyResult end_result =
        g_api.terminal_grid_ref(backend->terminal, end_point, &selection.end);
    jbyteArray output = NULL;
    if (start_result == GHOSTTY_SUCCESS && end_result == GHOSTTY_SUCCESS) {
        output = format_selection_locked(
            env, backend, &selection, unwrap == JNI_TRUE, trim == JNI_TRUE);
    }
    pthread_mutex_unlock(&backend->mutex);
    return output;
}

static bool selection_geometry_locked(TermuxGhosttyBackend* backend,
                                      size_t* scrollback_rows,
                                      size_t* total_rows,
                                      uint16_t* columns)
{
    return g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS,
                              scrollback_rows) == GHOSTTY_SUCCESS &&
           g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_TOTAL_ROWS,
                              total_rows) == GHOSTTY_SUCCESS &&
           g_api.terminal_get(backend->terminal, GHOSTTY_TERMINAL_DATA_COLS,
                              columns) == GHOSTTY_SUCCESS &&
           *total_rows > 0 && *columns > 0;
}

static GhosttyPoint clamped_termux_point(jint x,
                                         jint y,
                                         size_t scrollback_rows,
                                         size_t total_rows,
                                         uint16_t columns)
{
    int64_t screen_y = (int64_t) scrollback_rows + y;
    if (screen_y < 0) screen_y = 0;
    if ((uint64_t) screen_y >= total_rows) screen_y = (int64_t) total_rows - 1;
    uint16_t column = (uint16_t) (x < 0 ? 0 : x >= columns ? columns - 1 : x);
    GhosttyPoint point = {
        .tag = GHOSTTY_POINT_TAG_SCREEN,
        .value = {.coordinate = {.x = column, .y = (uint32_t) screen_y}},
    };
    return point;
}

JNIEXPORT jintArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSelectWord(JNIEnv* env,
                                                                  jclass clazz,
                                                                  jlong handle,
                                                                  jint x,
                                                                  jint y)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return NULL;
    pthread_mutex_lock(&backend->mutex);
    size_t scrollback_rows = 0, total_rows = 0;
    uint16_t columns = 0;
    if (!selection_geometry_locked(backend, &scrollback_rows, &total_rows, &columns)) {
        pthread_mutex_unlock(&backend->mutex);
        return NULL;
    }

    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyPoint point = clamped_termux_point(
        x, y, scrollback_rows, total_rows, columns);
    if (g_api.terminal_grid_ref(backend->terminal, point, &ref) != GHOSTTY_SUCCESS) {
        pthread_mutex_unlock(&backend->mutex);
        return NULL;
    }
    GhosttyTerminalSelectWordOptions options =
        GHOSTTY_INIT_SIZED(GhosttyTerminalSelectWordOptions);
    options.ref = ref;
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyResult result =
        g_api.terminal_select_word(backend->terminal, &options, &selection);
    GhosttyPointCoordinate start = {0}, end = {0};
    if (result != GHOSTTY_SUCCESS ||
        g_api.terminal_point_from_grid_ref(backend->terminal, &selection.start,
                                           GHOSTTY_POINT_TAG_SCREEN, &start) != GHOSTTY_SUCCESS ||
        g_api.terminal_point_from_grid_ref(backend->terminal, &selection.end,
                                           GHOSTTY_POINT_TAG_SCREEN, &end) != GHOSTTY_SUCCESS) {
        pthread_mutex_unlock(&backend->mutex);
        return NULL;
    }
    pthread_mutex_unlock(&backend->mutex);

    jint values[4] = {
        (jint) start.x,
        (jint) ((int64_t) start.y - (int64_t) scrollback_rows),
        (jint) end.x,
        (jint) ((int64_t) end.y - (int64_t) scrollback_rows),
    };
    if (values[1] > values[3] || (values[1] == values[3] && values[0] > values[2])) {
        jint swap_x = values[0], swap_y = values[1];
        values[0] = values[2];
        values[1] = values[3];
        values[2] = swap_x;
        values[3] = swap_y;
    }
    jintArray output = (*env)->NewIntArray(env, 4);
    if (output != NULL) (*env)->SetIntArrayRegion(env, output, 0, 4, values);
    return (*env)->ExceptionCheck(env) ? NULL : output;
}

JNIEXPORT jint JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeCellWide(JNIEnv* env,
                                                                jclass clazz,
                                                                jlong handle,
                                                                jint x,
                                                                jint y)
{
    (void) env;
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return -1;
    pthread_mutex_lock(&backend->mutex);
    size_t scrollback_rows = 0, total_rows = 0;
    uint16_t columns = 0;
    GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
    GhosttyCell cell = {0};
    GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
    bool success = selection_geometry_locked(
                       backend, &scrollback_rows, &total_rows, &columns) &&
                   g_api.terminal_grid_ref(
                       backend->terminal,
                       clamped_termux_point(x, y, scrollback_rows, total_rows, columns),
                       &ref) == GHOSTTY_SUCCESS &&
                   g_api.grid_ref_cell(&ref, &cell) == GHOSTTY_SUCCESS &&
                   g_api.cell_get(cell, GHOSTTY_CELL_DATA_WIDE, &wide) == GHOSTTY_SUCCESS;
    pthread_mutex_unlock(&backend->mutex);
    return success ? (jint) wide : -1;
}

static bool hyperlink_already_appended(const uint8_t* output,
                                       size_t output_len,
                                       const uint8_t* uri,
                                       size_t uri_len)
{
    size_t offset = 0;
    while (offset < output_len) {
        const uint8_t* end = memchr(output + offset, 0, output_len - offset);
        size_t length = end == NULL ? output_len - offset : (size_t) (end - output - offset);
        if (length == uri_len && memcmp(output + offset, uri, uri_len) == 0) return true;
        offset += length + (end == NULL ? 0 : 1);
    }
    return false;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeSelectionHyperlinks(JNIEnv* env,
                                                                           jclass clazz,
                                                                           jlong handle,
                                                                           jint x1,
                                                                           jint y1,
                                                                           jint x2,
                                                                           jint y2)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return NULL;
    const size_t output_capacity = TERMUX_GHOSTTY_MAX_SELECTION_HYPERLINKS *
        (TERMUX_GHOSTTY_MAX_HYPERLINK_BYTES + 1U);
    uint8_t* output = malloc(output_capacity);
    uint8_t* uri = malloc(TERMUX_GHOSTTY_MAX_HYPERLINK_BYTES);
    if (output == NULL || uri == NULL) {
        free(output);
        free(uri);
        return NULL;
    }

    pthread_mutex_lock(&backend->mutex);
    size_t scrollback_rows = 0, total_rows = 0;
    uint16_t columns = 0;
    if (!selection_geometry_locked(backend, &scrollback_rows, &total_rows, &columns)) {
        pthread_mutex_unlock(&backend->mutex);
        free(output);
        free(uri);
        return NULL;
    }
    if (y1 > y2 || (y1 == y2 && x1 > x2)) {
        jint swap_x = x1, swap_y = y1;
        x1 = x2;
        y1 = y2;
        x2 = swap_x;
        y2 = swap_y;
    }
    int64_t min_y = -(int64_t) scrollback_rows;
    int64_t max_y = (int64_t) total_rows - (int64_t) scrollback_rows - 1;
    int64_t start_y = y1 < min_y ? min_y : y1 > max_y ? max_y : y1;
    int64_t end_y = y2 < min_y ? min_y : y2 > max_y ? max_y : y2;
    int start_x = x1 < 0 ? 0 : x1 >= columns ? columns - 1 : x1;
    int end_x = x2 < 0 ? 0 : x2 >= columns ? columns - 1 : x2;
    size_t output_len = 0;
    size_t unique_links = 0;
    size_t visited_cells = 0;
    for (int64_t row = start_y;
         row <= end_y && unique_links < TERMUX_GHOSTTY_MAX_SELECTION_HYPERLINKS &&
             visited_cells < 1000000U;
         row++) {
        int from = row == start_y ? start_x : 0;
        int to = row == end_y ? end_x : columns - 1;
        for (int column = from;
             column <= to && unique_links < TERMUX_GHOSTTY_MAX_SELECTION_HYPERLINKS &&
                 visited_cells++ < 1000000U;
             column++) {
            GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
            GhosttyPoint point = {
                .tag = GHOSTTY_POINT_TAG_SCREEN,
                .value = {.coordinate = {
                    .x = (uint16_t) column,
                    .y = (uint32_t) ((int64_t) scrollback_rows + row),
                }},
            };
            if (g_api.terminal_grid_ref(backend->terminal, point, &ref) != GHOSTTY_SUCCESS) continue;
            size_t uri_len = 0;
            GhosttyResult result = g_api.grid_ref_hyperlink_uri(
                &ref, uri, TERMUX_GHOSTTY_MAX_HYPERLINK_BYTES, &uri_len);
            if (result != GHOSTTY_SUCCESS || uri_len == 0 ||
                uri_len > TERMUX_GHOSTTY_MAX_HYPERLINK_BYTES ||
                hyperlink_already_appended(output, output_len, uri, uri_len) ||
                output_len + uri_len + 1U > output_capacity) continue;
            memcpy(output + output_len, uri, uri_len);
            output_len += uri_len;
            output[output_len++] = 0;
            unique_links++;
        }
    }
    pthread_mutex_unlock(&backend->mutex);
    jbyteArray value = byte_array_from_native(env, output, output_len);
    free(output);
    free(uri);
    return value;
}

JNIEXPORT jbyteArray JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeFormatAll(JNIEnv* env,
                                                                 jclass clazz,
                                                                 jlong handle,
                                                                 jboolean unwrap)
{
    (void) clazz;
    TermuxGhosttyBackend* backend = backend_from_handle(handle);
    if (backend == NULL) return NULL;
    pthread_mutex_lock(&backend->mutex);
    GhosttySelection selection = GHOSTTY_INIT_SIZED(GhosttySelection);
    GhosttyResult result = g_api.terminal_select_all(backend->terminal, &selection);
    jbyteArray output = result == GHOSTTY_NO_VALUE
        ? (*env)->NewByteArray(env, 0)
        : result == GHOSTTY_SUCCESS
            ? format_selection_locked(env, backend, &selection, unwrap == JNI_TRUE, true)
            : NULL;
    pthread_mutex_unlock(&backend->mutex);
    return output;
}

JNIEXPORT void JNICALL
Java_com_termux_terminal_GhosttyTerminalBackend_nativeClose(JNIEnv* env,
                                                             jclass clazz,
                                                             jlong handle)
{
    (void) env;
    (void) clazz;
    free_backend(env, backend_from_handle(handle));
}
