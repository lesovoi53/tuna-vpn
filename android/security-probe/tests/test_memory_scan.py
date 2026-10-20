"""Host regression tests of the exact nested Java scanner; no Android/device data."""
import argparse
from pathlib import Path
import subprocess
import tempfile


HARNESS = r"""
import java.io.*;
import java.nio.file.*;
import java.util.function.LongSupplier;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.nio.charset.StandardCharsets;

class SystemClock {
    static long elapsedRealtime() { return System.nanoTime()/1000000; }
}
SCANNER
public class MemoryScanTest {
    static int tests;
    static final byte[] CANARY="TUNA_SYNTHETIC_CANARY".getBytes(StandardCharsets.UTF_8);
    static void require(boolean ok) { if(!ok) throw new AssertionError("Synthetic scanner assertion"); }
    static void run(MemoryAcceptance.Scan scan,Path file) throws Exception {
        scan.walk(file.toFile(),file.getFileName().toString(),0,0);
    }
    static void fails(MemoryAcceptance.Scan scan,Path file,String reason) throws Exception {
        boolean failed=false;
        try { run(scan,file); } catch(IllegalStateException expected) { failed=true; }
        require(failed&&reason.equals(scan.failureReason)); tests++;
    }
    static MemoryAcceptance.Scan bounded(long bytes,long time,int files,int depth,LongSupplier clock) {
        return new MemoryAcceptance.Scan(CANARY,bytes,time,files,depth,clock);
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        Path clean=root.resolve("large-synthetic.bin");
        try(RandomAccessFile file=new RandomAccessFile(clean.toFile(),"rw")) {
            file.setLength(257L*1024*1024);
        }
        MemoryAcceptance.Scan old=bounded(256L*1024*1024,120000,20000,32,SystemClock::elapsedRealtime);
        fails(old,clean,"byte_limit");
        require(old.bytes>256L*1024*1024&&old.files==1);
        MemoryAcceptance.Scan full=new MemoryAcceptance.Scan(CANARY);
        run(full,clean);
        require(full.bytes==257L*1024*1024&&full.matches==0&&full.failureReason==null); tests++;
        Files.delete(clean); // Only this harness's newly created synthetic file.

        Path crossing=root.resolve("crossing.bin");
        byte[] data=new byte[65536+CANARY.length];
        System.arraycopy(CANARY,0,data,65530,CANARY.length);
        Files.write(crossing,data);
        MemoryAcceptance.Scan cross=new MemoryAcceptance.Scan(CANARY);run(cross,crossing);
        require(cross.matches==1&&cross.bytes==data.length);tests++;
        MemoryAcceptance.Scan exact=bounded(data.length,120000,10,10,SystemClock::elapsedRealtime);
        run(exact,crossing);require(exact.matches==1&&exact.bytes==data.length);tests++;
        fails(bounded(data.length-1,120000,10,10,SystemClock::elapsedRealtime),crossing,"byte_limit");
        AtomicLong clock=new AtomicLong();
        MemoryAcceptance.Scan timed=bounded(1000000,2,10,10,clock::getAndIncrement);
        fails(timed,crossing,"time_limit");require(timed.bytes==65536);
        fails(bounded(1000000,120000,0,10,SystemClock::elapsedRealtime),crossing,"file_limit");
        fails(bounded(1000000,120000,10,0,SystemClock::elapsedRealtime),root,"depth_limit");
        fails(new MemoryAcceptance.Scan(CANARY),root.resolve("absent"),"entry_missing");

        Path forbidden=root.resolve("routing-policy.json");Files.writeString(forbidden,"{}");
        MemoryAcceptance.Scan names=new MemoryAcceptance.Scan(CANARY);run(names,forbidden);
        require(names.forbidden==1&&names.matches==0);tests++;
        Path overlap=root.resolve("overlap.bin");Files.writeString(overlap,"aaaaa");
        MemoryAcceptance.Scan overlaps=new MemoryAcceptance.Scan("aaa".getBytes(StandardCharsets.UTF_8));
        run(overlaps,overlap);require(overlaps.matches==3);tests++;
        Path split=root.resolve("split");Files.createDirectory(split);
        Files.write(split.resolve("first"),java.util.Arrays.copyOfRange(CANARY,0,8));
        Files.write(split.resolve("second"),java.util.Arrays.copyOfRange(CANARY,8,CANARY.length));
        MemoryAcceptance.Scan separate=new MemoryAcceptance.Scan(CANARY);run(separate,split);
        require(separate.matches==0&&separate.files==2);tests++;
        System.out.println("Memory scanner: "+tests+" synthetic tests PASS");
    }
}
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    args = parser.parse_args()
    source = (Path(__file__).resolve().parents[1] / "acceptance" / "MemoryAcceptance.java").read_text(encoding="utf-8")
    # Compile the actual scanner verbatim, without duplicating its algorithm or Android stubs.
    scanner = source[source.index("    static final class Scan {"):].rstrip()
    assert scanner.endswith("}\n}"), "Scanner must remain the last outer member"
    scanner = "final class MemoryAcceptance {\n" + scanner
    with tempfile.TemporaryDirectory(prefix="tuna-memory-scan-") as temp:
        folder = Path(temp)
        java = folder / "MemoryScanTest.java"
        java.write_text(HARNESS.replace("SCANNER", scanner), encoding="utf-8")
        subprocess.run([str(args.java_home / "bin" / "javac.exe"), "-d", str(folder), str(java)], check=True)
        fixtures = folder / "fixtures"
        fixtures.mkdir()
        subprocess.run([str(args.java_home / "bin" / "java.exe"), "-cp", str(folder), "MemoryScanTest", str(fixtures)], check=True)


if __name__ == "__main__":
    main()
