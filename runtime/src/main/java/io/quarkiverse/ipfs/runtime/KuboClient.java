package io.quarkiverse.ipfs.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import javax.net.ssl.SSLContext;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.logging.Logger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;

/** CDI client for reading raw blocks and transferring UnixFS files through a configured Kubo HTTP RPC server. */
public class KuboClient {
    private static final int MAX_ADD_RESPONSE_BYTES = 4096;
    private static final int MAX_DIRECTORY_RESPONSE_BYTES = 1024 * 1024;
    private static final Duration READINESS_TIMEOUT = Duration.ofSeconds(2);
    private static final Logger LOG = Logger.getLogger(KuboClient.class);

    private final Vertx vertx;
    private final Optional<String> tlsName;
    private final Set<Transport> transports = new HashSet<>();
    private Transport currentTransport;
    private final ManagedExecutor executor;
    private final KuboMetrics metrics;
    private final String rpcBase;
    private final Duration timeout;
    private final Optional<String> bearerToken;

    @Inject
    KuboClient(IpfsRuntimeConfig config, ManagedExecutor executor, Vertx vertx, TlsConfigurationRegistry tlsRegistry,
            KuboMetrics metrics) {
        this.vertx = vertx;
        this.executor = executor;
        this.metrics = metrics;
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
        tlsName = kubo.tlsConfigurationName();
        if (tlsName.isPresent() && !"https".equalsIgnoreCase(url.getScheme())) {
            throw new IllegalArgumentException("quarkus.ipfs.kubo.tls-configuration-name requires an HTTPS API URL");
        }
        var tls = tlsName.map(name -> tlsRegistry.get(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Kubo TLS configuration: " + name)));
        currentTransport = createTransport(tls);
        transports.add(currentTransport);
    }

    private Transport createTransport(Optional<TlsConfiguration> configuration) {
        var blocks = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        var streams = new HttpClientOptions()
                .setConnectTimeout((int) Math.clamp(timeout.toMillis(), 1, Integer.MAX_VALUE))
                .setVerifyHost(true);
        configuration.ifPresent(tls -> {
            String name = tlsName.orElseThrow();
            if (tls.isTrustAll() || !"HTTPS".equalsIgnoreCase(tls.getHostnameVerificationAlgorithm().orElse("HTTPS"))) {
                throw new IllegalArgumentException("Kubo TLS requires a trusted certificate and HTTPS hostname verification");
            }
            var trust = tls.getTrustStoreOptions();
            if (trust == null) {
                throw new IllegalArgumentException("Kubo TLS configuration requires a trust store: " + name);
            }
            try {
                var context = SSLContext.getInstance("TLS");
                context.init(null, trust.getTrustManagerFactory(vertx).getTrustManagers(), null);
                blocks.sslContext(context);
                streams.setTrustOptions(trust);
            } catch (Exception failure) {
                throw new IllegalArgumentException("Cannot configure the Kubo TLS trust store: " + name, failure);
            }
        });
        var http = blocks.build();
        try {
            return new Transport(http, vertx.createHttpClient(streams));
        } catch (RuntimeException failure) {
            http.shutdownNow();
            throw failure;
        }
    }

    synchronized void certificateUpdated(@Observes CertificateUpdatedEvent event) {
        if (currentTransport == null || tlsName.filter(event.name()::equals).isEmpty()) {
            return;
        }

        Transport replacement;
        try {
            replacement = createTransport(Optional.of(event.tlsConfiguration()));
        } catch (RuntimeException failure) {
            LOG.warn("Could not apply the updated Kubo TLS trust store. Keeping the previous configuration.", failure);
            return;
        }
        var previous = currentTransport;
        currentTransport = replacement;
        transports.add(replacement);
        closeIfRetired(previous);
    }

    private synchronized Transport acquireTransport() {
        if (currentTransport == null) {
            throw new IllegalStateException("Kubo client is closed");
        }
        currentTransport.active++;
        return currentTransport;
    }

    private synchronized void releaseTransport(Transport transport) {
        transport.active--;
        closeIfRetired(transport);
    }

    // Rotation stops assigning requests to the previous pool immediately. Close it after its last request finishes.
    private void closeIfRetired(Transport transport) {
        if (transport != currentTransport && transport.active == 0 && transports.remove(transport)) {
            transport.close();
        }
    }

    private static final class Transport {
        private final HttpClient blocks;
        private final io.vertx.core.http.HttpClient streams;
        private int active;

        private Transport(HttpClient blocks, io.vertx.core.http.HttpClient streams) {
            this.blocks = blocks;
            this.streams = streams;
        }

        private void close() {
            streams.close();
            blocks.shutdownNow();
        }
    }

    /**
     * Fetch a raw block by CID with Kubo's {@code block/get} RPC.
     * Invalid CIDs fail with {@link IllegalArgumentException}, missing blocks with {@link NoSuchElementException},
     * and other HTTP errors with {@link KuboRequestException}.
     */
    public CompletableFuture<byte[]> getBlock(String cidString) {
        return metrics.record(KuboMetrics.Operation.BLOCK_GET, metrics.start(), fetchBlock(cidString));
    }

    private CompletableFuture<byte[]> fetchBlock(String cidString) {
        Cid cid;
        try {
            cid = Cid.decode(cidString);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid IPFS CID", failure));
        }
        return send("block/get?arg=" + URLEncoder.encode(cid.toString(), UTF_8), HttpResponse.BodyHandlers.ofByteArray(),
                reply -> readBlock(cid, reply), timeout);
    }

    /** A direct UnixFS directory entry in Kubo's listing order. */
    public record DirectoryEntry(String name, String cid) {
    }

    /**
     * List the direct entries of a UnixFS directory by CID. The returned list is immutable.
     * The Kubo token needs access to both {@code files/stat} and {@code ls}. Each RPC uses the normal request timeout.
     * An invalid CID or a non-directory fails with {@link IllegalArgumentException}, a missing CID with
     * {@link NoSuchElementException}, and other HTTP errors with {@link KuboRequestException}.
     * Malformed responses or listings larger than 1 MiB fail with {@link UncheckedIOException}.
     */
    public CompletableFuture<List<DirectoryEntry>> listDirectory(String cidString) {
        Cid cid;
        try {
            cid = Cid.decode(cidString);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid IPFS CID", failure));
        }
        // Kubo ls also lists raw DAG links for a file, so confirm the root type before interpreting its entries.
        String stat = "files/stat?arg=" + URLEncoder.encode("/ipfs/" + cid, UTF_8) + "&enc=json";
        return transfer(stat, (request, result) -> {
            var metadata = directoryResponse(request, "files/stat", cid, 4096);
            if (!"directory".equals(jsonString(metadata, "Type"))) {
                throw new IllegalArgumentException("Expected a UnixFS directory");
            }
            return null;
        }).thenCompose(ignored -> transfer(
                "ls?arg=" + URLEncoder.encode(cid.toString(), UTF_8) + "&size=false&resolve-type=false&enc=json",
                (request, result) -> directoryEntries(directoryResponse(request, "ls", cid, MAX_DIRECTORY_RESPONSE_BYTES))));
    }

    private static JsonObject directoryResponse(HttpClientRequest request, String operation, Cid cid, int limit)
            throws IOException, InterruptedException {
        var reply = await(request.send().map(StreamResponse::new));
        int status = reply.http.statusCode();
        if (status != 200) {
            if (status == 404 || status == 500
                    && new String(reply.readPrefix(4096), UTF_8).contains("ipld: could not find " + cid)) {
                throw new NoSuchElementException("IPFS directory not found: " + cid);
            }
            throw new KuboRequestException(operation, status);
        }
        byte[] data = reply.readPrefix(limit + 1);
        if (data.length > limit) {
            throw new IOException("Kubo " + operation + " response exceeded " + limit + " bytes");
        }
        reply.checkTrailer(operation);
        try {
            return JsonParser.parseString(new String(data, UTF_8)).getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Kubo " + operation + " response", failure);
        }
    }

    private static List<DirectoryEntry> directoryEntries(JsonObject listing) throws IOException {
        try {
            var objects = listing.getAsJsonArray("Objects");
            if (objects == null || objects.size() != 1) {
                throw new IOException("Expected one directory in the Kubo ls response");
            }
            var links = objects.get(0).getAsJsonObject().getAsJsonArray("Links");
            if (links == null) {
                throw new IOException("Missing links in the Kubo ls response");
            }
            var entries = new ArrayList<DirectoryEntry>(links.size());
            for (var link : links) {
                var entry = link.getAsJsonObject();
                entries.add(new DirectoryEntry(jsonString(entry, "Name"), Cid.decode(jsonString(entry, "Hash")).toString()));
            }
            return List.copyOf(entries);
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Kubo ls response", failure);
        }
    }

    private static String jsonString(JsonObject object, String field) throws IOException {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isEmpty()) {
            throw new IOException("Invalid " + field + " in the Kubo directory response");
        }
        return value.getAsString();
    }

    CompletableFuture<Boolean> checkReadiness() {
        Duration deadline = timeout.compareTo(READINESS_TIMEOUT) < 0 ? timeout : READINESS_TIMEOUT;
        return send("version", HttpResponse.BodyHandlers.discarding(), reply -> reply.statusCode() == 200, deadline);
    }

    private <B, T> CompletableFuture<T> send(String path, HttpResponse.BodyHandler<B> bodyHandler,
            Function<HttpResponse<B>, T> process, Duration deadline) {
        var transport = acquireTransport();
        CompletableFuture<HttpResponse<B>> response;
        try {
            response = transport.blocks.sendAsync(request(path), bodyHandler);
        } catch (RuntimeException failure) {
            releaseTransport(transport);
            throw failure;
        }
        var result = response.thenApply(process).orTimeout(deadline.toNanos(), TimeUnit.NANOSECONDS);
        result.whenComplete((value, failure) -> {
            if (failure instanceof TimeoutException || result.isCancelled()) {
                response.cancel(true);
            }
            releaseTransport(transport);
        });
        return result;
    }

    /**
     * Stream a UnixFS file by CID with Kubo's {@code cat} RPC and return the number of bytes written.
     * The caller owns the destination, including flushing and closing it. A failed or cancelled transfer can leave
     * partial data in the destination. The configured timeout covers the entire transfer.
     * On timeout or cancellation, the client releases the HTTP response and interrupts the transfer. A destination
     * write already in progress can finish after the future completes if the destination does not respond to interruption.
     * I/O errors, incomplete responses and Kubo stream errors fail with {@link UncheckedIOException}.
     */
    public CompletableFuture<Long> writeFile(String cidString, OutputStream output) {
        return metrics.record(KuboMetrics.Operation.CAT, metrics.start(), streamFile(cidString, output));
    }

    private CompletableFuture<Long> streamFile(String cidString, OutputStream output) {
        Objects.requireNonNull(output, "output");
        Cid cid;
        try {
            cid = Cid.decode(cidString);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid IPFS CID", failure));
        }

        return transfer("cat?arg=" + URLEncoder.encode(cid.toString(), UTF_8),
                (request, result) -> copyFile(cid, output, request, result));
    }

    /**
     * Import one UnixFS file through Kubo's {@code add} RPC and return its root CID as a string.
     * Set {@code pin} to ask Kubo to retain the imported file during garbage collection.
     * The caller owns the source and must keep it open while the transfer reads it.
     * The configured timeout covers the entire upload and response. On timeout or cancellation, a source read
     * already in progress can finish later and Kubo may have stored data from the request.
     * HTTP status failures use {@link KuboRequestException}. I/O and Kubo stream errors use {@link UncheckedIOException}.
     */
    public CompletableFuture<String> addFile(InputStream input, boolean pin) {
        Objects.requireNonNull(input, "input");
        return metrics.record(KuboMetrics.Operation.ADD, metrics.start(),
                transfer("add?cid-version=1&pin=" + pin + "&progress=false&enc=json",
                        (request, result) -> uploadFile(input, request, result)));
    }

    @FunctionalInterface
    private interface StreamTransfer<T> {
        T run(HttpClientRequest request, CompletableFuture<T> result) throws IOException, InterruptedException;
    }

    private <T> CompletableFuture<T> transfer(String path, StreamTransfer<T> action) {
        var result = new CompletableFuture<T>();
        var opened = new CompletableFuture<HttpClientRequest>();
        var worker = executor.submit(() -> {
            try {
                var options = new RequestOptions().setAbsoluteURI(rpcBase + path)
                        .setMethod(HttpMethod.POST).setFollowRedirects(false);
                bearerToken.ifPresent(token -> options.addHeader("Authorization", "Bearer " + token));
                T value;
                checkActive(result);
                var transport = acquireTransport();
                try {
                    var request = await(transport.streams.request(options).onSuccess(opened::complete));
                    checkActive(result);
                    value = action.run(request, result);
                } finally {
                    releaseTransport(transport);
                }
                result.complete(value);
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
            if (failure != null) {
                opened.thenAccept(HttpClientRequest::reset);
                if (failure instanceof TimeoutException || result.isCancelled()) {
                    worker.cancel(true);
                }
            }
        });
        return result;
    }

    private HttpRequest request(String path) {
        var request = HttpRequest.newBuilder(URI.create(rpcBase + path)).POST(BodyPublishers.noBody());
        bearerToken.ifPresent(token -> request.header("Authorization", "Bearer " + token));
        return request.build();
    }

    private String uploadFile(InputStream input, HttpClientRequest request, CompletableFuture<String> result)
            throws IOException, InterruptedException {
        String boundary = "quarkus-ipfs-" + UUID.randomUUID();
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"file\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        request.setChunked(true).putHeader("Content-Type", "multipart/form-data; boundary=" + boundary);
        var ending = new AtomicBoolean();
        var response = request.response().map(StreamResponse::new);
        response.onSuccess(reply -> {
            if (reply.http.statusCode() != 200) {
                result.completeExceptionally(new KuboRequestException("add", reply.http.statusCode()));
            } else if (!ending.get()) {
                result.completeExceptionally(new UncheckedIOException(
                        new IOException("Kubo add returned before reading the entire file")));
            }
        });
        await(request.write(Buffer.buffer(preamble)));
        byte[] chunk = new byte[16 * 1024];
        for (;;) {
            checkActive(result);
            int count = input.read(chunk);
            checkActive(result);
            if (count == -1) {
                break;
            }
            await(request.write(Buffer.buffer().appendBytes(chunk, 0, count)));
        }
        ending.set(true);
        await(request.end(Buffer.buffer("\r\n--" + boundary + "--\r\n")));

        var reply = await(response);
        if (reply.http.statusCode() != 200) {
            throw new KuboRequestException("add", reply.http.statusCode());
        }
        byte[] data = reply.readPrefix(MAX_ADD_RESPONSE_BYTES + 1);
        if (data.length > MAX_ADD_RESPONSE_BYTES) {
            throw new IOException("Kubo add response exceeded " + MAX_ADD_RESPONSE_BYTES + " bytes");
        }
        reply.checkTrailer("add");
        return addedCid(data);
    }

    private static <T> T await(Future<T> future) throws IOException, InterruptedException {
        try {
            return future.toCompletionStage().toCompletableFuture().get();
        } catch (ExecutionException failure) {
            throw new IOException("Kubo HTTP exchange failed", failure.getCause());
        }
    }

    private static void checkActive(CompletableFuture<?> result) {
        if (result.isDone()) {
            throw new CancellationException();
        }
    }

    private static String addedCid(byte[] data) throws IOException {
        try {
            var reply = JsonParser.parseString(new String(data, UTF_8)).getAsJsonObject();
            var hash = reply.get("Hash");
            if (hash == null || !hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isString() || reply.has("Message")) {
                throw new IOException("Kubo add response did not contain a file CID");
            }
            return Cid.decode(hash.getAsString()).toString();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid Kubo add response", failure);
        }
    }

    private long copyFile(Cid cid, OutputStream output, HttpClientRequest request, CompletableFuture<Long> result)
            throws IOException, InterruptedException {
        var reply = await(request.send().map(StreamResponse::new));
        int status = reply.http.statusCode();
        if (status != 200) {
            if (status == 500 && new String(reply.readPrefix(4096), UTF_8).contains("ipld: could not find " + cid)) {
                throw new NoSuchElementException("IPFS file not found: " + cid);
            }
            throw new KuboRequestException("cat", status);
        }

        // Kubo sends its expected file size separately from the HTTP framing for streamed responses.
        String length = reply.http.getHeader("X-Content-Length");
        long size;
        try {
            size = Long.parseLong(length == null ? reply.http.getHeader("Content-Length") : length);
        } catch (NumberFormatException failure) {
            throw new IOException("Kubo cat did not return a valid file size", failure);
        }
        if (size < 0) {
            throw new IOException("Kubo cat did not return a valid file size");
        }
        long written = 0;
        for (Buffer chunk; (chunk = reply.next()) != null;) {
            checkActive(result);
            byte[] bytes = chunk.getBytes();
            output.write(bytes);
            written += bytes.length;
        }
        reply.checkTrailer("cat");
        if (written != size) {
            throw new IOException("Kubo cat returned " + written + " bytes, expected " + size);
        }
        return written;
    }

    /** Use a bounded read window while the caller's I/O runs on a worker. */
    private static final class StreamResponse {
        private static final int READ_WINDOW = 16;

        private record Part(Buffer buffer, Throwable failure) {
        }

        private final HttpClientResponse http;
        private final ArrayBlockingQueue<Part> incoming = new ArrayBlockingQueue<>(READ_WINDOW + 1);
        private final AtomicBoolean finished = new AtomicBoolean();

        private StreamResponse(HttpClientResponse http) {
            this.http = http;
            http.pause();
            http.handler(buffer -> incoming.add(new Part(buffer, null)));
            http.exceptionHandler(error -> finish(error));
            http.endHandler(ignored -> finish(null));
            // Allow HTTP framing to advance while the worker consumes an already delivered buffer.
            http.fetch(READ_WINDOW);
        }

        private void finish(Throwable error) {
            if (finished.compareAndSet(false, true)) {
                incoming.add(new Part(null, error));
            }
        }

        private Buffer next() throws IOException, InterruptedException {
            var part = incoming.take();
            if (part.failure() != null) {
                throw new IOException("Kubo response ended prematurely", part.failure());
            }
            if (part.buffer() != null && !finished.get()) {
                http.fetch(1);
            }
            return part.buffer();
        }

        private byte[] readPrefix(int limit) throws IOException, InterruptedException {
            var output = new ByteArrayOutputStream();
            for (Buffer chunk; output.size() < limit && (chunk = next()) != null;) {
                output.writeBytes(chunk.getBytes(0, Math.min(chunk.length(), limit - output.size())));
            }
            return output.toByteArray();
        }

        private void checkTrailer(String operation) throws IOException {
            if (http.trailers().contains("X-Stream-Error")) {
                // The remote message may contain sensitive or application-specific data.
                throw new StreamException(operation);
            }
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
    synchronized void shutdown() {
        currentTransport = null;
        transports.forEach(Transport::close);
        transports.clear();
    }

    static final class StreamException extends IOException {
        private StreamException(String operation) {
            super("Kubo " + operation + " reported an error in X-Stream-Error");
        }
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
