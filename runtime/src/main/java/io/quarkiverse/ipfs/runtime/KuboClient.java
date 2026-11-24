package io.quarkiverse.ipfs.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;

/** CDI client for reading raw blocks from a configured Kubo HTTP RPC server. */
public class KuboClient {
    private final HttpClient http;
    private final String blockEndpoint;
    private final Duration timeout;
    private final Optional<String> bearerToken;

    @Inject
    KuboClient(IpfsRuntimeConfig config) {
        var kubo = config.kubo();
        URI url = kubo.apiUrl();
        if (!("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme()))
                || url.getHost() == null || url.getRawUserInfo() != null || url.getRawQuery() != null
                || url.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "quarkus.ipfs.kubo.api-url must be an HTTP(S) base URL without credentials, query or fragment");
        }
        timeout = kubo.timeout();
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("quarkus.ipfs.kubo.timeout must be positive");
        }
        bearerToken = kubo.bearerToken();
        if (bearerToken.filter(token -> !token.matches("[a-zA-Z0-9._~+/-]+=*")).isPresent()) {
            throw new IllegalArgumentException("quarkus.ipfs.kubo.bearer-token has invalid characters");
        }
        blockEndpoint = url.toASCIIString().replaceFirst("/+$", "") + "/api/v0/block/get?arg=";
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Fetch a raw block by CID with Kubo's {@code block/get} RPC.
     * Invalid CIDs fail with {@link IllegalArgumentException}, missing blocks with {@link NoSuchElementException},
     * and other HTTP errors with {@link KuboRequestException}.
     */
    public CompletableFuture<byte[]> getBlock(String cidString) {
        Cid cid;
        try {
            cid = Cid.decode(cidString);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid IPFS CID", failure));
        }
        var request = HttpRequest.newBuilder(URI.create(blockEndpoint + URLEncoder.encode(cid.toString(), UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody());
        bearerToken.ifPresent(token -> request.header("Authorization", "Bearer " + token));
        var response = http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        var result = response.thenApply(reply -> readBlock(cid, reply)).orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException || result.isCancelled()) {
                response.cancel(true);
            }
        });
        return result;
    }

    private static byte[] readBlock(Cid cid, HttpResponse<byte[]> reply) {
        if (reply.statusCode() == 200) {
            return reply.body();
        }
        if (reply.statusCode() == 500 && new String(reply.body(), UTF_8).contains("ipld: could not find " + cid)) {
            throw new NoSuchElementException("IPFS block not found: " + cid);
        }
        throw new KuboRequestException(reply.statusCode());
    }

    @PreDestroy
    void shutdown() {
        http.shutdownNow();
    }

    /** A Kubo RPC server returned an unsuccessful HTTP response. */
    public static final class KuboRequestException extends RuntimeException {
        private final int statusCode;

        private KuboRequestException(int statusCode) {
            super("Kubo block/get returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
