package app.tuna.securityprobe;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Installed JNI only; output contains counts/booleans, never file contents or paths. */
final class MemoryAcceptance {
    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("Memory acceptance assertion failed");
    }

    static void run(Context context, Bundle arguments, Bundle result) throws Exception {
        ConnectivityManager manager=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network before=manager.getActiveNetwork();
        boolean vpnBefore=isVpn(manager,before);
        result.putBoolean("memoryVpnBefore",vpnBefore);
        String canary="TUNA_MEMORY_ACCEPTANCE_"+UUID.randomUUID();
        byte[] expected=canary.getBytes(StandardCharsets.UTF_8);
        Class<?> type=Class.forName(arguments.getString("memoryClass","com.wireturn.app.kernel.MemoryFileDescriptor"),true,context.getClassLoader());
        Object companion=type.getField("Companion").get(null);
        Method create=companion.getClass().getMethod("create",String.class);
        Closeable memory=(Closeable)create.invoke(companion,canary);
        String path=null;
        try {
            path=(String)type.getMethod("getPath").invoke(memory);
            require(path.matches("/proc/self/fd/[0-9]+"));
            // Os.open exercises the native pathname open used by the asynchronous HEV reader.
            readNative(path,expected);
            readNative(path,expected);
            result.putBoolean("memoryNativeReopenMatches",true);
            Scan scan=new Scan(expected);
            boolean complete=false;
            try {
                scan.walk(context.getFilesDir(),"",0,0);
                scan.walk(context.getCacheDir(),"",1,0);
                scan.walk(context.getNoBackupFilesDir(),"",2,0);
                complete=true;
            } finally {
                // Retain partial evidence even when a bound or filesystem operation fails.
                result.putInt("memoryDiskFilesScanned",scan.files);
                result.putLong("memoryDiskBytesScanned",scan.bytes);
                result.putInt("memoryDiskSymlinksSkipped",scan.symlinks);
                result.putInt("memoryDiskCanaryMatches",scan.matches);
                result.putInt("memoryForbiddenRuntimeFiles",scan.forbidden);
                result.putBoolean("memoryDiskScanComplete",complete);
                result.putString("memoryDiskScanStatus",complete?"complete":
                    scan.failureReason==null?"filesystem_error":scan.failureReason);
                result.putLong("memoryDiskScanElapsedMs",scan.elapsed());
                result.putLong("memoryDiskScanByteLimit",scan.byteLimit);
                result.putLong("memoryDiskScanTimeLimitMs",scan.timeLimit);
            }
            require(scan.matches==0&&scan.forbidden==0);
        } finally {
            memory.close();
            Arrays.fill(expected,(byte)0);
        }
        require(path!=null);
        boolean closed=false;
        try {
            FileDescriptor unexpected=Os.open(path,OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
            Os.close(unexpected);
        } catch (ErrnoException missing) {
            require(missing.errno==OsConstants.ENOENT);
            closed=true;
        }
        result.putBoolean("memoryDescriptorClosed",closed);
        require(closed);
        memory.close(); // The public close contract is idempotent.
        result.putBoolean("memoryRepeatedCloseSucceeded",true);
        Network after=manager.getActiveNetwork();
        boolean vpnAfter=isVpn(manager,after);
        result.putBoolean("memoryVpnAfter",vpnAfter);
        result.putBoolean("memoryDefaultNetworkStable",before==null?after==null:before.equals(after));
        if ("true".equals(arguments.getString("requireVpn","false"))) require(vpnBefore&&vpnAfter&&before.equals(after));
    }

    private static boolean isVpn(ConnectivityManager manager,Network network) {
        NetworkCapabilities capabilities=network==null?null:manager.getNetworkCapabilities(network);
        return capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static void readNative(String path,byte[] expected) throws Exception {
        FileDescriptor descriptor=Os.open(path,OsConstants.O_RDONLY|OsConstants.O_CLOEXEC,0);
        byte[] actual=new byte[expected.length+1];
        try {
            int count=0,read;
            while(count<actual.length&&(read=Os.read(descriptor,actual,count,actual.length-count))>0) count+=read;
            require(count==expected.length);
            for(int i=0;i<count;i++) require(actual[i]==expected[i]);
        } finally {
            Arrays.fill(actual,(byte)0);
            Os.close(descriptor);
        }
    }

    static final class Scan {
        final byte[] canary;
        final int[] prefix;
        final LongSupplier clock;
        final long started,byteLimit,timeLimit;
        final int fileLimit,depthLimit;
        int files,matches,forbidden,symlinks;
        long bytes;
        String failureReason;
        Scan(byte[] canary) {
            this(canary,1024L*1024*1024,120000,20000,32,new LongSupplier() {
                @Override public long getAsLong() { return SystemClock.elapsedRealtime(); }
            });
        }
        Scan(byte[] canary,long byteLimit,long timeLimit,int fileLimit,int depthLimit,LongSupplier clock) {
            if(canary.length==0||byteLimit<0||timeLimit<0||fileLimit<0||depthLimit<0)
                throw new IllegalArgumentException("Invalid scan bounds");
            this.canary=canary;
            this.byteLimit=byteLimit;this.timeLimit=timeLimit;
            this.fileLimit=fileLimit;this.depthLimit=depthLimit;
            this.clock=clock;started=clock.getAsLong();
            prefix=new int[canary.length];
            for(int i=1,j=0;i<canary.length;i++) {
                while(j>0&&canary[i]!=canary[j]) j=prefix[j-1];
                if(canary[i]==canary[j]) j++;
                prefix[i]=j;
            }
        }
        long elapsed() { return clock.getAsLong()-started; }
        private void check(boolean condition,String reason) {
            if(!condition) {
                failureReason=reason;
                throw new IllegalStateException("Memory scan incomplete: "+reason);
            }
        }
        void walk(File file,String relative,int root,int depth) throws Exception {
            check(depth<=depthLimit,"depth_limit");
            check(elapsed()<timeLimit,"time_limit");
            if(Files.isSymbolicLink(file.toPath())) { symlinks++;return; }
            check(Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS),"entry_missing");
            if(Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                File[] children=file.listFiles();check(children!=null,"directory_unreadable");
                for(File child:children) walk(child,relative.isEmpty()?child.getName():relative+"/"+child.getName(),root,depth+1);
            } else if(Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)) {
                check(files<fileLimit,"file_limit");
                files++;
                if(forbidden(relative,root)) forbidden++;
                byte[] buffer=new byte[65536];int matched=0;
                try(FileInputStream input=new FileInputStream(file)) {
                    int read;
                    while((read=input.read(buffer))!=-1) {
                        // Exact synthetic bytes only: do not decode, parse or report stored data.
                        for(int i=0;i<read;i++) {
                            while(matched>0&&buffer[i]!=canary[matched]) matched=prefix[matched-1];
                            if(buffer[i]==canary[matched]) matched++;
                            if(matched==canary.length) { matches++;matched=prefix[matched-1]; }
                        }
                        bytes+=read;
                        check(bytes<=byteLimit,"byte_limit");
                        check(elapsed()<timeLimit,"time_limit");
                    }
                } finally { Arrays.fill(buffer,(byte)0); }
            }
        }
        private static boolean forbidden(String path,int root) {
            if(root==2&&path.startsWith("native-runtime/"))
                return path.matches(".*\\.(json|yaml|yml|toml|conf)$")||path.endsWith("/client_resolvers.txt");
            if(root==1) return path.matches("(?:core-check|route-check)-[^/]+\\.json")||
                path.matches("(?:profile-test|masterdns-scan|snell-health)-[^/]+/.*\\.(json|yaml|yml|toml|conf)$");
            if(root!=0) return false;
            return path.matches("(?:hev-socks5-tunnel\\.yaml|xray-vpn/config\\.json|mieru/(?:launch|native)\\.json|singbox/config\\.json|webdav\\.json|qwdtt\\.json|masterdns/(?:client\\.toml|client_resolvers\\.txt)|routing-policy\\.json|xray-arguments\\.json|network-router/extended(?:-[^/]+)?\\.json)");
        }
    }
}
