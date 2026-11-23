package io.quarkiverse.ipfs.test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.IpfsRecorder;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.smallrye.config.SmallRyeConfigBuilder;

final class TestIpfsNode implements ShutdownContext, AutoCloseable {
    private final Deque<Runnable> tasks = new ArrayDeque<>();

    EmbeddedIpfs start(StorageType storage, Map<String, String> settings) {
        var config = new SmallRyeConfigBuilder().withMapping(IpfsRuntimeConfig.class)
                .withDefaultValues(settings).build().getConfigMapping(IpfsRuntimeConfig.class);
        return new IpfsRecorder(new RuntimeValue<>(config)).start(0, storage, this).getValue();
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
