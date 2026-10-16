package com.wireturn.app;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Drives the release UI and checks durable storage, not a mocked parser callback. Synthetic keys only. */
public class ClipboardImportRunner extends Instrumentation {
    private String kind;
    @Override public void onCreate(Bundle args) { super.onCreate(args); kind=args.getString("kind", "wg"); start(); }
    private void launch(String activity) throws Exception {
        try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand("am start -n "+getTargetContext().getPackageName()+"/com.wireturn.app.ui.activities.MainActivity");
            java.io.FileInputStream in=new java.io.FileInputStream(p.getFileDescriptor())) { while(in.read()!=-1) {} }
        Thread.sleep(800);
        if(activity.endsWith("AddProfileActivity")) {
            // Coordinates verified on the authorized 2136x3200 tablet; use the same entry as a user.
            try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand("input tap 1068 3110");
                java.io.FileInputStream in=new java.io.FileInputStream(p.getFileDescriptor())) { while(in.read()!=-1) {} }
            Thread.sleep(600);
            try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand("input tap 1730 168");
                java.io.FileInputStream in=new java.io.FileInputStream(p.getFileDescriptor())) { while(in.read()!=-1) {} }
        }
        Thread.sleep(1200);
    }
    private boolean persisted(String name) throws Exception {
        File[] files=new File(getTargetContext().getFilesDir(), "datastore").listFiles();
        if(files!=null) for(File f:files) if(f.isFile() && !f.getName().endsWith(".tmp"))
            if(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).contains(name)) return true;
        return false;
    }
    @Override public void onStart() {
        Bundle result=new Bundle(); ClipData previous=null; ClipboardManager clipboard=null; boolean captured=false; String phase="precheck";
        try {
            String name="QA rc45 " + kind.toUpperCase() + " Clipboard";
            if(persisted(name)) throw new IllegalStateException("QA fixture already exists; remove it explicitly before rerun");
            phase="launch"; launch("com.wireturn.app.ui.activities.AddProfileActivity");
            clipboard=(ClipboardManager)getTargetContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            phase="clipboard"; previous=clipboard.getPrimaryClip(); captured=true;
            String extra="";
            if(kind.startsWith("awg")) {
                extra="Jc = 3\nJmin = 52\nJmax = 105\nS1 = 27\nS2 = 143\nS3 = 52\nS4 = 13\nH1 = 140586138\nH2 = 601279878\nH3 = 1218208364\nH4 = 1664961555\n";
                for(int i=1;i<=5;i++) extra+="I"+i+" = \\<b 0xc1000000010807ef716e6037c99011a0fa04816183991c895425322ab151accf00407500007580a4f9807a477fb1541b8463745012fe13>\\<r 29>\\<b 0xebae401e0ca40315c5915037102bd21f8639f70d>\\<r 32>\\<r 16>\n";
                extra+="HeaderProtectionKey = AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\nContentPaddingAddition = 28-123\nRekeyAfterTime = 108-142\nRekeyTimeout = 2-5\nRejectAfterTime = 172-192\nKeepaliveTimeout = 6-18\nMaxHandshakeAttempts = 15-25\nRandomTrailers = on\nDisableCookies = on\n";
            }
            byte[] specialKeyBytes=new byte[32]; java.util.Arrays.fill(specialKeyBytes,(byte)251);
            String specialKey=java.util.Base64.getEncoder().encodeToString(specialKeyBytes);
            String endpoint=kind.endsWith("domain")?"fixture.example:29386":"192.0.2.1:29386";
            String ini="[Interface]\nPrivateKey = "+specialKey+"\nAddress = 10.8.1.3/32\nDNS = 1.1.1.1, 1.0.0.1\nMTU = 1420\n"+extra.replace("AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=",specialKey)+"**"+name+"**\n[Peer]\nPublicKey = "+specialKey+"\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = "+endpoint+"\n";
            clipboard.setPrimaryClip(ClipData.newPlainText("Synthetic import QA",ini));
            phase="paste-action"; AccessibilityNodeInfo root=null;
            for(int i=0;i<20;i++) { root=getUiAutomation().getRootInActiveWindow(); if(root!=null && !root.findAccessibilityNodeInfosByText("Вставить ссылку").isEmpty()) break; Thread.sleep(250); }
            boolean clicked=false;
            for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Вставить ссылку")) {
                while(node!=null && !node.isClickable()) node=node.getParent();
                if(node!=null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { clicked=true; break; }
            }
            // This Compose version exposes text nodes but not ACTION_CLICK via this API.
            // Physical tap position verified from the captured Add profile screen on this tablet.
            if(!clicked && getTargetContext().getPackageName().contentEquals(root.getPackageName())) {
                try(android.os.ParcelFileDescriptor p=getUiAutomation().executeShellCommand("input tap 650 870");
                    java.io.FileInputStream in=new java.io.FileInputStream(p.getFileDescriptor())) { while(in.read()!=-1) {} }
                clicked=true;
            }
            if(!clicked) {
                result.putString("rootPackage",root==null?"null":String.valueOf(root.getPackageName()));
                try(java.io.FileOutputStream out=new java.io.FileOutputStream(new File(getTargetContext().getExternalCacheDir(),"rc45-clip-ui.png"))) {
                    getUiAutomation().takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);
                }
                throw new IllegalStateException("Paste UI action not found");
            }
            phase="durability"; boolean saved=false;
            for(int i=0;i<40;i++) { Thread.sleep(200); if(persisted(name)) { saved=true; break; } }
            result.putString("kind",kind); result.putBoolean("durablyImported",saved);
            result.putString("result",saved?"PASS":"FAIL: paste action did not persist profile");
        } catch(Throwable e) { result.putString("error",e.getClass().getSimpleName()); result.putString("phase",phase); }
        finally {
            try {
                launch("com.wireturn.app.ui.activities.MainActivity");
                if(captured && clipboard!=null) { if(previous==null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(previous); }
            } catch(Exception ignored) { result.putBoolean("clipboardRestoreFailed",true); }
        }
        finish(result.getBoolean("durablyImported")?0:1,result);
    }
}
