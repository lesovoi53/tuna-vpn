package app.tuna.securityprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs installed code only. Class names may come from this APK's R8 mapping. */
public final class AcceptanceRunner extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle parameters){super.onCreate(parameters);args=parameters==null?new Bundle():parameters;start();}
    private Class<?> installed(String argument,String fallback)throws Exception{return Class.forName(args.getString(argument,fallback),true,getTargetContext().getClassLoader());}
    private static String errorClass(Throwable failure){while(failure instanceof InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();return failure.getClass().getSimpleName();}
    private static void require(boolean condition)throws Exception{if(!condition)throw new IllegalStateException("Acceptance assertion failed");}

    /** Synthetic process only: fixed categories plus bounded redacted startup text. */
    private static final class NativeDiagnostics {
        private int lines;
        private final Set<String> categories=new LinkedHashSet<String>();
        private String telemetry="none";
        private final String secret;
        private final String[] privatePaths;
        private final List<String> startup=new ArrayList<String>();
        final AtomicBoolean selected=new AtomicBoolean();
        NativeDiagnostics(String secret,String... privatePaths){this.secret=secret;this.privatePaths=privatePaths;}
        private String redact(String line){
            String safe=line.replace(secret,"[synthetic-password]");
            for(String path:privatePaths)if(path!=null&&!path.isEmpty())safe=safe.replace(path,"[private-path]");
            safe=safe.replaceAll("tuna-dns-[0-9a-fA-F-]{36}","[bridge-socket]")
                .replaceAll("/(?:data|storage|sdcard|mnt|apex|system|vendor|linkerconfig)/[^\\s\\\"'<>]*","[android-path]")
                .replaceAll("[\\x00-\\x1f\\x7f]"," ");
            return safe.length()>512?safe.substring(0,512)+"[truncated]":safe;
        }
        synchronized void observe(String line){
            lines++;
            if(startup.size()<3&&!categories.contains("xray_started"))startup.add(redact(line));
            String trimmed=line.trim();
            if(trimmed.matches("TUNA_DNS_STATUS (remote|domestic) [0-8] (selected|fallback|probe|negative|emergency-system|emergency-resolver|emergency-system-negative|emergency-resolver-negative)")){
                telemetry=trimmed;
                if(trimmed.equals("TUNA_DNS_STATUS remote 0 emergency-system"))selected.set(true);
            }
            String lower=line.toLowerCase(Locale.ROOT);
            String[][] allowed={
                {"loaded config file","config_loaded"},{"xray started","xray_started"},
                {"full xray json rejected","config_rejected"},{"instance initialization failed","instance_rejected"},
                {"bootstrap resolver:","bootstrap_rejected"},{"failed to start xray","start_rejected"},
                {"dns pool routing tag mismatch","dns_tag_mismatch"},{"invalid system dns response","system_dns_invalid_response"},
                {"system dns bridge unavailable","system_bridge_unavailable"},{"system dns bridge connection failed","system_bridge_connect_failed"},
                {"context canceled","context_canceled"},{"deadline exceeded","deadline_exceeded"},
                {"connection refused","connection_refused"},{"permission denied","permission_denied"},
                {"address already in use","address_in_use"},{"invalid username or password","socks_auth_rejected"},
                {"no configuration provided","configuration_missing"},{"failed to read request","socks_request_failed"},
                {"failed to load config file","config_read_failed"},{"bootstrap dns requires","bootstrap_invalid"},
                {"cannot link executable","linker_failed"},{"library ","library_diagnostic"},{"-listen is required","listener_missing"},
                {"panic:","panic"},{"fatal error:","fatal_runtime_error"}
            };
            for(String[] category:allowed)if(lower.contains(category[0]))categories.add(category[1]);
        }
        synchronized void result(Bundle status){
            status.putInt("nativeOutputLines",lines);status.putString("nativeOutputCategories",String.join(",",categories));
            status.putString("nativeDnsTelemetry",telemetry);status.putBoolean("nativeEmergencySelected",selected.get());
            status.putString("nativeStartupRedacted",String.join(" | ",startup));
        }
    }

    private Object bridge()throws Exception{
        Class<?> type=installed("bridgeClass","com.wireturn.app.kernel.SystemDnsBridge");
        for(Constructor<?> constructor:type.getDeclaredConstructors())if(constructor.getParameterTypes().length==1&&constructor.getParameterTypes()[0]==Context.class){constructor.setAccessible(true);return constructor.newInstance(getTargetContext());}
        throw new NoSuchMethodException("Bridge Context constructor unavailable");
    }
    private static String socketName(Object bridge)throws Exception{
        for(Field field:bridge.getClass().getDeclaredFields())if(field.getType()==String.class){field.setAccessible(true);Object value=field.get(bridge);if(value instanceof String&&((String)value).startsWith("tuna-dns-"))return (String)value;}
        throw new NoSuchFieldException("Bridge socket unavailable");
    }
    private static LocalSocket connect(String name)throws Exception{
        LocalSocket socket=new LocalSocket();
        try{socket.connect(new LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT));socket.setSoTimeout(3000);return socket;}
        catch(Exception error){socket.close();throw error;}
    }
    private static byte[] question()throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeShort(0x6271);out.writeShort(0x0100);out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(0);
        for(String part:new String[]{"example","com"}){byte[] label=part.getBytes(StandardCharsets.US_ASCII);out.writeByte(label.length);out.write(label);}
        out.writeByte(0);out.writeShort(1);out.writeShort(1);return bytes.toByteArray();
    }
    private static boolean closedRead(LocalSocket socket)throws Exception{
        try{return socket.getInputStream().read()==-1;}
        catch(SocketTimeoutException timeout){return false;}
        catch(java.io.IOException closed){return true;}
    }
    private void bridgeChecks(Object bridge,String name,Bundle status)throws Exception{
        // Give the installed network tracker its initial framework callback.
        Thread.sleep(250);
        long start=SystemClock.elapsedRealtime();byte[] response;
        try(LocalSocket socket=connect(name)){
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());byte[] query=question();
            out.writeShort(2000);out.writeShort(query.length);out.write(query);out.flush();
            DataInputStream input=new DataInputStream(socket.getInputStream());int length=input.readUnsignedShort();require(length>=12);response=new byte[length];input.readFully(response);
        }
        long duration=SystemClock.elapsedRealtime()-start;
        require(duration<3000&&response[0]==0x62&&response[1]==0x71&&(response[2]&0x80)!=0&&(response[2]&2)==0);
        status.putBoolean("bridgeDnsWire",true);status.putLong("bridgeQueryMs",duration);
        status.putInt("bridgeRcode",response[3]&15);status.putInt("bridgeAnswerCount",((response[6]&255)<<8)|(response[7]&255));
        try(LocalSocket malformed=connect(name)){
            DataOutputStream out=new DataOutputStream(malformed.getOutputStream());out.writeShort(1000);out.writeShort(1);out.writeByte(0);out.flush();
            require(closedRead(malformed));status.putBoolean("bridgeMalformedClosed",true);
        }
        try(LocalSocket idle=connect(name)){
            start=SystemClock.elapsedRealtime();DataOutputStream out=new DataOutputStream(idle.getOutputStream());out.writeShort(150);out.flush();
            require(closedRead(idle));long elapsed=SystemClock.elapsedRealtime()-start;require(elapsed<1500);status.putLong("bridgeIdleDeadlineMs",elapsed);
        }
    }

    private void signatureCheck(Bundle status)throws Exception{
        Class<?> type=installed("updateClass","com.wireturn.app.domain.UpdateSecurity");
        Object instance=null;
        for(Field field:type.getDeclaredFields())if(Modifier.isStatic(field.getModifiers())&&field.getType()==type){field.setAccessible(true);instance=field.get(null);break;}
        Method target=null;
        for(Method method:type.getDeclaredMethods()){
            Class<?>[] parameters=method.getParameterTypes();
            if(parameters.length==3&&parameters[0]==File.class&&parameters[1]==int.class&&parameters[2]==List.class){if(target!=null)throw new IllegalStateException("Ambiguous verifier");target=method;}
        }
        if(target==null)throw new NoSuchMethodException("Installed signature verifier unavailable");
        int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
        PackageInfo info=getTargetContext().getPackageManager().getPackageInfo(getTargetContext().getPackageName(),flags);
        Signature[] signatures=Build.VERSION.SDK_INT>=28?info.signingInfo.getApkContentsSigners():info.signatures;
        List<X509Certificate> certificates=new ArrayList<X509Certificate>();CertificateFactory factory=CertificateFactory.getInstance("X.509");
        for(Signature signature:signatures)certificates.add((X509Certificate)factory.generateCertificate(new ByteArrayInputStream(signature.toByteArray())));
        target.setAccessible(true);long start=SystemClock.elapsedRealtime();
        target.invoke(Modifier.isStatic(target.getModifiers())?null:instance,new File(getTargetContext().getApplicationInfo().sourceDir),Build.VERSION.SDK_INT,certificates);
        status.putBoolean("installedApkSignatureVerified",true);status.putLong("signatureVerificationMs",SystemClock.elapsedRealtime()-start);
        if("true".equals(args.getString("negative","false"))){
            Object receiver=Modifier.isStatic(target.getModifiers())?null:instance;
            String probePath=args.getString("differentSignerApk");
            if(probePath==null)probePath=getContext().getPackageManager().getApplicationInfo("app.tuna.securityprobe",0).sourceDir;
            File probe=new File(probePath);require(probe.isFile());
            requireSignatureRejection(target,receiver,probe,certificates);
            status.putBoolean("differentSignerRejected",true);
            File original=new File(getTargetContext().getApplicationInfo().sourceDir);
            File changed=new File(getTargetContext().getNoBackupFilesDir(),"qa-corrupt-apk-"+UUID.randomUUID()+".apk");
            try{
                require(original.length()>4096&&changed.getParentFile().getUsableSpace()>original.length()+8*1024*1024);
                try(FileInputStream input=new FileInputStream(original);FileOutputStream output=new FileOutputStream(changed)){
                    byte[] buffer=new byte[65536];int count;while((count=input.read(buffer))>=0)if(count>0)output.write(buffer,0,count);
                }
                try(RandomAccessFile file=new RandomAccessFile(changed,"rw")){
                    require(file.readInt()==0x504b0304);file.seek(18);
                    long compressed=(file.readUnsignedByte()|((long)file.readUnsignedByte()<<8)|((long)file.readUnsignedByte()<<16)|((long)file.readUnsignedByte()<<24));
                    file.seek(26);int name=file.readUnsignedByte()|(file.readUnsignedByte()<<8),extra=file.readUnsignedByte()|(file.readUnsignedByte()<<8);
                    long payload=30L+name+extra;require(compressed>0&&payload+compressed<file.length());
                    long offset=payload+Math.min(16,compressed-1);file.seek(offset);int value=file.readUnsignedByte();file.seek(offset);file.writeByte(value^1);
                }
                requireSignatureRejection(target,receiver,changed,certificates);status.putBoolean("modifiedApkRejected",true);
            }finally{boolean removed=!changed.exists()||changed.delete();status.putBoolean("modifiedApkRemoved",removed);require(removed);}
        }
    }
    private static void requireSignatureRejection(Method method,Object receiver,File candidate,List<X509Certificate> certificates)throws Exception{
        try{method.invoke(receiver,candidate,Build.VERSION.SDK_INT,certificates);}
        catch(InvocationTargetException rejection){require(rejection.getCause() instanceof IllegalArgumentException);return;}
        throw new IllegalStateException("Invalid signature was accepted");
    }

    private void nativeEmergency(String bridgeSocket,Bundle status)throws Exception{
        status.putString("nativePhase","reserve_listener");
        int port;try(ServerSocket reservation=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){port=reservation.getLocalPort();}
        String user="qa",password=UUID.randomUUID().toString().replace("-","");
        JSONObject pool=new JSONObject().put("group","remote").put("mode","priority").put("timeoutMs",2000)
            .put("addresses",new JSONArray().put("tcp://127.0.0.1:1")).put("emergency","tuna-system://android");
        String poolAddress="tuna-dns://"+Base64.getUrlEncoder().withoutPadding().encodeToString(pool.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject inbound=new JSONObject().put("tag","tuna-json-in").put("listen","127.0.0.1").put("port",port).put("protocol","socks")
            .put("settings",new JSONObject().put("auth","password").put("accounts",new JSONArray().put(new JSONObject().put("user",user).put("pass",password))));
        JSONObject dns=new JSONObject().put("tag","tuna-dns-remote").put("queryStrategy","UseIPv4").put("disableCache",true).put("enableParallelQuery",false)
            .put("servers",new JSONArray().put(new JSONObject().put("address",poolAddress).put("tag","tuna-dns-remote").put("timeoutMs",2000).put("finalQuery",true)));
        JSONObject raw=new JSONObject().put("inbounds",new JSONArray().put(inbound)).put("dns",dns)
            .put("outbounds",new JSONArray().put(new JSONObject().put("tag","qa-direct").put("protocol","freedom").put("settings",new JSONObject().put("domainStrategy","UseIP"))));
        JSONObject wrapper=new JSONObject().put("listen","127.0.0.1:"+port).put("proxy_user",user).put("proxy_pass",password).put("raw_xray",raw);
        File config=new File(getTargetContext().getNoBackupFilesDir(),"qa-native-"+UUID.randomUUID()+".json");
        java.lang.Process process=null;Thread outputThread=null;
        final NativeDiagnostics diagnostics=new NativeDiagnostics(password,config.getAbsolutePath(),getTargetContext().getNoBackupFilesDir().getAbsolutePath(),getTargetContext().getApplicationInfo().nativeLibraryDir);
        long start=SystemClock.elapsedRealtime();
        try{
            status.putString("nativePhase","write_config");
            // Android JSONObject escapes '/' as '\/'; the native wrapper loader is
            // YAML, whose double-quoted scalars reject that JSON-only escape.
            // These synthetic values contain no literal backslash/slash pairs.
            String serialized=wrapper.toString().replace("\\/","/");
            try(FileOutputStream output=new FileOutputStream(config)){output.write(serialized.getBytes(StandardCharsets.UTF_8));}
            File executable=new File(getTargetContext().getApplicationInfo().nativeLibraryDir,"libxray.so");
            status.putBoolean("nativeExecutablePresent",executable.isFile());require(executable.isFile());
            ProcessBuilder builder=new ProcessBuilder(executable.getAbsolutePath(),"-config",config.getAbsolutePath()).redirectErrorStream(true);
            builder.environment().put("TUNA_BOOTSTRAP_DNS","system");builder.environment().put("TUNA_SYSTEM_DNS_SOCKET",bridgeSocket);
            builder.environment().remove("TUNA_ROUTING_FILE");builder.environment().remove("TUNA_RESOLVE_SOCKS_DESTINATION");
            status.putString("nativePhase","start_process");process=builder.start();final java.lang.Process child=process;
            outputThread=new Thread(new Runnable(){public void run(){
                try(BufferedReader input=new BufferedReader(new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8))){String line;while((line=input.readLine())!=null)diagnostics.observe(line);}
                catch(Exception ignored){}
            }},"qa-native-output");outputThread.setDaemon(true);outputThread.start();
            status.putString("nativePhase","wait_listener");
            Socket socket=null;long ready=SystemClock.elapsedRealtime()+4000;
            while(socket==null&&SystemClock.elapsedRealtime()<ready&&process.isAlive()){
                Socket candidate=new Socket();try{candidate.connect(new InetSocketAddress("127.0.0.1",port),200);socket=candidate;}catch(java.io.IOException waiting){candidate.close();Thread.sleep(60);}
            }
            status.putBoolean("nativeListenerReady",socket!=null);require(socket!=null);
            boolean accepted=false;
            try(Socket socks=socket){
                status.putString("nativePhase","socks_method");
                socks.setSoTimeout(5000);DataOutputStream out=new DataOutputStream(socks.getOutputStream());DataInputStream input=new DataInputStream(socks.getInputStream());
                out.write(new byte[]{5,1,2});out.flush();int methodVersion=input.readUnsignedByte(),method=input.readUnsignedByte();
                status.putInt("nativeSocksMethod",method);require(methodVersion==5&&method==2);
                status.putString("nativePhase","socks_auth");
                byte[] username=user.getBytes(StandardCharsets.US_ASCII),secret=password.getBytes(StandardCharsets.US_ASCII);
                out.writeByte(1);out.writeByte(username.length);out.write(username);out.writeByte(secret.length);out.write(secret);out.flush();
                int authVersion=input.readUnsignedByte(),auth=input.readUnsignedByte();status.putInt("nativeSocksAuthResult",auth);require(authVersion==1&&auth==0);
                status.putString("nativePhase","socks_connect");
                byte[] domain="example.com".getBytes(StandardCharsets.US_ASCII);out.write(new byte[]{5,1,0,3});out.writeByte(domain.length);out.write(domain);out.writeShort(443);out.flush();
                try{
                    int version=input.readUnsignedByte(),reply=input.readUnsignedByte();input.readUnsignedByte();int addressType=input.readUnsignedByte();
                    status.putInt("nativeSocksReply",reply);status.putInt("nativeSocksReplyAddressType",addressType);require(version==5);
                    int addressLength=addressType==1?4:addressType==4?16:addressType==3?input.readUnsignedByte():-1;require(addressLength>=0);
                    byte[] discarded=new byte[addressLength+2];input.readFully(discarded);accepted=reply==0;
                }catch(java.io.IOException replyFailure){status.putString("nativeSocksReplyErrorClass",errorClass(replyFailure));}
                status.putBoolean("nativeSocksConnectAccepted",accepted);
                // Xray acknowledges SOCKS before dispatch/DNS. Closing here would cancel the
                // actual query and correctly suppress emergency; hold it until telemetry ends.
                status.putString("nativePhase","wait_emergency_telemetry");
                long telemetryDeadline=start+9500;
                while(!diagnostics.selected.get()&&SystemClock.elapsedRealtime()<telemetryDeadline&&process.isAlive())Thread.sleep(20);
                require(diagnostics.selected.get()&&SystemClock.elapsedRealtime()-start<10000);
            }
            status.putString("nativePhase","complete");
        }finally{
            status.putLong("nativeEmergencyMs",SystemClock.elapsedRealtime()-start);
            if(process!=null){
                boolean alive=process.isAlive();status.putBoolean("nativeChildAliveBeforeCleanup",alive);
                if(!alive)status.putInt("nativeChildExitCode",process.exitValue());
                process.destroy();if(!process.waitFor(2,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);}
                status.putBoolean("nativeChildStopped",!process.isAlive());
            }
            if(outputThread!=null)outputThread.join(500);
            diagnostics.result(status);
            boolean removed=!config.exists()||config.delete();status.putBoolean("nativeConfigRemoved",removed);
            require(removed&&(process==null||!process.isAlive()));
        }
    }

    /** Read only the temporary timing sink; never initialize the recorder or change preferences. */
    private void startupTimings(){
        Bundle result=new Bundle();boolean passed=true;int matches=0;
        try{
            Context target=getTargetContext();
            File timings=new File(target.getNoBackupFilesDir(),"qa-startup-timings.txt");
            File credential=new File(target.createPackageContext(target.getPackageName(),0).getNoBackupFilesDir(),"qa-startup-timings.txt");
            File device=new File(target.createDeviceProtectedStorageContext().getNoBackupFilesDir(),"qa-startup-timings.txt");
            File instrumentation=new File(getContext().getNoBackupFilesDir(),"qa-startup-timings.txt");
            result.putBoolean("startupTargetPackageMatches","app.tunaturn.android.debug".equals(target.getPackageName()));
            result.putBoolean("startupTargetDeviceProtected",target.isDeviceProtectedStorage());
            result.putBoolean("startupTargetFileExists",timings.isFile());
            result.putBoolean("startupTargetFileReadable",timings.canRead());
            result.putBoolean("startupCredentialFileExists",credential.isFile());
            result.putBoolean("startupDeviceFileExists",device.isFile());
            result.putBoolean("startupInstrumentationFileExists",instrumentation.isFile());
            result.putBoolean("startupTargetEqualsCredential",timings.equals(credential));
            result.putBoolean("startupTargetEqualsDevice",timings.equals(device));
            Context application=target.getApplicationContext();
            result.putBoolean("startupApplicationContextPresent",application!=null);
            if(application!=null){
                File appFile=new File(application.getNoBackupFilesDir(),"qa-startup-timings.txt");
                result.putBoolean("startupApplicationFileExists",appFile.isFile());
                result.putBoolean("startupTargetEqualsApplication",timings.equals(appFile));
            }
            require(timings.isFile());
            try(BufferedReader input=new BufferedReader(new InputStreamReader(new FileInputStream(timings),StandardCharsets.UTF_8))){
                String line;
                while((line=input.readLine())!=null){
                    if(!line.startsWith("[DEBUG-startup]"))continue;
                    Bundle entry=new Bundle();entry.putString("stream",line+"\n");sendStatus(0,entry);matches++;
                }
            }
        }catch(Throwable failure){passed=false;result.putString("startupTimingsErrorClass",errorClass(failure));}
        result.putInt("startupTimingLines",matches);
        result.putString("result",passed?"passed":"failed");
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    /** Remove only our two named test artifacts; never delete directories or preferences. */
    private void cleanupTestFiles(){
        Bundle result=new Bundle();boolean passed=true;
        String[] names={"qa-startup-timings.txt","qa-security-continuity.json"};
        String[] keys={"startupTimingFileRemoved","securityContinuityFileRemoved"};
        for(int i=0;i<names.length;i++){
            boolean removed=false;
            try{
                File file=new File(getTargetContext().getNoBackupFilesDir(),names[i]);
                removed=!file.exists() || (file.isFile() && file.delete());
            }catch(Exception failure){result.putString(keys[i]+"ErrorClass",errorClass(failure));}
            result.putBoolean(keys[i],removed);passed&=removed;
        }
        result.putString("result",passed?"passed":"failed");
        finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    @Override public void onStart(){
        if("cleanup-test-files".equals(args.getString("phase"))){cleanupTestFiles();return;}
        if("startup-timings".equals(args.getString("phase"))){startupTimings();return;}
        Bundle status=new Bundle();boolean passed=true;Object bridge=null;String socket=null;
        try{
            bridge=bridge();socket=socketName(bridge);bridgeChecks(bridge,socket,status);
            if("true".equals(args.getString("native","false")))nativeEmergency(socket,status);
        }catch(Throwable failure){passed=false;status.putString("bridgeOrNativeErrorClass",errorClass(failure));}
        finally{
            if(bridge!=null)try{((Closeable)bridge).close();}catch(Throwable failure){passed=false;status.putString("bridgeCloseErrorClass",errorClass(failure));}
            if(socket!=null){
                boolean immediate;
                try(LocalSocket unexpected=connect(socket)){immediate=false;}catch(Exception expected){immediate=true;}
                status.putBoolean("bridgeClosedSocketImmediatelyUnavailable",immediate);
                try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();passed=false;}
                try(LocalSocket unexpected=connect(socket)){passed=false;status.putBoolean("bridgeClosedSocketUnavailable",false);}catch(Exception expected){status.putBoolean("bridgeClosedSocketUnavailable",true);}
            }
        }
        try{signatureCheck(status);}catch(Throwable failure){passed=false;status.putString("signatureErrorClass",errorClass(failure));}
        status.putString("result",passed?"passed":"failed");finish(passed?Activity.RESULT_OK:Activity.RESULT_CANCELED,status);
    }
}
