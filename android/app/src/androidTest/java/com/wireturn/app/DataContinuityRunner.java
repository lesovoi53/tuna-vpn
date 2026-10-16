package com.wireturn.app;

import android.app.Instrumentation;
import android.os.Bundle;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.File;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;

/** Test APK only. Compares data across a real release update; never exports credential contents. */
public class DataContinuityRunner extends Instrumentation {
    private String phase;
    @Override public void onCreate(Bundle args) { super.onCreate(args); phase=args.getString("phase", "compare"); start(); }
    private String hash(byte[] data) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder s=new StringBuilder();for(byte b:digest)s.append(String.format("%02x",b & 255));return s.toString();
    }
    private void collect(File dir, String prefix, JSONObject output) throws Exception {
        File[] files=dir.listFiles();if(files==null)return;
        java.util.Arrays.sort(files,java.util.Comparator.comparing(File::getName));
        for(File f:files) {
            if(f.isDirectory())collect(f,prefix+f.getName()+"/",output);
            else if(!f.getName().endsWith(".lock") && !f.getName().endsWith(".bak")) output.put(prefix+f.getName(),hash(Files.readAllBytes(f.toPath())));
        }
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            JSONObject current=new JSONObject();
            collect(new File(getTargetContext().getApplicationInfo().dataDir,"shared_prefs"),"prefs/",current);
            collect(new File(getTargetContext().getFilesDir(),"datastore"),"datastore/",current);
            File vault=new File(getTargetContext().getNoBackupFilesDir(),"server-access-v1.enc");
            int records=0;
            if(vault.exists()) {
                byte[] encrypted=Files.readAllBytes(vault.toPath());current.put("vault/encrypted",hash(encrypted));
                String[] envelope=new String(encrypted,java.nio.charset.StandardCharsets.UTF_8).split(":");
                if(envelope.length!=3 || !envelope[0].equals("1"))throw new IllegalStateException("Vault envelope");
                KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,ks.getKey("tuna-server-access-v1",null),new GCMParameterSpec(128,Base64.getDecoder().decode(envelope[1])));
                cipher.updateAAD("TUNA/server-access/1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] plain=cipher.doFinal(Base64.getDecoder().decode(envelope[2]));
                try { current.put("vault/decrypted",hash(plain));records=new JSONObject(new String(plain,java.nio.charset.StandardCharsets.UTF_8)).getJSONArray("servers").length(); }
                finally { java.util.Arrays.fill(plain,(byte)0); }
            }
            File baseline=new File(getTargetContext().getNoBackupFilesDir(),"qa-rc39-continuity.json");
            if(phase.equals("before")) {
                Files.write(baseline.toPath(),current.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result.putString("result","snapshot_saved");
            } else {
                JSONObject before=new JSONObject(new String(Files.readAllBytes(baseline.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                java.util.Set<String> keys=new java.util.TreeSet<>();before.keys().forEachRemaining(keys::add);current.keys().forEachRemaining(keys::add);
                JSONArray changed=new JSONArray();for(String key:keys)if(!before.optString(key).equals(current.optString(key)))changed.put(key);
                result.putString("result",changed.length()==0?"unchanged":"changed");result.putString("changedFiles",changed.toString());
                if(phase.equals("finish"))Files.delete(baseline.toPath());
            }
            result.putInt("checkedFiles",current.length());result.putInt("vaultRecords",records);finish(0,result);
        } catch(Throwable error) { result.putString("error",error.getClass().getSimpleName());finish(1,result); }
    }
}
