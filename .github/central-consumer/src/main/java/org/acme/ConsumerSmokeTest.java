package org.acme;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class ConsumerSmokeTest implements QuarkusApplication {

    @Inject
    BlockService blocks;

    @Override
    public int run(String... args) throws Exception {
        byte[] payload = { 'I', 'P', 'F', 'S', 0, (byte) 0xff, '\n' };
        String cid = blocks.put(payload, Cid.Codec.Raw).get(15, TimeUnit.SECONDS).toString();
        if (!"bafkreiaabc27q6hpnrclwsa5cnjgzv3uv6zbonq7avwsn6bm2ncdi5fbjq".equals(cid)) {
            throw new IllegalStateException("Unexpected CID for the test block: " + cid);
        }

        byte[] restored = blocks.get(cid).get(15, TimeUnit.SECONDS);
        if (!Arrays.equals(payload, restored)) {
            throw new IllegalStateException("Block read by CID did not match the original bytes: " + cid);
        }

        System.out.printf("Maven Central consumer wrote and read %d bytes by CID %s%n", restored.length, cid);
        return 0;
    }
}
