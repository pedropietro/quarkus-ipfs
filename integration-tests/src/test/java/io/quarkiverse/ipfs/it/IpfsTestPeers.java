package io.quarkiverse.ipfs.it;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;

import org.peergos.EmbeddedIpfs;

import io.libp2p.core.crypto.KeyKt;
import io.libp2p.core.crypto.PrivKey;
import io.quarkiverse.ipfs.runtime.IpfsRecorder;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.smallrye.config.SmallRyeConfigBuilder;

final class IpfsTestPeers implements ShutdownContext, AutoCloseable {

    private final Deque<Runnable> tasks = new ArrayDeque<>();

    EmbeddedIpfs start(boolean bitswap) {
        return start(bitswap, null);
    }

    EmbeddedIpfs start(boolean bitswap, PrivKey identity) {
        var builder = new SmallRyeConfigBuilder().withMapping(IpfsRuntimeConfig.class)
                .withDefaultValue("quarkus.ipfs.run-bitswap", Boolean.toString(bitswap));
        if (identity != null) {
            builder.withDefaultValue("quarkus.ipfs.identity-private-key",
                    Base64.getEncoder().encodeToString(KeyKt.marshalPrivateKey(identity)));
        }
        var config = builder.build().getConfigMapping(IpfsRuntimeConfig.class);
        return new IpfsRecorder(new RuntimeValue<>(config)).start(0, StorageType.RAM, this).getValue();
    }

    @Override
    public void addShutdownTask(Runnable task) {
        tasks.addFirst(task);
    }

    @Override
    public void addLastShutdownTask(Runnable task) {
        tasks.addLast(task);
    }

    @Override
    public void close() {
        while (!tasks.isEmpty()) {
            tasks.removeFirst().run();
        }
    }
}
