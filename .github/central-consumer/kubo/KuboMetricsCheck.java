package org.acme;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

@Singleton
class KuboMetricsCheck implements KuboSmokeTest.MetricsCheck {
    private static final Set<String> BASE_OPERATIONS = Set.of("block_get", "cat", "add");
    private static final Set<String> OUTCOMES = Set.of("success", "http_error", "timeout", "cancelled",
            "stream_error", "failure");

    @Inject
    MeterRegistry registry;

    @Inject
    Instance<KuboSmokeTest.DirectoryPolicyCheck> directoryPolicy;

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

        var operations = new HashSet<>(BASE_OPERATIONS);
        if (directoryPolicy.isResolvable()) {
            operations.add("list_directory");
        }
        var timers = registry.find("ipfs.kubo.operation.duration").timers();
        if (timers.size() != operations.size() * OUTCOMES.size() || timers.size() != meters.size()) {
            throw new IllegalStateException("Unexpected Kubo meter count and type: " + meters);
        }
        Set<String> expected = switch (scenario) {
            case "success" -> Set.of("block_get/success", "cat/success", "add/success");
            case "trailers" -> Set.of("cat/stream_error", "add/stream_error");
            case "redirect" -> Set.of("block_get/http_error", "cat/http_error", "add/http_error");
            case "interrupted" -> Set.of("cat/timeout", "cat/cancelled");
            case "directory-policy" -> Set.of("list_directory/success", "list_directory/timeout",
                    "list_directory/cancelled", "list_directory/failure");
            case "untrusted CA", "hostname mismatch" -> Set.of("block_get/failure", "cat/failure", "add/failure");
            default -> throw new IllegalArgumentException(scenario);
        };
        Map<String, Integer> repeated = "directory-policy".equals(scenario)
                ? Map.of("list_directory/timeout", 2, "list_directory/cancelled", 2) : Map.of();
        var seen = new HashSet<String>();
        var durations = new HashMap<String, Double>();
        for (var timer : timers) {
            var id = timer.getId();
            String operation = id.getTag("operation");
            String outcome = id.getTag("outcome");
            String sample = operation + "/" + outcome;
            var tags = id.getTags().stream().map(Tag::getKey).toList();
            if (operation == null || outcome == null || !operations.contains(operation) || !OUTCOMES.contains(outcome)
                    || tags.size() != 2 || !seen.add(sample)) {
                throw new IllegalStateException("Unexpected Kubo timer tags: " + id);
            }
            long count = repeated.getOrDefault(sample, expected.contains(sample) ? 1 : 0);
            double duration = timer.totalTime(TimeUnit.NANOSECONDS);
            if (timer.count() != count || (count > 0 ? !(duration > 0) : duration != 0)) {
                throw new IllegalStateException("Unexpected Kubo timer for " + sample + ": "
                        + timer.count() + " recordings and " + duration + " ns");
            }
            if (count > 0) {
                durations.put(sample, duration);
            }
        }
        System.out.println("Kubo metrics verified for " + scenario + " (expected sample counts and durations in ns): " + durations);
    }
}
