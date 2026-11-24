package io.quarkiverse.ipfs.runtime;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;

import org.peergos.EmbeddedIpfs;

import io.quarkus.runtime.metrics.MetricsFactory;

/** Metrics owned by one running IPFS node. */
public final class IpfsMetrics {

    enum Operation {
        BLOCK_READ,
        BLOCK_WRITE,
        IPNS_PUBLISH,
        IPNS_RESOLVE
    }

    private record Outcomes(LongAdder successes, LongAdder failures) {
        Outcomes() {
            this(new LongAdder(), new LongAdder());
        }
    }

    private final Map<Operation, Outcomes> operations;

    IpfsMetrics(boolean enabled) {
        if (enabled) {
            var counters = new EnumMap<Operation, Outcomes>(Operation.class);
            for (Operation operation : Operation.values()) {
                counters.put(operation, new Outcomes());
            }
            operations = counters;
        } else {
            operations = Map.of();
        }
    }

    <T> CompletableFuture<T> record(Operation operation, CompletableFuture<T> result) {
        var outcomes = operations.get(operation);
        if (outcomes == null) {
            return result;
        }
        return result.whenComplete((value, failure) -> {
            if (failure == null) {
                outcomes.successes().increment();
            } else {
                outcomes.failures().increment();
            }
        });
    }

    void register(MetricsFactory factory, EmbeddedIpfs node) {
        factory.builder("ipfs.peers.connected")
                .description("Number of distinct peers connected to the embedded IPFS node")
                .buildGauge(node, IpfsMetrics::connectedPeers);
        operations.forEach((operation, outcomes) -> {
            registerCounter(factory, operation, "success", outcomes.successes());
            registerCounter(factory, operation, "failure", outcomes.failures());
        });
    }

    private static long connectedPeers(EmbeddedIpfs node) {
        return node.node.getNetwork().getConnections().stream()
                .map(connection -> connection.secureSession().getRemoteId())
                .distinct().count();
    }

    private static void registerCounter(MetricsFactory factory, Operation operation, String outcome, LongAdder counter) {
        factory.builder("ipfs.operations")
                .description("Completed IPFS block and IPNS operations")
                .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome)
                .buildCounter(counter, LongAdder::sum);
    }
}
