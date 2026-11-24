package io.quarkiverse.ipfs.it;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.NoSuchElementException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.smallrye.common.annotation.Blocking;

@Path("/kubo")
public class KuboResource {
    @Inject
    Instance<KuboClient> client;

    @GET
    @Blocking
    @Path("/file/{cid}")
    public Response streamFile(@PathParam("cid") String cid) {
        if (client.isUnsatisfied()) {
            return Response.status(503).build();
        }
        var kubo = client.get();
        StreamingOutput body = output -> kubo.writeFile(cid, output).join();
        return Response.ok(body, MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Test-Max-Heap", Runtime.getRuntime().maxMemory()).build();
    }

    @GET
    @Blocking
    @Path("/test-transfer/{mode}/{cid}")
    public String testTransfer(@PathParam("mode") String mode, @PathParam("cid") String cid) throws Exception {
        var output = new TrackingDestination(mode);
        var result = client.get().writeFile(cid, output);
        if ("cancel".equals(mode)) {
            output.firstWrite.get(5, TimeUnit.SECONDS);
            result.cancel(true);
        }
        String outcome;
        try {
            result.join();
            outcome = "success";
        } catch (CancellationException failure) {
            outcome = "cancelled";
        } catch (CompletionException failure) {
            outcome = switch (failure.getCause()) {
                case TimeoutException ignored -> "timeout";
                case UncheckedIOException error -> error.getCause() == output.writeFailure ? "destination-error" : "io-error";
                default -> throw failure;
            };
        }
        return outcome + ":" + output.bytes + ":" + output.closed + ":" + !output.release.isDone();
    }

    private static final class TrackingDestination extends OutputStream {
        private final CompletableFuture<Void> firstWrite = new CompletableFuture<>();
        private final CompletableFuture<Void> release = new CompletableFuture<>();
        private final IOException writeFailure;
        private volatile int bytes;
        private volatile boolean closed;

        TrackingDestination(String mode) {
            writeFailure = "fail".equals(mode) ? new IOException("destination rejected the write") : null;
            if (!"cancel".equals(mode) && !"blocked".equals(mode)) {
                release.complete(null);
            }
        }

        @Override
        public void write(int value) throws IOException {
            if (writeFailure != null) {
                throw writeFailure;
            }
            bytes++;
            firstWrite.complete(null);
            // Model a destination that ignores interruption. Bound the wait even if the test fails.
            release.completeOnTimeout(null, 15, TimeUnit.SECONDS).join();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @GET
    @Path("/block/{cid}")
    public Response getBlock(@PathParam("cid") String cid) throws Exception {
        if (client.isUnsatisfied()) {
            return Response.status(503).build();
        }
        try {
            return Response.ok(client.get().getBlock(cid).get(), MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (ExecutionException failure) {
            return switch (failure.getCause()) {
                case IllegalArgumentException ignored -> Response.status(400).build();
                case NoSuchElementException ignored -> Response.status(404).build();
                case TimeoutException ignored -> Response.status(504).build();
                case KuboClient.KuboRequestException error -> Response.status(error.statusCode()).build();
                default -> throw failure;
            };
        }
    }
}
