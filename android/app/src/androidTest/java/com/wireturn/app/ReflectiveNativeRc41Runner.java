package com.wireturn.app;

import android.app.Instrumentation;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Exact rc41 only. Imports no app, Gson, Kotlin or coroutine classes into the test ABI.
 * Reflection names are verified against rc41's shipped R8 mapping, guarded by APK SHA-256.
 * Uses the actual APK URI parser, validators and config generators, then its native check CLI.
 * No profile/settings writes, activities, VPN sessions, network downloads or server probes.
 * Only a private, uniquely named config tempfile is written and removed in finally.
 */
public final class ReflectiveNativeRc41Runner extends Instrumentation {
    private static final String APK_SHA256 = "fa71779be0c8a22e555d249a2efab72686b85ed615e09d8b6c14934cf02465f9";
    private static final String PACKAGE = "app.tunaturn.android.debug";
    private static final long VERSION_CODE = 26100801L;
    private static final String VPN_CLASS = "com.wireturn.app.data.kernel.VpnConfig";
    private static final String OPTIONS_CLASS = "com.wireturn.app.data.kernel.VpnOptions";
    private static final String FIXTURE = "vless://00000000-0000-4000-8000-000000000001@192.0.2.1:443";
    private static final int OUTPUT_LIMIT = 262144;
    private final JSONObject report = new JSONObject();
    private final JSONArray failures = new JSONArray();
    private int checks;
    private int nativeAccepted;
    private Class<?> vpnClass;
    private Constructor<?> vpnConstructor;
    private Constructor<?> optionsConstructor;
    private Constructor<?> fragmentConstructor;
    private Method parseUri;
    private Method validate;
    private Method usesXray;
    private Method uriGetter;
    private Method xrayGenerate;
    private Method singBoxGenerate;
    private Object companion;
    private File nativeDir;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        try { audit(); }
        catch (Throwable error) { recordFailure("audit_setup", error); }
        try {
            report.put("checks", checks).put("nativeAccepted", nativeAccepted).put("failures", failures)
                .put("profileSettingsWrites", false).put("serverReachabilityTested", false)
                .put("subscriptionDownloadTested", false);
        } catch (Exception ignored) { }
        Bundle output = new Bundle();
        output.putString("result", report.toString());
        finish(failures.length() == 0 ? 0 : 1, output);
    }

    private void audit() throws Exception {
        guardExactApk();
        resolveShippedEntrypoints();
        List<Fixture> fixtures = new ArrayList<>();
        for (String engine : new String[]{"xray", "sing-box"}) {
            for (String transport : new String[]{"tcp", "ws", "grpc", "httpupgrade", "xhttp"}) {
                String extra = (transport.equals("ws") || transport.equals("httpupgrade"))
                    ? "&host=fixture.invalid&path=%2Faudit"
                    : transport.equals("grpc") ? "&serviceName=audit"
                    : transport.equals("xhttp") ? "&mode=auto&path=%2Faudit" : "";
                fixtures.add(new Fixture(engine + "_" + transport,
                    FIXTURE + "?security=tls&type=" + transport + "&sni=fixture.invalid&fp=chrome" + extra, engine, null));
            }
            fixtures.add(new Fixture(engine + "_tcp_none", FIXTURE + "?security=none&type=tcp", engine, null));
        }
        for (String mode : new String[]{"tls", "tcp", "both"}) {
            fixtures.add(new Fixture("fragment_" + mode, FIXTURE + "?security=tls&type=tcp&sni=fixture.invalid", "xray", mode));
        }
        fixtures.add(new Fixture("snell6_default", "snell://fixture-psk@192.0.2.1:27305/?version=6&mode=default&reuse=true&udp-relay=true", "auto", null));
        fixtures.add(new Fixture("snell6_quic_config_only", "snell://fixture-psk@192.0.2.1:27305/?version=6&mode=default&quic-proxy-mode=true", "auto", null));
        report.put("fixtureCount", fixtures.size());
        for (Fixture fixture : fixtures) {
            verify(fixture.label, () -> {
                // The parser is invoked even when a fixture then selects an explicit engine.
                Object parsed = parseUri.invoke(companion, fixture.uri);
                require(parsed != null, "ParserRejectedFixture");
                String normalized = (String) uriGetter.invoke(parsed);
                Object profile = newProfile(normalized, fixture.engine, fixture.fragmentation);
                nativeCheck(profile);
            });
        }
        verify("escaped_snell", () -> {
            Object profile = parseUri.invoke(companion,
                "snell://fixture\\_psk\\@192.0.2.1:27305/?version=6&mode=default");
            require(profile != null, "EscapedSnellRejected");
            require(!((String) uriGetter.invoke(profile)).contains("\\"), "EscapedSnellNotNormalized");
            nativeCheck(profile);
        });
        verify("fragment_without_tls_rejected", () -> {
            Object profile = newProfile(FIXTURE + "?security=none&type=tcp", "xray", "both");
            boolean rejected = false;
            try { validate.invoke(profile); }
            catch (InvocationTargetException expected) { rejected = expected.getCause() instanceof IllegalArgumentException; }
            require(rejected, "InvalidFragmentAccepted");
        });
        report.put("parserGeneratorContract", "exact_shipped_rc41_reflection");
    }

    @SuppressWarnings("deprecation")
    private void guardExactApk() throws Exception {
        ApplicationInfo info = getTargetContext().getApplicationInfo();
        PackageInfo pkg = getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(), 0);
        long version = Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
        File apk = new File(info.sourceDir).getCanonicalFile();
        String hash = sha256(apk);
        nativeDir = new File(info.nativeLibraryDir).getCanonicalFile();
        report.put("package", getTargetContext().getPackageName()).put("versionCode", version)
            .put("versionName", pkg.versionName).put("sourceDir", apk.getAbsolutePath())
            .put("apkSha256", hash).put("nativeLibraryDir", nativeDir.getAbsolutePath())
            .put("contextPackage", getTargetContext().getApplicationContext().getPackageName());
        boolean matched = PACKAGE.equals(getTargetContext().getPackageName()) && version == VERSION_CODE && APK_SHA256.equals(hash);
        report.put("exactApkGuardPassed", matched);
        require(matched, "ExactRc41ApkRequired");
        for (String binary : new String[]{"libxray.so", "libsingbox.so"}) {
            File library = new File(nativeDir, binary).getCanonicalFile();
            require(nativeDir.equals(library.getParentFile()) && library.isFile() && library.canExecute(), "NativeBinaryUnavailable");
            report.put(binary + "Bytes", library.length());
        }
    }

    private void resolveShippedEntrypoints() throws Exception {
        ClassLoader loader = getTargetContext().getClassLoader();
        vpnClass = Class.forName(VPN_CLASS, true, loader);
        Class<?> optionsClass = Class.forName(OPTIONS_CLASS, true, loader);
        Class<?> jsonClass = optionsClass.getDeclaredField("singBox").getType();
        Class<?> fragmentClass = optionsClass.getDeclaredField("fragmentation").getType();
        vpnConstructor = vpnClass.getDeclaredConstructor(String.class, String.class, optionsClass, List.class);
        optionsConstructor = optionsClass.getDeclaredConstructor(int.class, jsonClass, jsonClass, boolean.class, fragmentClass);
        fragmentConstructor = fragmentClass.getDeclaredConstructor(String.class, String.class, String.class);
        vpnConstructor.setAccessible(true);
        optionsConstructor.setAccessible(true);
        fragmentConstructor.setAccessible(true);
        companion = vpnClass.getField("Companion").get(null);
        // rc41 mapping: VpnConfig.Companion.parse(String) -> wi5.e(String).
        parseUri = companion.getClass().getDeclaredMethod("e", String.class);
        parseUri.setAccessible(true);
        validate = vpnClass.getMethod("validate");
        usesXray = vpnClass.getMethod("getUsesXray");
        uriGetter = vpnClass.getMethod("getUri");
        // R8 static-method relocation; residual signatures verified in the exact rc41 mapping.
        Class<?>[] parameters = {vpnClass, String.class, String.class, String.class, boolean.class, String.class};
        xrayGenerate = Class.forName("ng6", true, loader).getDeclaredMethod("b", parameters);
        singBoxGenerate = Class.forName("tu5", true, loader).getDeclaredMethod("b", parameters);
        xrayGenerate.setAccessible(true);
        singBoxGenerate.setAccessible(true);
    }

    private Object newProfile(String uri, String engine, String fragmentation) throws Exception {
        Object options = null;
        if (fragmentation != null) {
            Object fragment = fragmentConstructor.newInstance(fragmentation, "3-3", "0-1");
            options = optionsConstructor.newInstance(1, null, null, false, fragment);
        }
        return vpnConstructor.newInstance(uri, engine, options, Collections.emptyList());
    }

    private void nativeCheck(Object profile) throws Exception {
        validate.invoke(profile);
        boolean xray = (Boolean) usesXray.invoke(profile);
        Object generated = (xray ? xrayGenerate : singBoxGenerate)
            .invoke(null, profile, "127.0.0.1:21089", "validation", "validation", false, "");
        byte[] config = generated.toString().getBytes(StandardCharsets.UTF_8);
        require(config.length > 0 && config.length <= 1024 * 1024, "GeneratedConfigSizeInvalid");
        File input = File.createTempFile("qa-rc41-native-", ".json", getTargetContext().getCacheDir());
        Process process = null;
        Thread drainer = null;
        try {
            try (FileOutputStream file = new FileOutputStream(input)) { file.write(config); }
            String binary = new File(nativeDir, xray ? "libxray.so" : "libsingbox.so").getAbsolutePath();
            ProcessBuilder builder = xray
                ? new ProcessBuilder(binary, "-config", input.getAbsolutePath(), "-check-config")
                : new ProcessBuilder(binary, "check", "-c", input.getAbsolutePath());
            builder.redirectErrorStream(true);
            builder.environment().put("GODEBUG", "netdns=cgo");
            builder.environment().remove("TUNA_ROUTING_FILE");
            process = builder.start();
            final Process child = process;
            AtomicLong outputBytes = new AtomicLong();
            AtomicBoolean outputOverflow = new AtomicBoolean();
            // Drain to a bounded counter only: native error output can contain private config data.
            drainer = new Thread(() -> {
                try (InputStream stream = child.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = stream.read(buffer)) >= 0) {
                        if (outputBytes.addAndGet(count) > OUTPUT_LIMIT) {
                            outputOverflow.set(true);
                            child.destroy();
                            break;
                        }
                    }
                } catch (IOException ignored) { }
            }, "rc41-native-output");
            drainer.setDaemon(true);
            drainer.start();
            require(process.waitFor(20, TimeUnit.SECONDS), "NativeCheckTimeout");
            drainer.join(1000);
            require(!outputOverflow.get(), "NativeOutputLimitExceeded");
            require(process.exitValue() == 0, "NativeConfigRejected");
            nativeAccepted++;
        } finally {
            java.util.Arrays.fill(config, (byte) 0);
            try {
                if (process != null && process.isAlive()) {
                    process.destroy();
                    if (!process.waitFor(1, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor(1, TimeUnit.SECONDS);
                    }
                }
                if (drainer != null) drainer.join(1000);
            } finally {
                if (input.exists() && !input.delete()) throw new IOException("AuditTemporaryCleanupFailed");
            }
        }
    }

    private void verify(String label, Check check) {
        checks++;
        try { check.run(); }
        catch (Throwable failure) { recordFailure(label, failure); }
    }

    private void recordFailure(String label, Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        try {
            JSONObject item = new JSONObject().put("case", label).put("errorClass", error.getClass().getSimpleName());
            if (error instanceof AuditFailure) item.put("reason", ((AuditFailure) error).fixedReason);
            failures.put(item);
        } catch (Exception ignored) { }
        // Do not log messages, causes, stack traces, URIs or generated configurations.
    }

    private static void require(boolean condition, String fixedReason) {
        if (!condition) throw new AuditFailure(fixedReason);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream stream = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = stream.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hex.toString();
    }

    private interface Check { void run() throws Exception; }
    private static final class AuditFailure extends RuntimeException {
        final String fixedReason;
        AuditFailure(String fixedReason) { this.fixedReason = fixedReason; }
    }
    private static final class Fixture {
        final String label;
        final String uri;
        final String engine;
        final String fragmentation;
        Fixture(String label, String uri, String engine, String fragmentation) {
            this.label = label; this.uri = uri; this.engine = engine; this.fragmentation = fragmentation;
        }
    }
}
