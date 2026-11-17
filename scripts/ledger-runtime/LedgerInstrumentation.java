package io.github.bytejuno.ledgeracceptance;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.ByteArrayOutputStream;

/** Test APK only. Loads the installed, unchanged R8 application's explicit runtime contract. */
public final class LedgerInstrumentation extends Instrumentation {
    private Bundle args;
    private Object application;
    private Method request;
    private File root;
    private final java.util.concurrent.CountDownLatch applicationCreated = new java.util.concurrent.CountDownLatch(1);
    @Override public void callApplicationOnCreate(android.app.Application app) {
        super.callApplicationOnCreate(app);
        applicationCreated.countDown();
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); args = arguments; start(); }
    private void require(boolean condition, String reason) { if (!condition) throw new AssertionError(reason); }
    private JSONObject call(JSONObject value) throws Exception {
        return new JSONObject((String) request.invoke(application, value.toString()));
    }
    private JSONObject op(String name) throws Exception { return new JSONObject().put("operation", name); }
    private JSONObject prepare(String run, String name, String json) throws Exception {
        return call(op("prepare").put("runId", run).put("name", name).put("requestId", run)
            .put("callId", java.util.UUID.nameUUIDFromBytes(run.getBytes(StandardCharsets.UTF_8)).toString())
            .put("taskChainId", run).put("sessionId", "acceptance").put("stepId", "one")
            .put("order", 0).put("argsJson", json).put("unattended", true));
    }
    private String freeze(String run) throws Exception {
        JSONObject directory = call(op("freeze").put("runId", run));
        String clock = null;
        java.util.Set<String> keys = new java.util.HashSet<>();
        java.util.Iterator<String> names = directory.keys();
        while (names.hasNext()) {
            String name = names.next();
            String key = directory.getJSONObject(name).getString("toolKey");
            require(keys.add(key), "duplicate tool registration");
            if ("host/clock".equals(key)) {
                require(clock == null, "ambiguous clock registration");
                clock = name;
            }
        }
        require(keys.equals(new java.util.HashSet<>(java.util.Arrays.asList(
            "host/clock", "host/blob_read", "host/screen_info"))), "unexpected host directory");
        require(clock != null, "clock not registered");
        return clock;
    }
    private String id(JSONObject record) throws Exception {
        return record.getJSONObject("input").getJSONObject("binding").getString("callId");
    }
    private JSONObject get(String id) throws Exception { return call(op("get").put("callId", id)); }
    private void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private SQLiteDatabase database(boolean writable) {
        File file = new File(root, "agent.db");
        require(file.isFile(), "no-backup agent database missing");
        return SQLiteDatabase.openDatabase(file.getPath(), null,
            writable ? SQLiteDatabase.OPEN_READWRITE : SQLiteDatabase.OPEN_READONLY);
    }
    // Compare committed logical rows, never the mutable SQLite/WAL file bytes.
    private String persisted(String callId) throws Exception {
        try (SQLiteDatabase db = database(false)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (Cursor chunks = db.rawQuery("SELECT bytes FROM record_chunks WHERE call_id=? ORDER BY ordinal", new String[]{callId})) {
                while (chunks.moveToNext()) bytes.write(chunks.getBlob(0));
            }
            byte[] payload = bytes.toByteArray();
            JSONObject stored = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            require(callId.equals(id(stored)), "persisted call binding mismatch");
            require(get(callId).getString("state").equals(stored.getString("state")), "persisted state differs from runtime");
            try (Cursor record = db.rawQuery("SELECT revision,size,hash FROM invocations WHERE call_id=?", new String[]{callId})) {
                require(record.moveToFirst(), "persisted invocation missing");
                require(record.getLong(1) == payload.length && payload.length > 0, "persisted size mismatch");
                StringBuilder hash = new StringBuilder();
                for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(payload))
                    hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
                require(hash.toString().equals(record.getString(2)), "persisted checksum mismatch");
                String snapshot = record.getLong(0) + ":" + record.getString(2) + ":" +
                    android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP);
                require(!record.moveToNext(), "duplicate invocation identity");
                return snapshot;
            }
        }
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String phase = args.getString("phase");
        try {
            require(applicationCreated.await(30, java.util.concurrent.TimeUnit.SECONDS), "Application.onCreate did not complete");
            application = getTargetContext().getApplicationContext();
            // Class name comes from the same build's mapping; never bundle host classes in this APK.
            Class<?> mapped = Class.forName(args.getString("applicationClass"), true, application.getClass().getClassLoader());
            require(mapped.isInstance(application), "mapping/APK application mismatch");
            request = mapped.getMethod("hostToolRequestForInstrumentation", String.class);
            root = getTargetContext().getNoBackupFilesDir();
            File snapshot = new File(root, "acceptance-completed.json");
            if ("execute".equals(phase)) {
                String badName = freeze("bad");
                require(new File(root, "agent.db").isFile(), "launcher startup database missing");
                require(new File(root, "agent.db.writer.lock").isFile(), "database owner missing");
                JSONObject bad = prepare("bad", badName, "{\"extra\":1}").getJSONObject("record");
                require("REJECTED".equals(bad.getString("state")) && bad.isNull("instance"), "schema must reject before execution");
                JSONObject unknown = prepare("unknown", freeze("unknown") + "_missing", "{}").getJSONObject("record");
                require("REJECTED".equals(unknown.getString("state")), "unknown tool accepted");
                JSONObject good = prepare("good", freeze("good"), "{}").getJSONObject("record");
                String callId = id(good);
                require("READY".equals(good.getString("state")), "read call not ready");
                JSONObject retried = prepare("good", freeze("good"), "{}");
                require("alreadyApplied".equals(retried.getString("kind")) && callId.equals(id(retried.getJSONObject("record"))), "prepare retry lost binding");
                require("conflict".equals(prepare("good", freeze("good"), "{\"extra\":1}").getString("kind")), "mismatched retry accepted");
                require("conflict".equals(call(op("decide").put("callId", callId).put("approvalId", "forged").put("decision", "ALLOW_ALWAYS")).getString("kind")), "forged approval accepted");
                require("applied".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "dispatch failed");
                long deadline = System.nanoTime() + 15_000_000_000L;
                JSONObject completed;
                while (true) {
                    completed = get(callId);
                    JSONObject instance = completed.optJSONObject("instance");
                    if (instance != null && "EXIT_CONFIRMED".equals(instance.optString("state"))) break;
                    require(System.nanoTime() < deadline, "execution did not exit");
                    Thread.sleep(20);
                }
                require("SUCCEEDED".equals(completed.getString("state")), "clock failed: " + completed);
                JSONObject value = completed.getJSONObject("result");
                require("NONE".equals(value.getString("effect")), "clock effect");
                require(value.getString("text").contains("epochMillis"), "structured output missing");
                write(snapshot, completed.toString());
                write(new File(root, "acceptance-disk.json"), persisted(callId));
                result.putString("callId", callId);
            } else if ("ready-create".equals(phase)) {
                JSONObject ready = prepare("ready-restart", freeze("ready-restart"), "{}").getJSONObject("record");
                require("READY".equals(ready.getString("state")), "restart fixture not READY");
                write(new File(root, "acceptance-ready.json"), ready.toString());
                write(new File(root, "acceptance-ready-disk.json"), persisted(id(ready)));
            } else if ("ready-restart".equals(phase)) {
                JSONObject ready = new JSONObject(read(new File(root, "acceptance-ready.json")));
                String callId = id(ready);
                require("blocked".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "missing directory must block");
                require(ready.toString().equals(get(callId).toString()), "missing directory mutated READY record");
                require(persisted(callId).equals(read(new File(root, "acceptance-ready-disk.json"))), "missing directory rewrote committed record");
                freeze("ready-restart"); // explicit trusted freeze, not automatic recovery/alias reconstruction
                require("applied".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "explicit freeze did not unblock valid READY");
                long deadline = System.nanoTime() + 15_000_000_000L;
                while (true) {
                    JSONObject done = get(callId);
                    JSONObject instance = done.optJSONObject("instance");
                    if (instance != null && "EXIT_CONFIRMED".equals(instance.optString("state"))) {
                        require("SUCCEEDED".equals(done.getString("state")), "resumed READY failed");
                        break;
                    }
                    require(System.nanoTime() < deadline, "resumed READY did not exit");
                    Thread.sleep(20);
                }
            } else if ("recover".equals(phase)) {
                JSONObject old = new JSONObject(read(snapshot));
                String callId = id(old);
                JSONObject recovered = get(callId);
                require(recovered.toString().equals(old.toString()), "restart changed terminal record");
                require(persisted(callId).equals(read(new File(root, "acceptance-disk.json"))), "restart rewrote completed record");
                require("conflict".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "completed invocation replayed");
            } else if ("storage".equals(phase)) {
                String name = freeze("storage");
                // Test APK only: force an actual SQLite write rejection without moving live WAL files.
                try (SQLiteDatabase db = database(true)) {
                    db.execSQL("CREATE TRIGGER acceptance_reject_write BEFORE INSERT ON invocations " +
                        "BEGIN SELECT RAISE(ABORT, 'acceptance injected storage failure'); END");
                }
                try {
                    require("storageFailure".equals(prepare("storage", name, "{}").getString("kind")), "lost write acknowledged");
                } finally {
                    try (SQLiteDatabase db = database(true)) { db.execSQL("DROP TRIGGER acceptance_reject_write"); }
                }
                require("blocked".equals(prepare("storage", name, "{}").getString("kind")), "submissions reopened after failure");
                // Corrupt a logical checksum for the next cold open. Do not open/close the
                // lifetime lock inode: on POSIX even another descriptor can release its lock.
                String corruptId = java.util.UUID.nameUUIDFromBytes("bad".getBytes(StandardCharsets.UTF_8)).toString();
                try (SQLiteDatabase db = database(true)) {
                    try (Cursor row = db.rawQuery("SELECT hash FROM invocations WHERE call_id=?", new String[]{corruptId})) {
                        require(row.moveToFirst(), "startup failure fixture missing");
                        write(new File(root, "acceptance-corruption.json"), new JSONObject()
                            .put("callId", corruptId).put("hash", row.getString(0)).toString());
                    }
                    db.execSQL("UPDATE invocations SET hash='corrupt-checksum' WHERE call_id=?", new Object[]{corruptId});
                }
            } else if ("startup-failure".equals(phase)) {
                boolean failed = false;
                try { freeze("broken"); } catch (java.lang.reflect.InvocationTargetException expected) { failed = true; }
                require(failed, "corrupt store accepted at startup");
                JSONObject corruption = new JSONObject(read(new File(root, "acceptance-corruption.json")));
                try (SQLiteDatabase db = database(true)) {
                    db.execSQL("UPDATE invocations SET hash=? WHERE call_id=?",
                        new Object[]{corruption.getString("hash"), corruption.getString("callId")});
                }
                failed = false;
                try { freeze("still-broken"); } catch (java.lang.reflect.InvocationTargetException expected) { failed = true; }
                require(failed, "startup failure was not sticky");
            } else throw new AssertionError("Unknown phase " + phase);
            result.putString("ledger", "PASS " + phase);
            finish(-1, result);
        } catch (Throwable failure) {
            result.putString("ledger", "FAIL " + phase + " " + android.util.Log.getStackTraceString(failure));
            finish(0, result);
        }
    }
}
