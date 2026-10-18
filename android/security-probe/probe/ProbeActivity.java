package app.tuna.securityprobe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.DnsResolver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.LinkProperties;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ordinary application: no shared UID, target signature, instrumentation or privileged API. */
public final class ProbeActivity extends Activity {
    private static final String TARGET = "app.tunaturn.android.debug";
    private static final String RECEIVER = "com.wireturn.app.CoreReceiver";
    private static final String TAG = "TUNA_SECURITY_PROBE";
    private TextView output;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private CancellationSignal dnsCancellation;
    private int dnsGeneration;
    private volatile HttpsURLConnection httpsConnection;
    private Future<?> httpsTask;
    private final ThreadPoolExecutor httpsExecutor = new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new SynchronousQueue<Runnable>(),new ThreadFactory(){public Thread newThread(Runnable action){Thread thread=new Thread(action,"probe-https");thread.setDaemon(true);return thread;}});
    private String runId;
    private final Executor mainExecutor = new Executor() { @Override public void execute(Runnable task) { handler.post(task); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding,padding,padding,padding);
        TextView description = new TextView(this);
        description.setText("TUNA Security Probe\nSeparate UID and signer. Broadcast submission does not prove delivery. Compare TUNA state and system logs before/after. DNS checks query only example.com and report counts, not addresses.");
        layout.addView(description);
        addButton(layout,"Inspect installed TUNA",new Runnable(){public void run(){begin("inspect");inspect();}});
        addButton(layout,"Attempt 10 control broadcasts",new Runnable(){public void run(){broadcasts();}});
        addButton(layout,"Check DNS: example.com A / AAAA",new Runnable(){public void run(){startDns(4);}});
        addButton(layout,"Check HTTPS: example.com",new Runnable(){public void run(){startHttps();}});
        addButton(layout,"Check VPN MTU",new Runnable(){public void run(){begin("mtu");inspect();networkSnapshot("mtu");}});
        addButton(layout,"Read HTTPS body: 256 KiB maximum",new Runnable(){public void run(){startHttps(true);}});
        output = new TextView(this);
        output.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(output);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(layout);
        executeIntent(getIntent());
    }
    private void addButton(LinearLayout layout,String title,final Runnable action){Button button=new Button(this);button.setText(title);button.setOnClickListener(new View.OnClickListener(){public void onClick(View view){action.run();}});layout.addView(button);}
    @Override public void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);executeIntent(intent);}
    private void executeIntent(Intent intent){
        if(intent!=null&&"app.tuna.securityprobe.RUN".equals(intent.getAction())){
            String mode=intent.getStringExtra("mode");
            if("broadcasts".equals(mode)){broadcasts();return;}
            if("dns".equals(mode)){startDns(Math.max(1,Math.min(8,intent.getIntExtra("count",4))));return;}
            if("https".equals(mode)){startHttps();return;}
            if("https-body".equals(mode)){startHttps(true);return;}
            if("mtu".equals(mode)){begin("mtu");inspect();networkSnapshot("mtu");return;}
        }
        begin("inspect");inspect();
    }
    private void cancelPending(){dnsGeneration++;if(dnsCancellation!=null)dnsCancellation.cancel();if(httpsTask!=null)httpsTask.cancel(true);HttpsURLConnection connection=httpsConnection;if(connection!=null)connection.disconnect();}
    private void begin(String mode){cancelPending();runId=Long.toString(System.currentTimeMillis());event("run", "mode",mode,"uid",Process.myUid(),"pid",Process.myPid(),"api",Build.VERSION.SDK_INT);}
    private void event(String type,Object... attributes){
        try{
            JSONObject message=new JSONObject().put("runId",runId).put("event",type).put("elapsedMs",SystemClock.elapsedRealtime());
            for(int i=0;i<attributes.length;i+=2)message.put((String)attributes[i],attributes[i+1]);
            String line=message.toString();Log.i(TAG,line);output.append(line+"\n");
        }catch(Exception ignored){Log.e(TAG,"event_encoding_failed");}
    }
    private List<String> signers(PackageInfo info)throws Exception{
        Signature[] signatures;
        if(Build.VERSION.SDK_INT>=28)signatures=info.signingInfo.getApkContentsSigners();else signatures=info.signatures;
        List<String> hashes=new ArrayList<String>();
        for(Signature signature:signatures){byte[] digest=MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());StringBuilder text=new StringBuilder();for(byte b:digest)text.append(String.format(Locale.ROOT,"%02x",b&255));hashes.add(text.toString());}
        return hashes;
    }
    private boolean inspect(){
        try{
            PackageManager pm=getPackageManager();
            int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
            PackageInfo target=pm.getPackageInfo(TARGET,flags), own=pm.getPackageInfo(getPackageName(),flags);
            List<String> targetSigners=signers(target), ownSigners=signers(own);
            boolean sameSigner=false;for(String signer:ownSigners)if(targetSigners.contains(signer))sameSigner=true;
            event("identity","targetPackage",TARGET,"targetUid",target.applicationInfo.uid,"separateUid",target.applicationInfo.uid!=Process.myUid(),
                "separateSigner",!sameSigner,"probeDebuggable",(own.applicationInfo.flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,
                "probeSignerSha256",new JSONArray(ownSigners),"targetSignerSha256",new JSONArray(targetSigners),
                "targetVersionCode",Build.VERSION.SDK_INT>=28?target.getLongVersionCode():target.versionCode);
            ActivityInfo receiver=pm.getReceiverInfo(new ComponentName(TARGET,RECEIVER),0);
            event("receiver_metadata","exported",receiver.exported,"enabled",receiver.enabled,"permission",receiver.permission==null?JSONObject.NULL:receiver.permission);
            if(sameSigner||target.applicationInfo.uid==Process.myUid()){event("invalid_probe_identity");return false;}
            return true;
        }catch(Exception failure){event("inspection_failed","errorClass",failure.getClass().getSimpleName());return false;}
    }
    private Network networkSnapshot(String phase){
        try{
            ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            Network active=manager.getActiveNetwork(),bound=manager.getBoundNetworkForProcess();
            Network effective=bound==null?active:bound;NetworkCapabilities capabilities=effective==null?null:manager.getNetworkCapabilities(effective);
            LinkProperties properties=effective==null?null:manager.getLinkProperties(effective);
            int mtu=properties==null?0:properties.getMtu();
            int targetUid=getPackageManager().getApplicationInfo(TARGET,0).uid;
            int ownerUid=Build.VERSION.SDK_INT>=29&&capabilities!=null?capabilities.getOwnerUid():-1;
            event("network","phase",phase,"probeUid",Process.myUid(),"targetUid",targetUid,
                "separateUid",targetUid!=Process.myUid(),"activeNetworkPresent",active!=null,"processNetworkBound",bound!=null,
                "linkPropertiesAvailable",properties!=null,"mtuAvailable",mtu>0,"effectiveMtu",mtu>0?mtu:JSONObject.NULL,
                "capabilitiesAvailable",capabilities!=null,"transportVpn",capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                "transportWifi",capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                "transportCellular",capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                "transportEthernet",capabilities!=null&&capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                "internet",capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                "validated",capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                "notVpn",capabilities!=null&&capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                "ownerUidAvailable",ownerUid>=0,"networkOwnerUid",ownerUid>=0?ownerUid:JSONObject.NULL,
                "ownerMatchesTarget",ownerUid>=0?ownerUid==targetUid:JSONObject.NULL);
            return effective;
        }catch(Exception failure){event("network_snapshot_failed","phase",phase,"errorClass",failure.getClass().getSimpleName());return null;}
    }
    private void broadcasts(){
        begin("broadcasts");
        if(!inspect()){event("broadcasts_skipped");return;}
        int submitted=0,exceptions=0;
        for(String delivery:new String[]{"explicit","package_implicit"})for(String suffix:new String[]{"START_CORE","STOP_CORE","START_VPN","STOP_VPN","CHANGE_PROFILE"}){
            Intent intent=new Intent(TARGET+"."+suffix).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            if(delivery.equals("explicit"))intent.setComponent(new ComponentName(TARGET,RECEIVER));else intent.setPackage(TARGET);
            try{sendBroadcast(intent);submitted++;event("broadcast","delivery",delivery,"action",suffix,"result","submitted_not_delivery_proof");}
            catch(Exception failure){exceptions++;event("broadcast","delivery",delivery,"action",suffix,"result","exception","errorClass",failure.getClass().getSimpleName());}
        }
        event("broadcasts_complete","submitted",submitted,"exceptions",exceptions,"requiresTargetStateCorrelation",true);
    }

    private void startDns(final int count){
        begin("dns");
        if(Build.VERSION.SDK_INT<29){event("dns_skipped","reason","requires_api_29");return;}
        inspect();networkSnapshot("dns_start");
        event("dns_start","questionCount",count,"domain","example.com","network","default","cacheLookupDisabled",true);
        nextDns(dnsGeneration,0,count,0,0);
    }
    private void nextDns(final int generation,final int index,final int count,final int successes,final int failures){
        if(generation!=dnsGeneration)return;
        if(index>=count){event("dns_complete","completed",count,"successes",successes,"failures",failures);return;}
        final int type=index%2==0?DnsResolver.TYPE_A:DnsResolver.TYPE_AAAA;
        final Network expected=networkSnapshot("dns_query_start");
        final long start=SystemClock.elapsedRealtime();
        final CancellationSignal cancellation=new CancellationSignal();dnsCancellation=cancellation;
        final boolean[] finished={false};
        final Runnable timeout=new Runnable(){public void run(){
            if(generation!=dnsGeneration||finished[0])return;finished[0]=true;cancellation.cancel();
            event("dns_result","query",index+1,"type",type,"result","timeout","durationMs",SystemClock.elapsedRealtime()-start);
            nextDns(generation,index+1,count,successes,failures+1);
        }};
        handler.postDelayed(timeout,5000);
        try{DnsResolver.getInstance().query(null,"example.com",type,DnsResolver.FLAG_NO_CACHE_LOOKUP|DnsResolver.FLAG_NO_CACHE_STORE,mainExecutor,cancellation,new DnsResolver.Callback<List<InetAddress>>(){
            public void onAnswer(List<InetAddress> answer,int rcode){
                if(generation!=dnsGeneration||finished[0])return;finished[0]=true;handler.removeCallbacks(timeout);
                Network current=networkSnapshot("dns_query_end");
                event("dns_result","query",index+1,"type",type,"result","answer","rcode",rcode,"answerCount",answer.size(),"durationMs",SystemClock.elapsedRealtime()-start,"networkStable",expected!=null&&expected.equals(current));
                nextDns(generation,index+1,count,successes+(rcode==0?1:0),failures+(rcode==0?0:1));
            }
            public void onError(DnsResolver.DnsException failure){
                if(generation!=dnsGeneration||finished[0])return;finished[0]=true;handler.removeCallbacks(timeout);
                event("dns_result","query",index+1,"type",type,"result","resolver_error","errorCode",failure.code,"durationMs",SystemClock.elapsedRealtime()-start);
                nextDns(generation,index+1,count,successes,failures+1);
            }
        });}catch(Exception failure){finished[0]=true;handler.removeCallbacks(timeout);event("dns_result","query",index+1,"result","exception","errorClass",failure.getClass().getSimpleName());nextDns(generation,index+1,count,successes,failures+1);}
    }
    private void startHttps(){startHttps(false);}
    private void startHttps(final boolean readBody){
        final String domain=readBody?"speed.cloudflare.com":"example.com";
        final int bodyLimit=262144;
        begin(readBody?"https-body":"https");inspect();final Network expected=networkSnapshot("https_start");
        final int generation=dnsGeneration;final long start=SystemClock.elapsedRealtime();final boolean[] finished={false};
        final Runnable deadline=new Runnable(){public void run(){
            if(generation!=dnsGeneration||finished[0])return;finished[0]=true;cancelPending();networkSnapshot("https_timeout");
            event("https_result","domain",domain,"result","timeout","durationMs",SystemClock.elapsedRealtime()-start);
        }};
        handler.postDelayed(deadline,readBody?20000:8000);
        try{httpsTask=httpsExecutor.submit(new Runnable(){public void run(){
            HttpsURLConnection connection=null;int code=-1;String error=null;int bodyBytes=0;boolean bodyEof=false;
            try{
                connection=(HttpsURLConnection)new URL(readBody?"https://speed.cloudflare.com/__down?bytes=262144":"https://example.com/").openConnection();httpsConnection=connection;
                connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(5000);
                connection.setUseCaches(false);connection.setRequestMethod(readBody?"GET":"HEAD");
                if(readBody)connection.setRequestProperty("Accept-Encoding","identity");
                code=connection.getResponseCode();
                if(readBody){
                    InputStream source=code>=400?connection.getErrorStream():connection.getInputStream();
                    if(source!=null)try(InputStream input=source){
                        byte[] buffer=new byte[8192];
                        while(bodyBytes<bodyLimit){
                            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                            int count=input.read(buffer,0,Math.min(buffer.length,bodyLimit-bodyBytes));
                            if(count<0){bodyEof=true;break;}
                            bodyBytes+=count;
                        }
                    }
                }
            }catch(Exception failure){error=failure.getClass().getSimpleName();}
            finally{if(connection!=null)connection.disconnect();if(httpsConnection==connection)httpsConnection=null;}
            final int resultCode=code,resultBytes=bodyBytes;final boolean resultEof=bodyEof;final String errorClass=error;
            handler.post(new Runnable(){public void run(){
                if(generation!=dnsGeneration||finished[0])return;finished[0]=true;handler.removeCallbacks(deadline);
                Network current=networkSnapshot("https_end");
                event("https_result","domain",domain,"method",readBody?"GET":"HEAD","result",errorClass==null?"response":"exception",
                    "bodyReadEnabled",readBody,"bodyBytesRead",resultBytes,"bodyLimitBytes",readBody?bodyLimit:0,
                    "bodyLimitReached",readBody&&resultBytes==bodyLimit,"bodyEndOfStream",resultEof,
                    "boundedTransferSucceeded",readBody&&errorClass==null&&resultCode==200&&resultBytes==bodyLimit,
                    "responseCode",resultCode,"errorClass",errorClass==null?JSONObject.NULL:errorClass,"durationMs",SystemClock.elapsedRealtime()-start,
                    "networkStable",expected!=null&&expected.equals(current));
            }});
        }});}catch(Exception failure){finished[0]=true;handler.removeCallbacks(deadline);event("https_result","result","worker_unavailable","errorClass",failure.getClass().getSimpleName());}
    }
    @Override public void onDestroy(){cancelPending();httpsExecutor.shutdownNow();handler.removeCallbacksAndMessages(null);super.onDestroy();}
}
