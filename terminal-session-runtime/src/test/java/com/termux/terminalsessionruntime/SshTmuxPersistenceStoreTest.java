package com.termux.terminalsessionruntime;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SshTmuxPersistenceStoreTest {

    private static final String PREFS_NAME = "ssh_persistence_prefs";
    private static final String RECORDS_KEY = "ssh_persist.records_json";
    private static final String LEGACY_ENABLED_KEY = "ssh_persist.enabled";
    private static final String LEGACY_COMMAND_KEY = "ssh_persist.command";
    private static final String LEGACY_TMUX_KEY = "ssh_persist.tmux_session";

    private Context context;
    private SharedPreferences preferences;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    @Test
    public void missingLegacyIdIsRewrittenAndStableAcrossStoreInstances() throws Exception {
        String legacyJson = buildLegacyJson();
        preferences.edit().putString(RECORDS_KEY, legacyJson).commit();

        SshTmuxPersistenceStore firstStore =
            new SshTmuxPersistenceStore(context, new SshTmuxCommandFactory());
        ArrayList<SshPersistenceRecord> firstLoad = firstStore.load();

        Assert.assertEquals(1, firstLoad.size());
        String migratedId = firstLoad.get(0).id;
        Assert.assertFalse(migratedId.isEmpty());
        Assert.assertEquals(32, migratedId.length());

        JSONArray persisted = new JSONArray(preferences.getString(RECORDS_KEY, "[]"));
        Assert.assertEquals(1, persisted.length());
        Assert.assertEquals(migratedId, persisted.getJSONObject(0).getString("id"));

        SshTmuxPersistenceStore restartedStore =
            new SshTmuxPersistenceStore(context, new SshTmuxCommandFactory());
        ArrayList<SshPersistenceRecord> restartedLoad = restartedStore.load();

        Assert.assertEquals(1, restartedLoad.size());
        Assert.assertEquals(migratedId, restartedLoad.get(0).id);
        Assert.assertEquals(firstLoad.get(0).shellName, restartedLoad.get(0).shellName);
    }

    @Test
    public void interruptedLegacyRewriteRegeneratesTheSameId() throws Exception {
        String legacyJson = buildLegacyJson();
        preferences.edit().putString(RECORDS_KEY, legacyJson).commit();
        String firstId = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load().get(0).id;

        // Model a process restart where the canonical rewrite did not reach durable storage.
        preferences.edit().putString(RECORDS_KEY, legacyJson).commit();
        String retryId = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load().get(0).id;

        Assert.assertEquals(firstId, retryId);
    }

    @Test
    public void legacySingleRecordMigrationPersistsAStableId() throws Exception {
        preferences.edit()
            .putBoolean(LEGACY_ENABLED_KEY, true)
            .putString(LEGACY_COMMAND_KEY, "ssh user@example.com")
            .putString(LEGACY_TMUX_KEY, "work")
            .commit();

        String firstId = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load().get(0).id;
        JSONArray persisted = new JSONArray(preferences.getString(RECORDS_KEY, "[]"));

        Assert.assertEquals(firstId, persisted.getJSONObject(0).getString("id"));
        Assert.assertEquals(firstId, new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load().get(0).id);
    }

    @Test
    public void duplicateIdsAndShellNamesAreRepairedDeterministically() throws Exception {
        String damagedJson = buildDuplicateIdentityJson();
        preferences.edit().putString(RECORDS_KEY, damagedJson).commit();

        ArrayList<SshPersistenceRecord> firstLoad = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load();
        Assert.assertEquals(2, firstLoad.size());
        Assert.assertNotEquals(firstLoad.get(0).id, firstLoad.get(1).id);
        Assert.assertNotEquals(firstLoad.get(0).shellName, firstLoad.get(1).shellName);

        preferences.edit().putString(RECORDS_KEY, damagedJson).commit();
        ArrayList<SshPersistenceRecord> retriedLoad = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load();
        Assert.assertEquals(firstLoad.get(0).id, retriedLoad.get(0).id);
        Assert.assertEquals(firstLoad.get(1).id, retriedLoad.get(1).id);
        Assert.assertEquals(firstLoad.get(0).shellName, retriedLoad.get(0).shellName);
        Assert.assertEquals(firstLoad.get(1).shellName, retriedLoad.get(1).shellName);
    }

    @Test
    public void malformedIdentityNeverFallsBackToClockOrParserRandomness() throws Exception {
        SshPersistenceRecord parsed = SshPersistenceRecord.fromJson(new JSONObject()
            .put("sshCommand", "ssh user@example.com")
            .put("tmuxSession", "work"));

        Assert.assertNotNull(parsed);
        Assert.assertEquals("", parsed.id);

        SshTmuxPersistenceStore firstStore =
            new SshTmuxPersistenceStore(context, new SshTmuxCommandFactory());
        SshTmuxPersistenceStore secondStore =
            new SshTmuxPersistenceStore(context, new SshTmuxCommandFactory());
        String first = firstStore.buildShellName("---");
        String second = secondStore.buildShellName("---");

        Assert.assertEquals(first, second);
        Assert.assertTrue(first.matches("ssh-persistent-[0-9a-f]{12}"));
    }

    @Test
    public void persistedUnsafeHostKeyOptionsAreReplacedDuringMigration() throws Exception {
        JSONObject legacyRecord = new JSONObject()
            .put("id", "unsafe-legacy-record")
            .put("sshCommand", "ssh -o StrictHostKeyChecking=no -o UpdateHostKeys=yes "
                + "-o CheckHostIP=yes user@example.com")
            .put("tmuxSession", "work")
            .put("displayName", "Work")
            .put("shellName", "ssh-persistent-unsafe")
            .put("lockedHandle", JSONObject.NULL);
        preferences.edit().putString(
            RECORDS_KEY, new JSONArray().put(legacyRecord).toString()).commit();

        ArrayList<SshPersistenceRecord> records = new SshTmuxPersistenceStore(
            context, new SshTmuxCommandFactory()).load();

        Assert.assertEquals(1, records.size());
        String migrated = records.get(0).sshCommand;
        Assert.assertFalse(migrated, migrated.contains("StrictHostKeyChecking=no"));
        Assert.assertFalse(migrated, migrated.contains("UpdateHostKeys=yes"));
        Assert.assertFalse(migrated, migrated.contains("CheckHostIP=yes"));
        Assert.assertTrue(migrated, migrated.contains("StrictHostKeyChecking=yes"));
        Assert.assertTrue(migrated, migrated.contains("UpdateHostKeys=no"));
        Assert.assertTrue(migrated, migrated.contains("CheckHostIP=no"));
        Assert.assertTrue(migrated, migrated.contains("UserKnownHostsFile="));

        JSONArray persisted = new JSONArray(preferences.getString(RECORDS_KEY, "[]"));
        Assert.assertEquals(migrated, persisted.getJSONObject(0).getString("sshCommand"));
    }

    private String buildLegacyJson() throws Exception {
        JSONObject legacyRecord = new JSONObject()
            .put("sshCommand", "ssh user@example.com")
            .put("tmuxSession", "work")
            .put("displayName", "Work")
            .put("shellName", "")
            .put("lockedHandle", JSONObject.NULL);
        return new JSONArray().put(legacyRecord).toString();
    }

    private String buildDuplicateIdentityJson() throws Exception {
        JSONArray records = new JSONArray();
        records.put(new JSONObject()
            .put("id", "duplicate-id")
            .put("sshCommand", "ssh alice@example.com")
            .put("tmuxSession", "work-a")
            .put("displayName", "Work A")
            .put("shellName", "ssh-persistent-duplicate")
            .put("lockedHandle", JSONObject.NULL));
        records.put(new JSONObject()
            .put("id", "duplicate-id")
            .put("sshCommand", "ssh bob@example.com")
            .put("tmuxSession", "work-b")
            .put("displayName", "Work B")
            .put("shellName", "ssh-persistent-duplicate")
            .put("lockedHandle", JSONObject.NULL));
        return records.toString();
    }
}
