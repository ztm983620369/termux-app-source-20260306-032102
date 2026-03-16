package com.termux.view.textselection;

import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.UrlDetector;
import com.termux.terminal.WcWidth;
import com.termux.view.R;
import com.termux.view.TerminalView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class TextSelectionCursorController implements CursorController {

    private final TerminalView terminalView;
    private final TextSelectionHandleView mStartHandle, mEndHandle;
    private String mStoredSelectedText;
    private boolean mIsSelectingText = false;
    private long mShowStartTime = System.currentTimeMillis();
    private float mLastPreviewAnchorX = Float.NaN;
    private float mLastPreviewAnchorY = Float.NaN;
    private int mLastPreviewFocusX = -1;
    private int mLastPreviewFocusY = Integer.MIN_VALUE;

    private final int mHandleHeight;
    private int mSelX1 = -1, mSelX2 = -1, mSelY1 = -1, mSelY2 = -1;

    private ActionMode mActionMode;
    public final int ACTION_COPY = 1;
    public final int ACTION_PASTE = 2;
    public final int ACTION_MORE = 3;
    public final int ACTION_OPEN = 4;

    private int mCachedUrlSelX1 = Integer.MIN_VALUE;
    private int mCachedUrlSelY1 = Integer.MIN_VALUE;
    private int mCachedUrlSelX2 = Integer.MIN_VALUE;
    private int mCachedUrlSelY2 = Integer.MIN_VALUE;
    @Nullable
    private LinkedHashSet<String> mCachedDetectedUrls;

    public TextSelectionCursorController(TerminalView terminalView) {
        this.terminalView = terminalView;
        mStartHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.LEFT);
        mEndHandle = new TextSelectionHandleView(terminalView, this, TextSelectionHandleView.RIGHT);

        mHandleHeight = Math.max(mStartHandle.getHandleHeight(), mEndHandle.getHandleHeight());
    }

    @Override
    public void show(MotionEvent event) {
        setInitialTextSelectionPosition(event);
        invalidateDetectedUrlCache();
        mStartHandle.positionAtCursor(mSelX1, mSelY1, true);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, true);

        setActionModeCallBacks();
        mShowStartTime = System.currentTimeMillis();
        mIsSelectingText = true;
    }

    void onHandleDragStarted(TextSelectionHandleView handle) {
        if (!isActive()) return;
        updateSelectionPreviewForHandle(handle);
    }

    void onHandleDragStopped() {
        terminalView.hideTextSelectionPreview();
    }

    @Override
    public boolean hide() {
        if (!isActive()) return false;

        // prevent hide calls right after a show call, like long pressing the down key
        // 300ms seems long enough that it wouldn't cause hide problems if action button
        // is quickly clicked after the show, otherwise decrease it
        if (System.currentTimeMillis() - mShowStartTime < 300) {
            return false;
        }

        mStartHandle.hide();
        mEndHandle.hide();

        if (mActionMode != null) {
            // This will hide the TextSelectionCursorController
            mActionMode.finish();
        }

        mSelX1 = mSelY1 = mSelX2 = mSelY2 = -1;
        mIsSelectingText = false;
        terminalView.hideTextSelectionPreview();
        invalidateDetectedUrlCache();

        return true;
    }

    @Override
    public void render() {
        if (!isActive()) return;

        mStartHandle.positionAtCursor(mSelX1, mSelY1, false);
        mEndHandle.positionAtCursor(mSelX2 + 1, mSelY2, false);

        if (mActionMode != null) {
            mActionMode.invalidate();
        }
    }

    public void setInitialTextSelectionPosition(MotionEvent event) {
        int[] columnAndRow = terminalView.getColumnAndRow(event, true);
        mSelX1 = mSelX2 = columnAndRow[0];
        mSelY1 = mSelY2 = columnAndRow[1];

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        if (!" ".equals(screen.getSelectedText(mSelX1, mSelY1, mSelX1, mSelY1))) {
            // Selecting something other than whitespace. Expand to word.
            while (mSelX1 > 0 && !"".equals(screen.getSelectedText(mSelX1 - 1, mSelY1, mSelX1 - 1, mSelY1))) {
                mSelX1--;
            }
            while (mSelX2 < terminalView.mEmulator.mColumns - 1 && !"".equals(screen.getSelectedText(mSelX2 + 1, mSelY1, mSelX2 + 1, mSelY1))) {
                mSelX2++;
            }
        }
    }
    
    public void setActionModeCallBacks() {
        final ActionMode.Callback callback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                int showPrimary = MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT;

                ClipboardManager clipboard = (ClipboardManager) terminalView.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, R.string.copy_text).setShowAsAction(showPrimary);
                menu.add(Menu.NONE, ACTION_OPEN, Menu.NONE, R.string.open_url)
                    .setVisible(!getDetectedUrlsForSelection().isEmpty())
                    .setShowAsAction(showPrimary);
                menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, R.string.paste_text)
                    .setEnabled(clipboard != null && clipboard.hasPrimaryClip())
                    .setShowAsAction(showPrimary);
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, R.string.text_selection_more);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                MenuItem openItem = menu.findItem(ACTION_OPEN);
                if (openItem == null) return false;

                boolean shouldShowOpen = !getDetectedUrlsForSelection().isEmpty();
                if (openItem.isVisible() != shouldShowOpen) {
                    openItem.setVisible(shouldShowOpen);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (!isActive()) {
                    // Fix issue where the dialog is pressed while being dismissed.
                    return true;
                }

                switch (item.getItemId()) {
                    case ACTION_COPY:
                        String selectedText = getSelectedText();
                        terminalView.mTermSession.onCopyTextToClipboard(selectedText);
                        terminalView.stopTextSelectionMode();
                        break;
                    case ACTION_OPEN:
                        String url = getBestDetectedUrlForSelection();
                        if (!TextUtils.isEmpty(url)) {
                            terminalView.stopTextSelectionMode();
                            openUrl(url);
                        }
                        break;
                    case ACTION_PASTE:
                        terminalView.stopTextSelectionMode();
                        terminalView.mTermSession.onPasteTextFromClipboard();
                        break;
                    case ACTION_MORE:
                        // We first store the selected text in case TerminalViewClient needs the
                        // selected text before MORE button was pressed since we are going to
                        // stop selection mode
                        mStoredSelectedText = getSelectedText();
                        // The text selection needs to be stopped before showing context menu,
                        // otherwise handles will show above popup
                        terminalView.stopTextSelectionMode();
                        terminalView.showContextMenu();
                        break;
                }

                return true;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
            }

        };

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            mActionMode = terminalView.startActionMode(callback);
            return;
        }

        //noinspection NewApi
        mActionMode = terminalView.startActionMode(new ActionMode.Callback2() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                return callback.onCreateActionMode(mode, menu);
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return callback.onPrepareActionMode(mode, menu);
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return callback.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // Ignore.
            }

            @Override
            public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                int x1 = Math.round(mSelX1 * terminalView.mRenderer.getFontWidth());
                int x2 = Math.round(mSelX2 * terminalView.mRenderer.getFontWidth());
                int y1 = Math.round((mSelY1 - 1 - terminalView.getTopRow()) * terminalView.mRenderer.getFontLineSpacing());
                int y2 = Math.round((mSelY2 + 1 - terminalView.getTopRow()) * terminalView.mRenderer.getFontLineSpacing());

                if (x1 > x2) {
                    int tmp = x1;
                    x1 = x2;
                    x2 = tmp;
                }

                int terminalBottom = terminalView.getBottom();
                int top = y1 + mHandleHeight;
                int bottom = y2 + mHandleHeight;
                if (top > terminalBottom) top = terminalBottom;
                if (bottom > terminalBottom) bottom = terminalBottom;

                outRect.set(x1, top, x2, bottom);
            }
        }, ActionMode.TYPE_FLOATING);
    }

    @Nullable
    private String getBestDetectedUrlForSelection() {
        LinkedHashSet<String> urls = getDetectedUrlsForSelection();
        if (urls.isEmpty()) return null;
        return urls.iterator().next();
    }

    private void invalidateDetectedUrlCache() {
        mCachedUrlSelX1 = Integer.MIN_VALUE;
        mCachedUrlSelY1 = Integer.MIN_VALUE;
        mCachedUrlSelX2 = Integer.MIN_VALUE;
        mCachedUrlSelY2 = Integer.MIN_VALUE;
        mCachedDetectedUrls = null;
    }

    private boolean isDetectedUrlCacheValid() {
        return mCachedDetectedUrls != null &&
            mCachedUrlSelX1 == mSelX1 && mCachedUrlSelY1 == mSelY1 &&
            mCachedUrlSelX2 == mSelX2 && mCachedUrlSelY2 == mSelY2;
    }

    private LinkedHashSet<String> getDetectedUrlsForSelection() {
        if (isDetectedUrlCacheValid()) return mCachedDetectedUrls;

        mCachedUrlSelX1 = mSelX1;
        mCachedUrlSelY1 = mSelY1;
        mCachedUrlSelX2 = mSelX2;
        mCachedUrlSelY2 = mSelY2;

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (terminalView.mEmulator == null) {
            mCachedDetectedUrls = urls;
            return urls;
        }

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int columns = terminalView.mEmulator.mColumns;

        // Prefer local context near selection (fast, avoids scanning huge selection ranges).
        List<String> probes = new ArrayList<>(8);
        probes.add(screen.getWordAtLocation(mSelX1, mSelY1));
        probes.add(screen.getWordAtLocation(mSelX2, mSelY2));

        // For small selections, also scan selected text directly (helps when selection spans multiple tokens).
        int rowSpan = Math.abs(mSelY2 - mSelY1) + 1;
        if (rowSpan == 1) {
            int midX = (mSelX1 + mSelX2) / 2;
            probes.add(screen.getWordAtLocation(midX, mSelY1));
        } else if (rowSpan == 2) {
            int midStartX = (mSelX1 + (columns - 1)) / 2;
            probes.add(screen.getWordAtLocation(midStartX, mSelY1));

            int midEndX = mSelX2 / 2;
            probes.add(screen.getWordAtLocation(midEndX, mSelY2));
        } else {
            // Probe an intermediate row where selection is full-width to avoid false positives from
            // sampling outside the selected range on the start/end rows.
            int midY = (mSelY1 + mSelY2) / 2;
            if (midY == mSelY1) midY++;
            else if (midY == mSelY2) midY--;
            int midX = columns / 2;
            probes.add(screen.getWordAtLocation(midX, midY));
        }
        if (rowSpan <= 6) {
            probes.add(getSelectedText());
        }

        for (String probe : probes) {
            if (TextUtils.isEmpty(probe)) continue;
            urls.addAll(UrlDetector.extractUrls(probe, /*allowWithoutScheme*/ true));
            if (urls.size() >= 4) break; // keep UI decision fast and deterministic
        }

        mCachedDetectedUrls = urls;
        return urls;
    }

    private void openUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            terminalView.getContext().startActivity(intent);
        } catch (ActivityNotFoundException ignored) {
            // No handler for ACTION_VIEW, keep silent to avoid breaking selection flow.
        }
    }

    @Override
    public void updatePosition(TextSelectionHandleView handle, int x, int y) {
        if (terminalView.mEmulator == null) return;

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        final int scrollRows = screen.getActiveRows() - terminalView.mEmulator.mRows;
        final int columns = terminalView.mEmulator.mColumns;
        final float fontWidth = terminalView.mRenderer.getFontWidth();
        if (handle == mStartHandle) {
            // Invert {@link TerminalView#getPointX(int)} (which uses Math.round) by snapping to the nearest cell
            // boundary. This avoids systematic +/-1 drift when fontWidth is non-integer.
            mSelX1 = clamp(Math.round(x / fontWidth), 0, Math.max(0, columns - 1));
            mSelY1 = terminalView.getCursorY(y);
            if (mSelX1 < 0) {
                mSelX1 = 0;
            }

            if (mSelY1 < -scrollRows) {
                mSelY1 = -scrollRows;

            } else if (mSelY1 > terminalView.mEmulator.mRows - 1) {
                mSelY1 = terminalView.mEmulator.mRows - 1;

            }

            if (mSelY1 > mSelY2) {
                mSelY1 = mSelY2;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX1 = mSelX2;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY1 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY1 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX1 = snapSelectionStartColumn(screen, mSelY1, mSelX1);

        } else {
            // End handle hotspot represents the boundary *after* the last selected character. Snap to the nearest
            // boundary (same rationale as start handle) to avoid +/-1 drift when fontWidth is non-integer.
            int endBoundaryColumn = clamp(Math.round(x / fontWidth), 1, columns);
            mSelY2 = terminalView.getCursorY(y);
            mSelX2 = Math.max(0, Math.min(columns - 1, endBoundaryColumn - 1));
            if (mSelX2 < 0) {
                mSelX2 = 0;
            }

            if (mSelY2 < -scrollRows) {
                mSelY2 = -scrollRows;
            } else if (mSelY2 > terminalView.mEmulator.mRows - 1) {
                mSelY2 = terminalView.mEmulator.mRows - 1;
            }

            if (mSelY1 > mSelY2) {
                mSelY2 = mSelY1;
            }
            if (mSelY1 == mSelY2 && mSelX1 > mSelX2) {
                mSelX2 = mSelX1;
            }

            if (!terminalView.mEmulator.isAlternateBufferActive()) {
                int topRow = terminalView.getTopRow();

                if (mSelY2 <= topRow) {
                    topRow--;
                    if (topRow < -scrollRows) {
                        topRow = -scrollRows;
                    }
                } else if (mSelY2 >= topRow + terminalView.mEmulator.mRows) {
                    topRow++;
                    if (topRow > 0) {
                        topRow = 0;
                    }
                }

                terminalView.setTopRow(topRow);
            }

            mSelX2 = snapSelectionEndColumn(screen, mSelY2, mSelX2, columns);
        }

        invalidateDetectedUrlCache();
        terminalView.invalidate();
        updateSelectionPreviewForHandle(handle);
    }

    private void updateSelectionPreviewForHandle(TextSelectionHandleView handle) {
        if (handle == mStartHandle) {
            int boundaryX = mSelX1;
            float anchorX = terminalView.getPointX(boundaryX);
            float anchorY = terminalView.getPointY(mSelY1 + 1);
            updateSelectionPreview(mSelX1, mSelY1, anchorX, anchorY);
        } else if (handle == mEndHandle) {
            int boundaryX = mSelX2 + 1;
            float anchorX = terminalView.getPointX(boundaryX);
            float anchorY = terminalView.getPointY(mSelY2 + 1);
            updateSelectionPreview(mSelX2, mSelY2, anchorX, anchorY);
        }
    }

    private int snapSelectionStartColumn(TerminalBuffer screen, int row, int column) {
        if (column <= 0) return column;
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        if (line.findStartOfColumn(column) == line.findStartOfColumn(column - 1)) {
            // Prevent selecting the 2nd half of a wide char as start.
            return column - 1;
        }
        return column;
    }

    private int snapSelectionEndColumn(TerminalBuffer screen, int row, int column, int columns) {
        if (column < 0 || column >= columns) return column;
        if (column + 1 >= columns) return column;
        TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
        if (line.findStartOfColumn(column + 1) == line.findStartOfColumn(column)) {
            // Prevent selecting only the 1st half of a wide char as end.
            return Math.min(columns - 1, column + 1);
        }
        return column;
    }

    public void decrementYTextSelectionCursors(int decrement) {
        mSelY1 -= decrement;
        mSelY2 -= decrement;
        invalidateDetectedUrlCache();
    }

    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    public void onTouchModeChanged(boolean isInTouchMode) {
        if (!isInTouchMode) {
            terminalView.stopTextSelectionMode();
        }
    }

    @Override
    public void onDetached() {
        terminalView.hideTextSelectionPreview();
    }

    @Override
    public boolean isActive() {
        return mIsSelectingText;
    }

    public void getSelectors(int[] sel) {
        if (sel == null || sel.length != 4) {
            return;
        }

        sel[0] = mSelY1;
        sel[1] = mSelY2;
        sel[2] = mSelX1;
        sel[3] = mSelX2;
    }

    /** Get the currently selected text. */
    public String getSelectedText() {
        return terminalView.mEmulator.getSelectedText(mSelX1, mSelY1, mSelX2, mSelY2);
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mStoredSelectedText;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        mStoredSelectedText = null;
    }

    public ActionMode getActionMode() {
        return mActionMode;
    }

    /**
     * @return true if this controller is currently used to move the start selection.
     */
    public boolean isSelectionStartDragged() {
        return mStartHandle.isDragging();
    }

    /**
     * @return true if this controller is currently used to move the end selection.
     */
    public boolean isSelectionEndDragged() {
        return mEndHandle.isDragging();
    }

    private void updateSelectionPreview(int focusX, int focusY, float anchorX, float anchorY) {
        mLastPreviewAnchorX = anchorX;
        mLastPreviewAnchorY = anchorY;
        mLastPreviewFocusX = focusX;
        mLastPreviewFocusY = focusY;
        if (terminalView.isTextSelectionMagnifierSupported()) {
            terminalView.showTextSelectionMagnifier(anchorX, anchorY);
        } else {
            terminalView.showTextSelectionPreview(buildPreviewText(focusX, focusY), Math.round(anchorX), Math.round(anchorY));
        }
    }

    public void refreshSelectionPreview() {
        if (!mIsSelectingText) return;
        int focusX = mLastPreviewFocusX >= 0 ? mLastPreviewFocusX : mSelX2;
        int focusY = mLastPreviewFocusY != Integer.MIN_VALUE ? mLastPreviewFocusY : mSelY2;
        float anchorX = !Float.isNaN(mLastPreviewAnchorX) ? mLastPreviewAnchorX : terminalView.getPointX(focusX + 1);
        float anchorY = !Float.isNaN(mLastPreviewAnchorY) ? mLastPreviewAnchorY : terminalView.getPointY(focusY + 1);
        if (terminalView.isTextSelectionMagnifierSupported()) {
            terminalView.showTextSelectionMagnifier(anchorX, anchorY);
        } else {
            terminalView.showTextSelectionPreview(buildPreviewText(focusX, focusY), Math.round(anchorX), Math.round(anchorY));
        }
    }

    private CharSequence buildPreviewText(int focusX, int focusY) {
        if (terminalView.mEmulator == null) return " ";

        TerminalBuffer screen = terminalView.mEmulator.getScreen();
        int columns = terminalView.mEmulator.mColumns;
        int previewRadius = Math.min(12, Math.max(6, columns / 5));
        int startColumn = Math.max(0, focusX - previewRadius);
        int endColumn = Math.min(columns - 1, focusX + previewRadius);

        String segment = screen.getSelectedText(startColumn, focusY, endColumn, focusY);
        if (segment == null) segment = "";
        segment = segment.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');

        boolean clippedAtStart = startColumn > 0;
        boolean clippedAtEnd = endColumn < columns - 1;
        int[] focusCharRange = findFocusCharRange(segment, Math.max(0, focusX - startColumn));

        SpannableStringBuilder builder = new SpannableStringBuilder();
        if (clippedAtStart) builder.append("...");

        if (focusCharRange[0] >= 0 && focusCharRange[1] >= focusCharRange[0]) {
            builder.append(segment, 0, focusCharRange[0]);
            builder.append('[');
            builder.append(segment, focusCharRange[0], focusCharRange[1]);
            builder.append(']');
            builder.append(segment, focusCharRange[1], segment.length());
        } else {
            builder.append(segment);
        }

        if (clippedAtEnd) builder.append("...");

        if (builder.length() == 0) {
            builder.append(' ');
        }
        return builder;
    }

    private int[] findFocusCharRange(String text, int targetColumn) {
        int displayColumn = 0;
        for (int index = 0; index < text.length(); ) {
            int codePoint = Character.codePointAt(text, index);
            int charCount = Character.charCount(codePoint);
            int width = Math.max(1, WcWidth.width(codePoint));
            int nextDisplayColumn = displayColumn + width;

            if (targetColumn >= displayColumn && targetColumn < nextDisplayColumn) {
                return new int[]{index, index + charCount};
            }

            displayColumn = nextDisplayColumn;
            index += charCount;
        }

        return new int[]{-1, -1};
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
