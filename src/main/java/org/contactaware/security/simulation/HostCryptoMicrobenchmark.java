package org.contactaware.security.simulation;

import org.contactaware.contacts.JsonFiles;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Host-only primitive timing; NOT an INSTALL, radio handshake or onboard calibration. */
public final class HostCryptoMicrobenchmark {
    public static void main(String[] args) throws Exception {
        var out=Path.of(args[0]); if(Files.exists(out))throw new IllegalArgumentException("Existing evidence");
        var generator=KeyGenerator.getInstance("AES");generator.init(256);
        var key=generator.generateKey();var cipher=Cipher.getInstance("AES/GCM/NoPadding");
        var data=new byte[1000];long nonce=0;int warmup=2000,iterations=10000;
        var rows=new ArrayList<Map<String,Object>>();var cpu=ManagementFactory.getThreadMXBean();
        if(cpu.isCurrentThreadCpuTimeSupported()&&!cpu.isThreadCpuTimeEnabled())cpu.setThreadCpuTimeEnabled(true);
        for(String operation:List.of("AES256_KEYGEN","AES256_GCM_1000B")) {
            for(int sample=-1;sample<5;sample++) {
                int count=sample<0?warmup:iterations;
                long c0=cpu.isCurrentThreadCpuTimeSupported()?cpu.getCurrentThreadCpuTime():-1;
                long w0=System.nanoTime();int checksum=0;
                for(int i=0;i<count;i++) {
                    if(operation.equals("AES256_KEYGEN")) checksum^=generator.generateKey().getEncoded()[0];
                    else {
                        byte[] iv=ByteBuffer.allocate(12).putInt(0).putLong(nonce++).array();
                        cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,iv));checksum^=cipher.doFinal(data)[0];
                    }
                }
                long wall=System.nanoTime()-w0,c1=cpu.isCurrentThreadCpuTimeSupported()?cpu.getCurrentThreadCpuTime():-1;
                if(sample>=0) {
                    var row=new LinkedHashMap<String,Object>();row.put("operation",operation);row.put("sample",sample);row.put("iterations",count);
                    row.put("wallNanosPerOp",(double)wall/count);row.put("threadCpuNanosPerOp",c0<0?null:(double)(c1-c0)/count);row.put("checksum",checksum);rows.add(row);
                }
            }
        }
        JsonFiles.write(out,Map.of("classification","MEASURED_HOST_PRIMITIVE_ONLY","java",System.getProperty("java.runtime.version"),
                "provider",cipher.getProvider().toString(),"warmupPerOperation",warmup,"samples",rows,
                "limitations","Single JVM microbenchmark includes allocations/init, no JMH isolation; no radio/ACK/handshake or onboard measurement. Synthetic profiles unchanged."));
    }
}
