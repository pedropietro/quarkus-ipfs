package io.quarkiverse.ipfs.runtime;

import java.io.UncheckedIOException;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.quarkus.runtime.metrics.MetricsFactory;

/** Optional metrics for the Kubo client, independent of the embedded node. */
public final class KuboMetrics {

    enum Operation {
        BLOCK_GET,
        CAT,
        ADD,
        LIST_DIRECTORY
    }

    private enum Outcome {
        SUCCESS,
        HTTP_ERROR,
        TIMEOUT,
        CANCELLED,
        STREAM_ERROR,
        FAILURE
    }

    private volatile Map<Operation, Map<Outcome, MetricsFactory.TimeRecorder>> timers = Map.of();

    long start() {
        return timers.isEmpty() ? 0 : System.nanoTime();
    }

    <T> CompletableFuture<T> record(Operation operation, long started, CompletableFuture<T> result) {
        var recorders = timers.get(operation);
        if (started == 0 || recorders == null) {
            return result;
        }
        // Report metrics before publishing the result, while forwarding cancellation to the actual transfer.
        var measured = new CompletableFuture<T>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                return result.cancel(mayInterruptIfRunning);
            }
        };
        result.whenComplete((value, failure) -> {
            try {
                recorders.get(outcome(failure)).update(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            } finally {
                if (failure == null) {
                    measured.complete(value);
                } else {
                    measured.completeExceptionally(failure);
                }
            }
        });
        return measured;
    }

    void register(MetricsFactory factory) {
        var registered = new EnumMap<Operation, Map<Outcome, MetricsFactory.TimeRecorder>>(Operation.class);
        for (Operation operation : Operation.values()) {
            var recorders = new EnumMap<Outcome, MetricsFactory.TimeRecorder>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                recorders.put(outcome, factory.builder("ipfs.kubo.operation.duration")
                        .description("Duration and count of completed Kubo client calls")
                        .tag("operation", operation.name().toLowerCase(Locale.ROOT))
                        .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                        .buildTimer());
            }
            registered.put(operation, recorders);
        }
        timers = registered;
    }

    private static Outcome outcome(Throwable error) {
        while (error instanceof CompletionException wrapped && wrapped.getCause() != null) {
            error = wrapped.getCause();
        }
        return switch (error) {
            case null -> Outcome.SUCCESS;
            case KuboClient.KuboRequestException ignored -> Outcome.HTTP_ERROR;
            case NoSuchElementException ignored -> Outcome.HTTP_ERROR;
            case TimeoutException ignored -> Outcome.TIMEOUT;
            case CancellationException ignored -> Outcome.CANCELLED;
            case UncheckedIOException failure when failure.getCause() instanceof KuboClient.StreamException ->
                Outcome.STREAM_ERROR;
            default -> Outcome.FAILURE;
        };
    }
}
