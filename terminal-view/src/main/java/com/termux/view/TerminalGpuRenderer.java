package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class TerminalGpuRenderer implements GLSurfaceView.Renderer {

    private static final int ATLAS_SIZE = 2048;
    private static final int GLYPH_PADDING = 2;
    private static final int FLOATS_PER_VERTEX = 9;
    private static final int VERTICES_PER_QUAD = 6;
    private static final int MAX_TEXT_RUN_CACHE_ENTRIES = 4096;
    private static final int MAX_FRAME_REBUILD_ATTEMPTS = 1;
    private static final String LOG_TAG = "TerminalGpuRenderer";

    private static final String VERTEX_SHADER =
        "attribute vec2 a_Position;\n" +
        "attribute vec2 a_TexCoord;\n" +
        "attribute vec4 a_Color;\n" +
        "attribute float a_UseTexture;\n" +
        "uniform vec2 u_Viewport;\n" +
        "varying vec2 v_TexCoord;\n" +
        "varying vec4 v_Color;\n" +
        "varying float v_UseTexture;\n" +
        "void main() {\n" +
        "  vec2 zeroToOne = a_Position / u_Viewport;\n" +
        "  vec2 clip = zeroToOne * 2.0 - 1.0;\n" +
        "  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);\n" +
        "  v_TexCoord = a_TexCoord;\n" +
        "  v_Color = a_Color;\n" +
        "  v_UseTexture = a_UseTexture;\n" +
        "}\n";

    private static final String FRAGMENT_SHADER =
        "precision mediump float;\n" +
        "uniform sampler2D u_Texture;\n" +
        "varying vec2 v_TexCoord;\n" +
        "varying vec4 v_Color;\n" +
        "varying float v_UseTexture;\n" +
        "void main() {\n" +
        "  float glyphAlpha = texture2D(u_Texture, v_TexCoord).a;\n" +
        "  float alpha = mix(1.0, glyphAlpha, v_UseTexture);\n" +
        "  gl_FragColor = vec4(v_Color.rgb, v_Color.a * alpha);\n" +
        "}\n";

    private final Map<TextRunKey, TextRunTexture> textRunCache = new HashMap<>();
    private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);

    private final Object snapshotLock = new Object();
    private TerminalRenderSnapshot snapshot = TerminalRenderSnapshot.empty(1, 1);
    private long submittedSnapshotSerial;
    private long consumedSnapshotSerial;
    private long lastDrawnContentGeneration;
    private boolean lastDrawnFrameContentReady;
    private RowRenderData[] rowCache = new RowRenderData[0];
    private int cachedRows = -1;
    private int cachedViewWidth = -1;
    private int cachedViewHeight = -1;
    private int cachedTextSize = -1;
    private int cachedTypefaceIdentity = 0;
    private float cachedFontWidth = -1f;
    private int cachedFontLineSpacing = -1;

    private int program;
    private int atlasTexture;
    private int atlasX;
    private int atlasY;
    private int atlasRowHeight;
    private int atlasGeneration;
    private boolean frameAtlasResetAllowed;
    private boolean frameRebuildRequested;
    private int frameCacheHits;
    private int frameCacheMisses;
    private int frameUploads;
    private int frameAtlasResets;

    private int aPosition;
    private int aTexCoord;
    private int aColor;
    private int aUseTexture;
    private int uViewport;
    private int uTexture;

    private float[] vertexData = new float[64 * FLOATS_PER_VERTEX * VERTICES_PER_QUAD];
    private int vertexFloatCount;
    private FloatBuffer vertexBuffer;

    void setSnapshot(TerminalRenderSnapshot snapshot) {
        synchronized (snapshotLock) {
            this.snapshot = snapshot == null ? TerminalRenderSnapshot.empty(1, 1) : snapshot;
            submittedSnapshotSerial++;
        }
    }

    boolean hasPendingSnapshot() {
        synchronized (snapshotLock) {
            return submittedSnapshotSerial != consumedSnapshotSerial;
        }
    }

    long getLastDrawnContentGeneration() {
        return lastDrawnContentGeneration;
    }

    boolean isLastDrawnFrameContentReady() {
        return lastDrawnFrameContentReady;
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        resetContextLocalCaches();
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        aPosition = GLES20.glGetAttribLocation(program, "a_Position");
        aTexCoord = GLES20.glGetAttribLocation(program, "a_TexCoord");
        aColor = GLES20.glGetAttribLocation(program, "a_Color");
        aUseTexture = GLES20.glGetAttribLocation(program, "a_UseTexture");
        uViewport = GLES20.glGetUniformLocation(program, "u_Viewport");
        uTexture = GLES20.glGetUniformLocation(program, "u_Texture");

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);

        createAtlas();
    }

    private void resetContextLocalCaches() {
        textRunCache.clear();
        rowCache = new RowRenderData[0];
        cachedRows = -1;
        cachedViewWidth = -1;
        cachedViewHeight = -1;
        cachedTextSize = -1;
        cachedTypefaceIdentity = 0;
        cachedFontWidth = -1f;
        cachedFontLineSpacing = -1;
        program = 0;
        atlasTexture = 0;
        lastDrawnContentGeneration = 0L;
        lastDrawnFrameContentReady = false;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        TerminalRenderSnapshot frame;
        long frameSerial;
        synchronized (snapshotLock) {
            frame = snapshot;
            frameSerial = submittedSnapshotSerial;
        }
        int width = Math.max(1, frame.viewWidth);
        int height = Math.max(1, frame.viewHeight);
        long frameStartMs = isPerfLoggingEnabled() ? SystemClock.uptimeMillis() : 0L;

        beginFrame();
        updateRowCache(frame);

        int rebuildAttempts = 0;
        while (true) {
            frameRebuildRequested = false;
            vertexFloatCount = 0;

            for (int row = 0; row < rowCache.length; row++) {
                addCachedRow(frame, rowCache[row], row);
                if (frameRebuildRequested) break;
            }

            if (!frameRebuildRequested || rebuildAttempts >= MAX_FRAME_REBUILD_ATTEMPTS) break;

            rebuildAttempts++;
            clearAtlas();
            frameAtlasResets++;
            frameAtlasResetAllowed = rebuildAttempts < MAX_FRAME_REBUILD_ATTEMPTS;
        }

        clear(frame.backgroundColor);
        flush(width, height);
        lastDrawnContentGeneration = frame.contentGeneration;
        lastDrawnFrameContentReady = frame.contentReady;
        synchronized (snapshotLock) {
            if (frameSerial > consumedSnapshotSerial) consumedSnapshotSerial = frameSerial;
        }
        if (isPerfLoggingEnabled()) {
            Log.d(LOG_TAG, "drawFrame ms=" + (SystemClock.uptimeMillis() - frameStartMs)
                + " rows=" + rowCache.length
                + " textRuns=" + frame.textRuns.size()
                + " cache=" + textRunCache.size()
                + " hits=" + frameCacheHits
                + " misses=" + frameCacheMisses
                + " uploads=" + frameUploads
                + " atlasResets=" + frameAtlasResets
                + " atlasGen=" + atlasGeneration
                + " full=" + frame.fullFrame
                + " dirty=" + frame.dirtyRowStart + ".." + frame.dirtyRowEnd
                + " scroll=" + frame.scrollRows);
        }
    }

    private void beginFrame() {
        frameAtlasResetAllowed = MAX_FRAME_REBUILD_ATTEMPTS > 0;
        frameRebuildRequested = false;
        frameCacheHits = 0;
        frameCacheMisses = 0;
        frameUploads = 0;
        frameAtlasResets = 0;

        if (textRunCache.size() >= MAX_TEXT_RUN_CACHE_ENTRIES) {
            clearAtlas();
            frameAtlasResets++;
        }
    }

    private void updateRowCache(TerminalRenderSnapshot frame) {
        int typefaceIdentity = System.identityHashCode(frame.typeface);
        boolean reset = frame.fullFrame
            || cachedRows != frame.screenRows
            || cachedViewWidth != frame.viewWidth
            || cachedViewHeight != frame.viewHeight
            || cachedTextSize != frame.textSize
            || cachedTypefaceIdentity != typefaceIdentity
            || cachedFontWidth != frame.fontWidth
            || cachedFontLineSpacing != frame.fontLineSpacing;

        if (reset) {
            int rows = Math.max(0, frame.screenRows);
            rowCache = new RowRenderData[rows];
            for (int row = 0; row < rows; row++) {
                rowCache[row] = new RowRenderData(row);
            }
            cachedRows = frame.screenRows;
            cachedViewWidth = frame.viewWidth;
            cachedViewHeight = frame.viewHeight;
            cachedTextSize = frame.textSize;
            cachedTypefaceIdentity = typefaceIdentity;
            cachedFontWidth = frame.fontWidth;
            cachedFontLineSpacing = frame.fontLineSpacing;
        } else if (frame.scrollRows > 0) {
            shiftRowsUp(frame.scrollRows);
        }

        int start = Math.max(0, frame.dirtyRowStart);
        int end = Math.min(rowCache.length, frame.dirtyRowEnd);
        for (int row = start; row < end; row++) {
            rowCache[row] = new RowRenderData(row);
        }

        for (TerminalRenderSnapshot.RenderRect rect : frame.backgroundRects) {
            RowRenderData row = rowDataFor(rect.row);
            if (row != null) row.backgroundRects.add(rect);
        }

        for (TerminalRenderSnapshot.TextRun run : frame.textRuns) {
            RowRenderData row = rowDataFor(run.row);
            if (row != null) row.textRuns.add(run);
        }

        for (TerminalRenderSnapshot.RenderRect rect : frame.decorationRects) {
            RowRenderData row = rowDataFor(rect.row);
            if (row != null) row.decorationRects.add(rect);
        }
    }

    private RowRenderData rowDataFor(int row) {
        return row >= 0 && row < rowCache.length ? rowCache[row] : null;
    }

    private void shiftRowsUp(int rows) {
        if (rows <= 0 || rowCache.length == 0) return;
        if (rows >= rowCache.length) {
            for (int row = 0; row < rowCache.length; row++) {
                rowCache[row] = new RowRenderData(row);
            }
            return;
        }

        int keepRows = rowCache.length - rows;
        System.arraycopy(rowCache, rows, rowCache, 0, keepRows);
        for (int row = keepRows; row < rowCache.length; row++) {
            rowCache[row] = new RowRenderData(row);
        }
    }

    private void addCachedRow(TerminalRenderSnapshot frame, RowRenderData rowData, int targetRow) {
        if (rowData == null) return;
        float yOffset = (targetRow - rowData.sourceRow) * frame.fontLineSpacing;

        for (TerminalRenderSnapshot.RenderRect rect : rowData.backgroundRects) {
            addSolidRect(rect.left, rect.top + yOffset, rect.right, rect.bottom + yOffset, rect.color);
        }

        for (TerminalRenderSnapshot.TextRun run : rowData.textRuns) {
            TextRunTexture texture = getTextRunTexture(frame, run);
            if (texture.width <= 0 || texture.height <= 0) continue;

            float left = run.left - GLYPH_PADDING;
            float top = run.top + yOffset;
            float right = left + texture.width;
            float bottom = top + texture.height;
            addTextRunRect(left, top, right, bottom, texture, run.color);
        }

        for (TerminalRenderSnapshot.RenderRect rect : rowData.decorationRects) {
            addSolidRect(rect.left, rect.top + yOffset, rect.right, rect.bottom + yOffset, rect.color);
        }
    }

    private void clear(int color) {
        GLES20.glClearColor(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f,
            Color.alpha(color) / 255f
        );
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
    }

    private void flush(int width, int height) {
        if (vertexFloatCount == 0 || program == 0 || atlasTexture == 0) return;

        ensureVertexBuffer(vertexFloatCount);
        vertexBuffer.clear();
        vertexBuffer.put(vertexData, 0, vertexFloatCount);
        vertexBuffer.flip();

        GLES20.glUseProgram(program);
        GLES20.glUniform2f(uViewport, width, height);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture);
        GLES20.glUniform1i(uTexture, 0);

        int stride = FLOATS_PER_VERTEX * 4;
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPosition);

        vertexBuffer.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aTexCoord);

        vertexBuffer.position(4);
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aColor);

        vertexBuffer.position(8);
        GLES20.glVertexAttribPointer(aUseTexture, 1, GLES20.GL_FLOAT, false, stride, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aUseTexture);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexFloatCount / FLOATS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glDisableVertexAttribArray(aColor);
        GLES20.glDisableVertexAttribArray(aUseTexture);
    }

    private void addSolidRect(float left, float top, float right, float bottom, int color) {
        if (right <= left || bottom <= top) return;
        addQuad(left, top, right, bottom, 0f, 0f, 0f, 0f, color, 0f);
    }

    private void addTextRunRect(float left, float top, float right, float bottom, TextRunTexture texture, int color) {
        addQuad(left, top, right, bottom, texture.uLeft, texture.vTop, texture.uRight, texture.vBottom, color, 1f);
    }

    private void addQuad(float left, float top, float right, float bottom,
                         float uLeft, float vTop, float uRight, float vBottom,
                         int color, float useTexture) {
        ensureVertexData(vertexFloatCount + VERTICES_PER_QUAD * FLOATS_PER_VERTEX);

        putVertex(left, top, uLeft, vTop, color, useTexture);
        putVertex(left, bottom, uLeft, vBottom, color, useTexture);
        putVertex(right, bottom, uRight, vBottom, color, useTexture);
        putVertex(left, top, uLeft, vTop, color, useTexture);
        putVertex(right, bottom, uRight, vBottom, color, useTexture);
        putVertex(right, top, uRight, vTop, color, useTexture);
    }

    private void putVertex(float x, float y, float u, float v, int color, float useTexture) {
        vertexData[vertexFloatCount++] = x;
        vertexData[vertexFloatCount++] = y;
        vertexData[vertexFloatCount++] = u;
        vertexData[vertexFloatCount++] = v;
        vertexData[vertexFloatCount++] = Color.red(color) / 255f;
        vertexData[vertexFloatCount++] = Color.green(color) / 255f;
        vertexData[vertexFloatCount++] = Color.blue(color) / 255f;
        vertexData[vertexFloatCount++] = Color.alpha(color) / 255f;
        vertexData[vertexFloatCount++] = useTexture;
    }

    private TextRunTexture getTextRunTexture(TerminalRenderSnapshot frame, TerminalRenderSnapshot.TextRun run) {
        TextRunKey key = new TextRunKey(
            run.text,
            frame.textSize,
            (int) Math.ceil(run.width),
            run.flags,
            System.identityHashCode(frame.typeface)
        );
        TextRunTexture cached = textRunCache.get(key);
        if (cached != null) {
            frameCacheHits++;
            return cached;
        }

        frameCacheMisses++;
        if (frameRebuildRequested) return TextRunTexture.empty();
        TextRunTexture loaded = rasterizeAndUploadTextRun(frame, run);
        if (!loaded.isEmpty()) textRunCache.put(key, loaded);
        return loaded;
    }

    private TextRunTexture rasterizeAndUploadTextRun(TerminalRenderSnapshot frame, TerminalRenderSnapshot.TextRun run) {
        float targetAdvance = Math.max(1f, run.width);
        int glyphWidth = Math.max(1, (int) Math.ceil(targetAdvance)) + GLYPH_PADDING * 2;
        int glyphHeight = Math.max(1, frame.fontLineSpacing);
        if (glyphWidth > ATLAS_SIZE || glyphHeight > ATLAS_SIZE) return TextRunTexture.empty();

        if (!reserveAtlasSpace(glyphWidth, glyphHeight)) {
            if (requestFrameRebuildForAtlasExhaustion()) return TextRunTexture.empty();
            return TextRunTexture.empty();
        }

        glyphPaint.reset();
        glyphPaint.setAntiAlias(true);
        glyphPaint.setSubpixelText(true);
        glyphPaint.setColor(Color.WHITE);
        glyphPaint.setTextSize(frame.textSize);
        glyphPaint.setTypeface(frame.typeface == null ? Typeface.MONOSPACE : frame.typeface);
        glyphPaint.setFakeBoldText((run.flags & TerminalRenderSnapshot.FLAG_BOLD) != 0);
        glyphPaint.setTextSkewX((run.flags & TerminalRenderSnapshot.FLAG_ITALIC) != 0 ? -0.35f : 0f);
        glyphPaint.setUnderlineText((run.flags & TerminalRenderSnapshot.FLAG_UNDERLINE) != 0);
        glyphPaint.setStrikeThruText((run.flags & TerminalRenderSnapshot.FLAG_STRIKETHROUGH) != 0);

        Bitmap bitmap = Bitmap.createBitmap(glyphWidth, glyphHeight, Bitmap.Config.ALPHA_8);
        Canvas canvas = new Canvas(bitmap);
        float baseline = Math.max(0f, -frame.fontAscent);
        if (baseline > glyphHeight) baseline = glyphHeight;
        char[] text = run.text.toCharArray();
        float measuredAdvance = run.measuredWidth > 0f ? run.measuredWidth : glyphPaint.measureText(text, 0, text.length);
        canvas.save();
        canvas.translate(GLYPH_PADDING, 0f);
        if (measuredAdvance > 0f && Math.abs(measuredAdvance - targetAdvance) > 0.01f) {
            canvas.scale(targetAdvance / measuredAdvance, 1f);
        }
        canvas.drawTextRun(text, 0, text.length, 0, text.length, 0f, baseline, false, glyphPaint);
        canvas.restore();

        ByteBuffer buffer = ByteBuffer.allocateDirect(glyphWidth * glyphHeight);
        bitmap.copyPixelsToBuffer(buffer);
        buffer.position(0);
        bitmap.recycle();

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture);
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            atlasX,
            atlasY,
            glyphWidth,
            glyphHeight,
            GLES20.GL_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer
        );

        TextRunTexture texture = new TextRunTexture(
            glyphWidth,
            glyphHeight,
            atlasX / (float) ATLAS_SIZE,
            atlasY / (float) ATLAS_SIZE,
            (atlasX + glyphWidth) / (float) ATLAS_SIZE,
            (atlasY + glyphHeight) / (float) ATLAS_SIZE
        );

        atlasX += glyphWidth;
        if (glyphHeight > atlasRowHeight) atlasRowHeight = glyphHeight;
        frameUploads++;
        return texture;
    }

    private boolean reserveAtlasSpace(int width, int height) {
        if (atlasX + width > ATLAS_SIZE) {
            atlasX = 0;
            atlasY += atlasRowHeight;
            atlasRowHeight = 0;
        }
        return atlasY + height <= ATLAS_SIZE;
    }

    private void createAtlas() {
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        atlasTexture = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_ALPHA,
            ATLAS_SIZE,
            ATLAS_SIZE,
            0,
            GLES20.GL_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        );
        atlasX = 0;
        atlasY = 0;
        atlasRowHeight = 0;
        atlasGeneration++;
        textRunCache.clear();
    }

    private void clearAtlas() {
        textRunCache.clear();
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture);
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_ALPHA,
            ATLAS_SIZE,
            ATLAS_SIZE,
            0,
            GLES20.GL_ALPHA,
            GLES20.GL_UNSIGNED_BYTE,
            null
        );
        atlasX = 0;
        atlasY = 0;
        atlasRowHeight = 0;
        atlasGeneration++;
    }

    private boolean requestFrameRebuildForAtlasExhaustion() {
        if (!frameAtlasResetAllowed) return false;
        frameRebuildRequested = true;
        return true;
    }

    private static boolean isPerfLoggingEnabled() {
        return Log.isLoggable(LOG_TAG, Log.DEBUG);
    }

    private void ensureVertexData(int requiredFloats) {
        if (vertexData.length >= requiredFloats) return;
        int newSize = vertexData.length;
        while (newSize < requiredFloats) newSize *= 2;
        float[] next = new float[newSize];
        System.arraycopy(vertexData, 0, next, 0, vertexFloatCount);
        vertexData = next;
    }

    private void ensureVertexBuffer(int requiredFloats) {
        if (vertexBuffer != null && vertexBuffer.capacity() >= requiredFloats) return;
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(requiredFloats * 4).order(ByteOrder.nativeOrder());
        vertexBuffer = byteBuffer.asFloatBuffer();
    }

    private static int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link terminal GPU renderer: " + log);
        }
        return program;
    }

    private static int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile terminal GPU shader: " + log);
        }
        return shader;
    }

    private static final class TextRunTexture {
        final int width;
        final int height;
        final float uLeft;
        final float vTop;
        final float uRight;
        final float vBottom;

        TextRunTexture(int width, int height, float uLeft, float vTop, float uRight, float vBottom) {
            this.width = width;
            this.height = height;
            this.uLeft = uLeft;
            this.vTop = vTop;
            this.uRight = uRight;
            this.vBottom = vBottom;
        }

        static TextRunTexture empty() {
            return new TextRunTexture(0, 0, 0f, 0f, 0f, 0f);
        }

        boolean isEmpty() {
            return width <= 0 || height <= 0;
        }
    }

    private static final class RowRenderData {
        final int sourceRow;
        final List<TerminalRenderSnapshot.RenderRect> backgroundRects = new ArrayList<>();
        final List<TerminalRenderSnapshot.TextRun> textRuns = new ArrayList<>();
        final List<TerminalRenderSnapshot.RenderRect> decorationRects = new ArrayList<>();

        RowRenderData(int sourceRow) {
            this.sourceRow = sourceRow;
        }
    }

    private static final class TextRunKey {
        final String text;
        final int textSize;
        final int width;
        final int flags;
        final int typefaceIdentity;

        TextRunKey(String text, int textSize, int width, int flags, int typefaceIdentity) {
            this.text = text;
            this.textSize = textSize;
            this.width = width;
            this.flags = flags;
            this.typefaceIdentity = typefaceIdentity;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof TextRunKey)) return false;
            TextRunKey key = (TextRunKey) other;
            return textSize == key.textSize
                && width == key.width
                && flags == key.flags
                && typefaceIdentity == key.typefaceIdentity
                && text.equals(key.text);
        }

        @Override
        public int hashCode() {
            int result = text.hashCode();
            result = 31 * result + textSize;
            result = 31 * result + width;
            result = 31 * result + flags;
            result = 31 * result + typefaceIdentity;
            return result;
        }
    }
}
