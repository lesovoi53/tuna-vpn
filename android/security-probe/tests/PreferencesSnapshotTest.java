package app.tuna.securityprobe;

import androidx.datastore.preferences.PreferencesProto;
import androidx.datastore.preferences.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Fixture bytes come from AndroidX's real serializer classes, not a mirror encoder. */
public final class PreferencesSnapshotTest {
    private static final byte[] KEY = new byte[32];
    private static int checks;
    static { Arrays.fill(KEY, (byte) 47); }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static PreferencesProto.Value string(String value) { return PreferencesProto.Value.newBuilder().setString(value).build(); }
    private static PreferencesProto.Value set(String... values) { return PreferencesProto.Value.newBuilder().setStringSet(PreferencesProto.StringSet.newBuilder().addAllStrings(Arrays.asList(values))).build(); }
    private static String encrypt(String name, String type, String value) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY,"AES"), new GCMParameterSpec(128, iv));
        cipher.updateAAD(("tuna.preferences/v1/"+type+"/"+name).getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] joined = Arrays.copyOf(iv, iv.length+encrypted.length);
        System.arraycopy(encrypted,0,joined,iv.length,encrypted.length);
        return "tuna.enc.v1:"+Base64.getEncoder().encodeToString(joined);
    }
    private static final PreferencesSnapshot.Decryptor DECRYPTOR = new PreferencesSnapshot.Decryptor(){
        public byte[] decrypt(byte[] envelope,byte[] aad)throws Exception{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(KEY,"AES"),new GCMParameterSpec(128,Arrays.copyOf(envelope,12)));
            cipher.updateAAD(aad);return cipher.doFinal(envelope,12,envelope.length-12);
        }
    };
    private static PreferencesSnapshot.Result read(PreferencesProto.PreferenceMap map)throws Exception{return PreferencesSnapshot.read(map.toByteArray(),"fixture",DECRYPTOR);}
    private static void reject(byte[] bytes) throws Exception {
        boolean rejected=false;
        try{PreferencesSnapshot.read(bytes,"fixture",DECRYPTOR);}catch(Exception expected){rejected=true;}
        check(rejected,"malformed/unauthenticated input accepted");
    }
    public static void main(String[] args)throws Exception{
        PreferencesProto.PreferenceMap.Builder original=PreferencesProto.PreferenceMap.newBuilder()
            .putPreferences("bool",PreferencesProto.Value.newBuilder().setBoolean(false).build())
            .putPreferences("float",PreferencesProto.Value.newBuilder().setFloat(Float.NaN).build())
            .putPreferences("int",PreferencesProto.Value.newBuilder().setInteger(-123).build())
            .putPreferences("long",PreferencesProto.Value.newBuilder().setLong(Long.MIN_VALUE).build())
            .putPreferences("text",string("synthetic-credential-не-секрет"))
            .putPreferences("set",set("z","a","тест","a"))
            .putPreferences("double",PreferencesProto.Value.newBuilder().setDouble(-0.0).build())
            .putPreferences("bytes",PreferencesProto.Value.newBuilder().setBytes(ByteString.copyFrom(new byte[]{0,1,-1})).build());
        PreferencesSnapshot.Result before=read(original.build());
        check(before.entries==8&&!before.encrypted,"wrong plaintext shape");
        PreferencesProto.PreferenceMap.Builder encrypted=original.build().toBuilder()
            .putPreferences("text",string(encrypt("text","string","synthetic-credential-не-секрет")))
            .putPreferences("set",set(encrypt("set","set","a"),encrypt("set","set","тест"),encrypt("set","set","z")))
            .putPreferences(PreferencesSnapshot.MARKER,PreferencesProto.Value.newBuilder().setInteger(1).build());
        PreferencesSnapshot.Result after=read(encrypted.build());
        check(after.encrypted&&before.hashes.equals(after.hashes),"migration changed canonical settings");
        check(before.hashes.equals(read(original.build().toBuilder().putPreferences("set",set("a","z","тест")).build()).hashes),"set order/dedup changed snapshot");
        check(!before.hashes.equals(read(original.build().toBuilder().putPreferences("text",string("changed")).build()).hashes),"changed value not detected");
        check(!before.hashes.equals(read(original.build().toBuilder().removePreferences("text").putPreferences("other",string("synthetic-credential-не-секрет")).build()).hashes),"changed key not detected");
        reject(encrypted.build().toBuilder().removePreferences(PreferencesSnapshot.MARKER).build().toByteArray());
        reject(encrypted.build().toBuilder().putPreferences("text",string("plaintext")).build().toByteArray());
        reject(encrypted.build().toBuilder().putPreferences("text",string(encrypt("wrong-key","string","payload"))).build().toByteArray());
        reject(encrypted.build().toBuilder().putPreferences(PreferencesSnapshot.MARKER,PreferencesProto.Value.newBuilder().setInteger(2).build()).build().toByteArray());
        byte[] good=original.build().toByteArray();reject(Arrays.copyOf(good,good.length-1));
        reject(new byte[]{10,127});
        reject(new byte[]{10,-1,-1,-1,-1,-1,-1,-1,-1,-1,2});
        System.out.println("PreferencesSnapshot: "+checks+" checks passed against AndroidX protobuf fixtures");
    }
}
