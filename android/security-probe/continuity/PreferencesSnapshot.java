package app.tuna.securityprobe;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Independent reader of AndroidX PreferencesProto; no app/AndroidX runtime dependency. */
public final class PreferencesSnapshot {
    public static final String MARKER = "_tuna_secret_storage_version";
    private static final String PREFIX = "tuna.enc.v1:";
    public interface Decryptor { byte[] decrypt(byte[] envelope, byte[] aad) throws Exception; }
    public static final class Result {
        public final TreeMap<String, String> hashes = new TreeMap<String, String>();
        public boolean encrypted;
        public int entries;
    }
    private static final class Value {
        int type;
        long number;
        byte[] bytes;
        List<byte[]> strings;
    }

    public static Result read(byte[] input, String namespace, Decryptor decryptor) throws Exception {
        Reader root = new Reader(input);
        TreeMap<String, Value> values = new TreeMap<String, Value>();
        while (root.hasNext()) {
            root.expectTag(10); // PreferenceMap.preferences: map<string, Value> = 1
            Reader entry = new Reader(root.bytes());
            String key = null;
            Value value = null;
            while (entry.hasNext()) {
                int tag = entry.tag();
                if (tag == 10) key = utf8(entry.bytes());
                else if (tag == 18) value = readValue(entry.bytes());
                else throw new IOException("Unexpected preference map field");
            }
            if (key == null || value == null || values.size() > 100000) throw new IOException("Invalid preference entry");
            values.put(key, value);
        }
        Result result = new Result();
        Value marker = values.remove(MARKER);
        if (marker != null) {
            if (marker.type != 3 || marker.number != 1) throw new IOException("Unknown preference storage version");
            result.encrypted = true;
        }
        for (Map.Entry<String, Value> item : values.entrySet()) {
            Value value = item.getValue();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(value.type);
            switch (value.type) {
                case 1: out.writeBoolean(value.number != 0); break;
                case 2: out.writeInt(Float.floatToIntBits(Float.intBitsToFloat((int) value.number))); break;
                case 3: out.writeInt((int) value.number); break;
                case 4: out.writeLong(value.number); break;
                case 5: writeBytes(out, plaintext(value.bytes, item.getKey(), "string", result.encrypted, decryptor)); break;
                case 6:
                    TreeSet<String> set = new TreeSet<String>();
                    for (byte[] member : value.strings) {
                        byte[] plain = plaintext(member, item.getKey(), "set", result.encrypted, decryptor);
                        try { set.add(utf8(plain)); } finally { Arrays.fill(plain, (byte) 0); }
                    }
                    out.writeInt(set.size());
                    for (String member : set) writeBytes(out, member.getBytes(StandardCharsets.UTF_8));
                    break;
                case 7: out.writeLong(Double.doubleToLongBits(Double.longBitsToDouble(value.number))); break;
                case 8: writeBytes(out, value.bytes); break;
                default: throw new IOException("Unknown preference type");
            }
            out.flush();
            byte[] canonical = bytes.toByteArray();
            try {
                result.hashes.put(hash((namespace + "\u0000" + item.getKey()).getBytes(StandardCharsets.UTF_8)), hash(canonical));
            } finally { Arrays.fill(canonical, (byte) 0); }
            result.entries++;
        }
        return result;
    }

    private static Value readValue(byte[] data) throws IOException {
        Reader in = new Reader(data);
        Value value = new Value();
        while (in.hasNext()) {
            int tag = in.tag();
            value = new Value(); // protobuf oneof: the last recognized field wins
            value.type = tag >>> 3;
            switch (tag) {
                case 8: case 24: case 32: value.number = in.varint(); break;
                case 21: value.number = in.fixed(4); break;
                case 42: case 66: value.bytes = in.bytes(); break;
                case 50:
                    Reader members = new Reader(in.bytes());
                    value.strings = new ArrayList<byte[]>();
                    while (members.hasNext()) { members.expectTag(10); value.strings.add(members.bytes()); }
                    break;
                case 57: value.number = in.fixed(8); break;
                default: throw new IOException("Unknown preference value field");
            }
        }
        if (value.type == 0) throw new IOException("Missing preference value");
        return value;
    }

    private static byte[] plaintext(byte[] bytes, String key, String type, boolean encrypted, Decryptor decryptor) throws Exception {
        String text = utf8(bytes);
        if (!encrypted) {
            if (text.startsWith(PREFIX)) throw new IOException("Encrypted value lacks marker");
            return Arrays.copyOf(bytes, bytes.length);
        }
        if (!text.startsWith(PREFIX) || decryptor == null) throw new IOException("Plaintext value in encrypted preferences");
        byte[] envelope = Base64.getDecoder().decode(text.substring(PREFIX.length()));
        if (envelope.length < 28) throw new IOException("Short encrypted preference");
        try { return decryptor.decrypt(envelope, ("tuna.preferences/v1/" + type + "/" + key).getBytes(StandardCharsets.UTF_8)); }
        finally { Arrays.fill(envelope, (byte) 0); }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException { out.writeInt(value.length); out.write(value); }
    private static String utf8(byte[] value) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
    }
    public static String hash(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder output = new StringBuilder(64);
        for (byte value : digest) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;
        Reader(byte[] data) throws IOException { if (data.length > 32 * 1024 * 1024) throw new IOException("Oversized preferences"); this.data = data; }
        boolean hasNext() { return offset < data.length; }
        int tag() throws IOException { long value = varint(); if (value < 1 || value > Integer.MAX_VALUE) throw new IOException("Invalid tag"); return (int) value; }
        void expectTag(int expected) throws IOException { if (tag() != expected) throw new IOException("Unexpected protobuf field"); }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (offset >= data.length) throw new IOException("Truncated varint");
                int next = data[offset++] & 255;
                if (shift == 63 && next > 1) throw new IOException("Overflow varint");
                value |= (long) (next & 127) << shift;
                if ((next & 128) == 0) return value;
            }
            throw new IOException("Overflow varint");
        }
        long fixed(int count) throws IOException {
            if (data.length - offset < count) throw new IOException("Truncated fixed field");
            long value = 0;
            for (int i = 0; i < count; i++) value |= (long) (data[offset++] & 255) << (8 * i);
            return value;
        }
        byte[] bytes() throws IOException {
            long length = varint();
            if (length < 0 || length > data.length - offset) throw new IOException("Truncated bytes");
            byte[] value = Arrays.copyOfRange(data, offset, offset + (int) length);
            offset += (int) length;
            return value;
        }
    }
}
