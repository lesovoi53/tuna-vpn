package app.tuna.securityprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.Key;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.TreeSet;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same-signer instrumentation only; writes one hashes-only QA baseline in noBackup. */
public final class DataContinuityRunner extends Instrumentation {
    private String phase;
    private boolean requireEncrypted;
    private static final String TARGET = "app.tunaturn.android.debug";
    private static final String BASELINE = "qa-security-continuity.json";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        phase = args == null ? "compare" : args.getString("phase", "compare");
        requireEncrypted = args == null || !"false".equals(args.getString("requireEncrypted", "true"));
        start();
    }

    private static byte[] readStable(File file) throws Exception {
        if (!file.isFile() || file.length() > 32 * 1024 * 1024) throw new IllegalStateException("Missing or oversized file");
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] first = Files.readAllBytes(file.toPath());
            byte[] second = Files.readAllBytes(file.toPath());
            boolean stable = Arrays.equals(first, second);
            Arrays.fill(second, (byte) 0);
            if (stable) return first;
            Arrays.fill(first, (byte) 0);
            Thread.sleep(25);
        }
        throw new IllegalStateException("Concurrent settings update");
    }

    private static Key key(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        Key value = store.getKey(alias, null);
        if (value == null) throw new IllegalStateException("Existing key unavailable");
        return value;
    }

    private static byte[] openPreference(byte[] envelope, byte[] aad) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key("tuna.preferences.aes.v1"), new GCMParameterSpec(128, Arrays.copyOfRange(envelope, 0, 12)));
        cipher.updateAAD(aad);
        return cipher.doFinal(envelope, 12, envelope.length - 12);
    }

    private static String id(String name) throws Exception { return PreferencesSnapshot.hash(name.getBytes(StandardCharsets.UTF_8)); }

    private JSONObject snapshot() throws Exception {
        if (!TARGET.equals(getTargetContext().getPackageName())) throw new IllegalStateException("Wrong target");
        JSONObject result = new JSONObject();
        JSONObject entries = new JSONObject();
        int preferenceFiles = 0, preferenceEntries = 0, encryptedFiles = 0;
        boolean appPreferencesFound = false, appPreferencesEncrypted = false;
        File[] files = new File(getTargetContext().getFilesDir(), "datastore").listFiles();
        if (files == null) throw new IllegalStateException("Datastore directory unavailable");
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".preferences_pb")) continue;
            byte[] data = readStable(file);
            PreferencesSnapshot.Result preferences;
            try { preferences = PreferencesSnapshot.read(data, "datastore/" + file.getName(), new PreferencesSnapshot.Decryptor() {
                @Override public byte[] decrypt(byte[] envelope, byte[] aad) throws Exception { return openPreference(envelope, aad); }
            }); } finally { Arrays.fill(data, (byte) 0); }
            for (java.util.Map.Entry<String, String> entry : preferences.hashes.entrySet()) entries.put(entry.getKey(), entry.getValue());
            preferenceFiles++;
            preferenceEntries += preferences.entries;
            if (preferences.encrypted) encryptedFiles++;
            if (file.getName().equals("app_prefs.preferences_pb")) { appPreferencesFound = true; appPreferencesEncrypted = preferences.encrypted; }
        }
        if (!appPreferencesFound || preferenceEntries == 0) throw new IllegalStateException("Expected populated app preferences");
        int sharedFiles = 0;
        File[] shared = new File(getTargetContext().getApplicationInfo().dataDir, "shared_prefs").listFiles();
        if (shared != null) for (File file : shared) if (file.isFile() && file.getName().endsWith(".xml")) {
            byte[] data = readStable(file);
            try { entries.put(id("shared_prefs/" + file.getName()), PreferencesSnapshot.hash(data)); }
            finally { Arrays.fill(data, (byte) 0); }
            sharedFiles++;
        }
        File vault = new File(getTargetContext().getNoBackupFilesDir(), "server-access-v1.enc");
        int records = 0;
        if (vault.exists()) {
            byte[] encrypted = readStable(vault);
            try {
                entries.put(id("vault/encrypted"), PreferencesSnapshot.hash(encrypted));
                String[] envelope = new String(encrypted, StandardCharsets.UTF_8).split(":", -1);
                if (envelope.length != 3 || !envelope[0].equals("1")) throw new IllegalStateException("Unknown vault envelope");
                byte[] iv = Base64.getDecoder().decode(envelope[1]);
                if (iv.length != 12) throw new IllegalStateException("Invalid vault IV");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key("tuna-server-access-v1"), new GCMParameterSpec(128, iv));
                cipher.updateAAD("TUNA/server-access/1".getBytes(StandardCharsets.UTF_8));
                byte[] plain = cipher.doFinal(Base64.getDecoder().decode(envelope[2]));
                try {
                    entries.put(id("vault/decrypted"), PreferencesSnapshot.hash(plain));
                    records = new JSONObject(new String(plain, StandardCharsets.UTF_8)).getJSONArray("servers").length();
                } finally { Arrays.fill(plain, (byte) 0); }
            } finally { Arrays.fill(encrypted, (byte) 0); }
        }
        result.put("schema", 1);
        result.put("uid", Process.myUid());
        result.put("entries", entries);
        result.put("preferenceFiles", preferenceFiles);
        result.put("preferenceEntries", preferenceEntries);
        result.put("sharedPreferenceFiles", sharedFiles);
        result.put("encryptedPreferenceFiles", encryptedFiles);
        result.put("appPreferencesEncrypted", appPreferencesEncrypted);
        result.put("vaultPresent", vault.exists());
        result.put("vaultRecords", records);
        return result;
    }

    @Override public void onStart() {
        Bundle status = new Bundle();
        try {
            if (!phase.equals("before") && !phase.equals("compare")) throw new IllegalArgumentException("Unknown phase");
            JSONObject current = snapshot();
            File baseline = new File(getTargetContext().getNoBackupFilesDir(), BASELINE);
            if (phase.equals("before")) {
                if (baseline.exists()) throw new IllegalStateException("Baseline already exists");
                File temporary = new File(getTargetContext().getNoBackupFilesDir(), BASELINE + ".tmp");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(current.toString().getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
                if (!temporary.renameTo(baseline)) throw new IllegalStateException("Baseline commit failed");
                status.putString("result", "snapshot_saved");
            } else {
                JSONObject previous = new JSONObject(new String(readStable(baseline), StandardCharsets.UTF_8));
                if (previous.getInt("schema") != 1) throw new IllegalStateException("Unknown baseline schema");
                JSONObject before = previous.getJSONObject("entries"), after = current.getJSONObject("entries");
                TreeSet<String> keys = new TreeSet<String>();
                for (Iterator<String> it = before.keys(); it.hasNext();) keys.add(it.next());
                for (Iterator<String> it = after.keys(); it.hasNext();) keys.add(it.next());
                int changed = 0;
                JSONArray ids = new JSONArray();
                for (String key : keys) if (!before.optString(key, "missing").equals(after.optString(key, "missing"))) {
                    changed++;
                    if (ids.length() < 20) ids.put(key);
                }
                boolean sameUid = previous.getInt("uid") == current.getInt("uid");
                boolean sameVault = previous.getBoolean("vaultPresent") == current.getBoolean("vaultPresent") && previous.getInt("vaultRecords") == current.getInt("vaultRecords");
                boolean migrated = current.getBoolean("appPreferencesEncrypted");
                boolean passed = changed == 0 && sameUid && sameVault && (!requireEncrypted || migrated);
                status.putString("result", passed ? "unchanged" : "changed_or_migration_missing");
                status.putBoolean("uidPreserved", sameUid);
                status.putBoolean("vaultPreserved", sameVault);
                status.putInt("changedEntries", changed);
                status.putString("changedEntryIds", ids.toString());
                if (!passed) { addCounts(status, current); finish(Activity.RESULT_CANCELED, status); return; }
            }
            addCounts(status, current);
            finish(Activity.RESULT_OK, status);
        } catch (Throwable failure) {
            // Exception text/stack can contain a preference key or data. Emit class only.
            status.putString("result", "error");
            status.putString("errorClass", failure.getClass().getSimpleName());
            finish(Activity.RESULT_CANCELED, status);
        }
    }

    private static void addCounts(Bundle result, JSONObject current) throws Exception {
        result.putInt("uid", current.getInt("uid"));
        result.putInt("checkedEntries", current.getJSONObject("entries").length());
        result.putInt("preferenceEntries", current.getInt("preferenceEntries"));
        result.putInt("preferenceFiles", current.getInt("preferenceFiles"));
        result.putInt("encryptedPreferenceFiles", current.getInt("encryptedPreferenceFiles"));
        result.putBoolean("appPreferencesEncrypted", current.getBoolean("appPreferencesEncrypted"));
        result.putBoolean("vaultPresent", current.getBoolean("vaultPresent"));
        result.putInt("vaultRecords", current.getInt("vaultRecords"));
    }
}
