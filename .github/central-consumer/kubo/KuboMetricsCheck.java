package org.acme;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Singleton
class KuboMetricsCheck implements KuboSmokeTest.MetricsCheck {
    private static final Set<String> OPERATIONS = Set.of("block_get", "cat", "add");
    private static final Set<String> OUTCOMES = Set.of("success", "http_error", "timeout", "cancelled",
            "stream_error", "failure");

    @Inject
    MeterRegistry registry;

    @Override
    public void verify(String scenario, boolean enabled) {
        var meters = registry.find("ipfs.kubo.operation.duration").meters();
        if (!enabled) {
            if (!meters.isEmpty()) {
                throw new IllegalStateException("Kubo meters registered with metrics disabled");
            }
            System.out.println("Kubo consumer verified with Micrometer present and metrics disabled.");
            return;
        }

        var timers = registry.find("ipfs.kubo.operation.duration").timers();
        if (timers.size() != OPERATIONS.size() * OUTCOMES.size() || timers.size() != meters.size()) {
            throw new IllegalStateException("Unexpected Kubo meter count and type: " + meters);
        }
        Set<String> expected = switch (scenario) {
            case "success" -> Set.of("block_get/success", "cat/success", "add/success");
            case "trailers" -> Set.of("cat/stream_error", "add/stream_error");
            case "redirect" -> Set.of("block_get/http_error", "cat/http_error", "add/http_error");
            case "interrupted" -> Set.of("cat/timeout", "cat/cancelled");
            case "untrusted CA", "hostname mismatch" -> Set.of("block_get/failure", "cat/failure", "add/failure");
            default -> throw new IllegalArgumentException(scenario);
        };
        var seen = new HashSet<String>();
        var durations = new HashMap<String, Double>();
        for (var timer : timers) {
            var id = timer.getId();
            String operation = id.getTag("operation");
            String outcome = id.getTag("outcome");
            String sample = operation + "/" + outcome;
            var tags = id.getTags().stream().map(Tag::getKey).toList();
            if (operation == null || outcome == null || !OPERATIONS.contains(operation) || !OUTCOMES.contains(outcome)
                    || tags.size() != 2 || !seen.add(sample)) {
                throw new IllegalStateException("Unexpected Kubo timer tags: " + id);
            }
            long count = expected.contains(sample) ? 1 : 0;
            double duration = timer.totalTime(TimeUnit.NANOSECONDS);
            if (timer.count() != count || (count == 1 ? !(duration > 0) : duration != 0)) {
                throw new IllegalStateException("Unexpected Kubo timer for " + sample + ": "
                        + timer.count() + " recordings and " + duration + " ns");
            }
            if (count == 1) {
                durations.put(sample, duration);
            }
        }
        System.out.println("Kubo metrics verified for " + scenario + " (one sample each, durations in ns): " + durations);
    }
}
