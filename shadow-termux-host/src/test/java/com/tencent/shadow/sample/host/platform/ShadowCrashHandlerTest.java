package com.tencent.shadow.sample.host.platform;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ShadowCrashHandlerTest {

    @Test
    public void crashReportFlattensRuntimeCorrelationAndKeepsTheStack() throws Exception {
        JSONObject context = new JSONObject();
        context.put("operationId", "launch-123");
        context.put("pluginId", "com.termux.shadow.notes");
        context.put("generation", "16-deadbeef");
        context.put("activityClassName", "com.termux.shadow.notes.NotesActivity");
        IllegalStateException failure = new IllegalStateException("acceptance crash");

        JSONObject report = ShadowCrashHandler.buildReport(
                Thread.currentThread(),
                failure,
                context,
                1234L,
                4321
        );

        assertEquals(2, report.getInt("schemaVersion"));
        assertEquals("launch-123", report.getString("operationId"));
        assertEquals("com.termux.shadow.notes", report.getString("pluginId"));
        assertEquals("16-deadbeef", report.getString("generation"));
        assertEquals(
                "com.termux.shadow.notes.NotesActivity",
                report.getString("activityClassName")
        );
        assertEquals("java.lang.IllegalStateException", report.getString("errorType"));
        assertTrue(report.getString("stackTrace").contains("acceptance crash"));
    }

    @Test
    public void crashTextIsBoundedAndCredentialShapedValuesAreRedacted() {
        String sanitized = ShadowCrashHandler.sanitizeAndBound(
                "Authorization=secret-value Bearer abcdef123456 trailing",
                48
        );
        assertFalse(sanitized.contains("secret-value"));
        assertFalse(sanitized.contains("abcdef123456"));
        assertTrue(sanitized.length() <= 62);
    }
}
