package com.tencent.shadow.sample.host.platform;

import android.os.Process;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class ShadowOperationJournal {

    private static final long MAX_JOURNAL_BYTES = 16L * 1024L * 1024L;

    private final File file;

    ShadowOperationJournal(File file) {
        this.file = file;
    }

    synchronized void append(
            String operationId,
            String operation,
            String phase,
            String pluginId,
            String generation,
            String detail
    ) throws Exception {
        rotateIfNeeded();
        JSONObject object = new JSONObject();
        object.put("schemaVersion", 1);
        object.put("epochMs", System.currentTimeMillis());
        object.put("elapsedMs", SystemClock.elapsedRealtime());
        object.put("pid", Process.myPid());
        object.put("operationId", operationId);
        object.put("operation", operation);
        object.put("phase", phase);
        putIfPresent(object, "pluginId", pluginId);
        putIfPresent(object, "generation", generation);
        putIfPresent(object, "detail", detail);
        byte[] bytes = (object.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file, true);
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }

    private void rotateIfNeeded() throws IOException {
        if (!file.isFile() || file.length() < MAX_JOURNAL_BYTES) {
            return;
        }
        File archive = new File(file.getParentFile(), file.getName() + ".previous");
        if (archive.exists() && !archive.delete()) {
            throw new IOException("Failed to remove old Shadow journal archive");
        }
        if (!file.renameTo(archive)) {
            throw new IOException("Failed to rotate Shadow operation journal");
        }
    }

    private static void putIfPresent(JSONObject object, String key, String value) throws Exception {
        if (value != null && value.length() > 0) {
            object.put(key, value);
        }
    }
}
