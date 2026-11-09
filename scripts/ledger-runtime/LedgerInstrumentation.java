package io.github.bytejuno.ledgeracceptance;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;

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
        require(directory.length() == 1, "expected host-only directory");
        String name = directory.keys().next();
        require("host/clock".equals(directory.getJSONObject(name).getString("toolKey")), "clock not registered");
        return name;
    }
    private String id(JSONObject record) throws Exception {
        return record.getJSONObject("input").getJSONObject("binding").getString("callId");
    }
    private JSONObject get(String id) throws Exception { return call(op("get").put("callId", id)); }
    private void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private String read(File file) throws Exception { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
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
            File directory = new File(root, "host-invocations");
            File snapshot = new File(root, "acceptance-completed.json");
            if ("execute".equals(phase)) {
                require(new File(directory, "ledger.identity").isFile(), "launcher startup ledger missing");
                String badName = freeze("bad");
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
                File disk = new File(directory, callId + ".json");
                require(disk.isFile() && read(disk).contains("SUCCEEDED"), "not persisted to noBackup");
                write(snapshot, completed.toString());
                write(new File(root, "acceptance-disk.json"), read(disk));
                result.putString("callId", callId);
            } else if ("ready-create".equals(phase)) {
                JSONObject ready = prepare("ready-restart", freeze("ready-restart"), "{}").getJSONObject("record");
                require("READY".equals(ready.getString("state")), "restart fixture not READY");
                write(new File(root, "acceptance-ready.json"), ready.toString());
                write(new File(root, "acceptance-ready-disk.json"), read(new File(directory, id(ready) + ".json")));
            } else if ("ready-restart".equals(phase)) {
                JSONObject ready = new JSONObject(read(new File(root, "acceptance-ready.json")));
                String callId = id(ready);
                require("blocked".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "missing directory must block");
                require(ready.toString().equals(get(callId).toString()), "missing directory mutated READY record");
                require(read(new File(directory, callId + ".json")).equals(read(new File(root, "acceptance-ready-disk.json"))), "missing directory rewrote disk");
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
                require(read(new File(directory, callId + ".json")).equals(read(new File(root, "acceptance-disk.json"))), "restart rewrote completed record");
                require("conflict".equals(call(op("dispatch").put("callId", callId)).getString("kind")), "completed invocation replayed");
            } else if ("storage".equals(phase)) {
                String name = freeze("storage");
                File moved = new File(root, "acceptance-ledger-held");
                require(directory.renameTo(moved), "cannot inject path failure");
                try {
                    write(directory, "not a directory");
                    require("storageFailure".equals(prepare("storage", name, "{}").getString("kind")), "lost write acknowledged");
                } finally {
                    require(directory.delete(), "cannot remove injected failure");
                    require(moved.renameTo(directory), "cannot restore ledger");
                }
                require("blocked".equals(prepare("storage", name, "{}").getString("kind")), "submissions reopened after failure");
                File marker = new File(directory, "ledger.identity");
                write(new File(root, "acceptance-marker"), read(marker));
                write(marker, "corrupt-marker");
            } else if ("startup-failure".equals(phase)) {
                boolean failed = false;
                try { freeze("broken"); } catch (java.lang.reflect.InvocationTargetException expected) { failed = true; }
                require(failed, "corrupt store accepted at startup");
                write(new File(directory, "ledger.identity"), read(new File(root, "acceptance-marker")));
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
