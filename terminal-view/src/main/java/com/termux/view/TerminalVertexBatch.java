package com.termux.view;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Heap-backed packed quad instances compiled once per retained terminal row. */
final class TerminalVertexBatch {
    static final int INSTANCE_BYTES = 32;
    private static final int INSTANCE_INTS = INSTANCE_BYTES / Integer.BYTES;

    private int[] words;
    private int wordCount;

    TerminalVertexBatch(int initialQuads) {
        words = new int[Math.max(INSTANCE_INTS,
            Math.max(1, initialQuads) * INSTANCE_INTS)];
    }

    void clear() {
        wordCount = 0;
    }

    void appendQuad(float left, float top, float right, float bottom,
                    float uLeft, float vTop, float uRight, float vBottom,
                    int color, int mode) {
        appendPackedQuad(left, top, right, bottom,
            packAtlasCoordinate(uLeft) | (packAtlasCoordinate(vTop) << 16),
            packAtlasCoordinate(uRight) | (packAtlasCoordinate(vBottom) << 16),
            packRgba(color), mode);
    }

    void appendPackedQuad(float left, float top, float right, float bottom,
                          int uvLeftTop, int uvRightBottom, int packedColor, int mode) {
        if (right <= left || bottom <= top) return;
        ensureCapacity(wordCount + INSTANCE_INTS);
        int base = wordCount;
        words[base] = Float.floatToRawIntBits(left);
        words[base + 1] = Float.floatToRawIntBits(top);
        words[base + 2] = Float.floatToRawIntBits(right);
        words[base + 3] = Float.floatToRawIntBits(bottom);
        words[base + 4] = uvLeftTop;
        words[base + 5] = uvRightBottom;
        words[base + 6] = packedColor;
        words[base + 7] = mode;
        wordCount += INSTANCE_INTS;
    }

    int byteCount() {
        return wordCount * Integer.BYTES;
    }

    int instanceCount() {
        return wordCount / INSTANCE_INTS;
    }

    int wordCount() {
        return wordCount;
    }

    int[] copyWords() {
        return copyWords(null);
    }

    int[] copyWords(int[] target) {
        if (target == null || target.length < wordCount) {
            return Arrays.copyOf(words, wordCount);
        }
        System.arraycopy(words, 0, target, 0, wordCount);
        return target;
    }

    byte[] copyBytes() {
        ByteBuffer encoded = ByteBuffer.allocate(byteCount()).order(ByteOrder.nativeOrder());
        encoded.asIntBuffer().put(words, 0, wordCount);
        return encoded.array();
    }

    static int packRgba(int color) {
        int a = (color >>> 24) & 0xff;
        int r = (color >>> 16) & 0xff;
        int g = (color >>> 8) & 0xff;
        int b = color & 0xff;
        return r | (g << 8) | (b << 16) | (a << 24);
    }

    private static int packAtlasCoordinate(float coordinate) {
        if (!Float.isFinite(coordinate) || coordinate <= 0f) return 0;
        return Math.min(0xffff, Math.round(coordinate));
    }

    private void ensureCapacity(int required) {
        if (words.length >= required) return;
        int capacity = words.length;
        while (capacity < required) capacity *= 2;
        words = Arrays.copyOf(words, capacity);
    }
}
