package io.quarkiverse.ipfs.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;

/** CDI client for reading raw blocks and UnixFS files from a configured Kubo HTTP RPC server. */
public class KuboClient {
    private final HttpClient http;
    private final ManagedExecutor executor;
    private final String rpcBase;
    private final Duration timeout;
    private final Optional<String> bearerToken;

    @Inject
    KuboClient(IpfsRuntimeConfig config, ManagedExecutor executor) {
        this.executor = executor;
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
        rpcBase = url.toASCIIString().replaceFirst("/+$", "") + "/api/v0/";
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
        var response = http.sendAsync(request("block/get", cid), HttpResponse.BodyHandlers.ofByteArray());
        var result = response.thenApply(reply -> readBlock(cid, reply)).orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException || result.isCancelled()) {
                response.cancel(true);
            }
        });
        return result;
    }

    /**
     * Stream a UnixFS file by CID with Kubo's {@code cat} RPC and return the number of bytes written.
     * The caller owns the destination and must keep it open until the transfer completes. A failed transfer
     * can leave partial data in the destination. The configured timeout covers the entire transfer.
     * A timeout or cancellation interrupts the transfer, so a blocking destination should respond to interruption.
     * I/O errors, including incomplete responses, fail with {@link UncheckedIOException}.
     */
    public CompletableFuture<Long> writeFile(String cidString, OutputStream output) {
        Objects.requireNonNull(output, "output");
        Cid cid;
        try {
            cid = Cid.decode(cidString);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid IPFS CID", failure));
        }

        var result = new CompletableFuture<Long>();
        var transfer = executor.submit(() -> {
            try {
                result.complete(copyFile(cid, output));
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                result.completeExceptionally(failure);
            } catch (IOException failure) {
                result.completeExceptionally(new UncheckedIOException(failure));
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        result.orTimeout(timeout.toNanos(), TimeUnit.NANOSECONDS).whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException || result.isCancelled()) {
                transfer.cancel(true);
            }
        });
        return result;
    }

    private HttpRequest request(String operation, Cid cid) {
        var request = HttpRequest
                .newBuilder(URI.create(rpcBase + operation + "?arg=" + URLEncoder.encode(cid.toString(), UTF_8)))
                .POST(HttpRequest.BodyPublishers.noBody());
        bearerToken.ifPresent(token -> request.header("Authorization", "Bearer " + token));
        return request.build();
    }

    private long copyFile(Cid cid, OutputStream output) throws IOException, InterruptedException {
        var reply = http.send(request("cat", cid), HttpResponse.BodyHandlers.ofInputStream());
        try (var input = reply.body()) {
            if (reply.statusCode() != 200) {
                if (reply.statusCode() == 500
                        && new String(input.readNBytes(4096), UTF_8).contains("ipld: could not find " + cid)) {
                    throw new NoSuchElementException("IPFS file not found: " + cid);
                }
                throw new KuboRequestException("cat", reply.statusCode());
            }

            // Kubo sends its expected file size separately from the HTTP framing for streamed responses.
            long size;
            try {
                size = reply.headers().firstValueAsLong("X-Content-Length")
                        .orElseGet(() -> reply.headers().firstValueAsLong("Content-Length").orElse(-1));
            } catch (NumberFormatException failure) {
                throw new IOException("Kubo cat did not return a valid file size", failure);
            }
            if (size < 0) {
                throw new IOException("Kubo cat did not return a valid file size");
            }
            long written = input.transferTo(output);
            if (written != size) {
                throw new IOException("Kubo cat returned " + written + " bytes, expected " + size);
            }
            return written;
        }
    }

    private static byte[] readBlock(Cid cid, HttpResponse<byte[]> reply) {
        if (reply.statusCode() == 200) {
            return reply.body();
        }
        if (reply.statusCode() == 500 && new String(reply.body(), UTF_8).contains("ipld: could not find " + cid)) {
            throw new NoSuchElementException("IPFS block not found: " + cid);
        }
        throw new KuboRequestException("block/get", reply.statusCode());
    }

    @PreDestroy
    void shutdown() {
        http.shutdownNow();
    }

    /** A Kubo RPC server returned an unsuccessful HTTP response. */
    public static final class KuboRequestException extends RuntimeException {
        private final int statusCode;

        private KuboRequestException(String operation, int statusCode) {
            super("Kubo " + operation + " returned HTTP " + statusCode);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}
