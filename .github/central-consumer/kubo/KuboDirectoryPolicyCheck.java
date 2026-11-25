package org.acme;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

@Singleton
class KuboDirectoryPolicyCheck implements KuboSmokeTest.DirectoryPolicyCheck {
    private static final String DIRECTORY_CID = "bafybeiczsscdsbs7ffqz55asqdf3smv6klcw3gofszvwlyarci47bgf354";
    private static final String STAT = "/api/v0/files/stat";
    private static final String LS = "/api/v0/ls";
    private static final String DIRECTORY = "{\"Type\":\"directory\"}";
    private static final String EMPTY_LIST = "{\"Objects\":[{\"Links\":[]}]}";

    @Inject
    KuboClient kubo;

    @Inject
    Vertx vertx;

    private volatile Probe active;

    @Override
    public void verify() throws Exception {
        var server = vertx.createHttpServer().requestHandler(this::respond);
        try {
            server.listen(19080, "127.0.0.1").toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            verifySuccess();
            verifyTimeout(new Probe(STAT, 0, 0), 0, "files/stat timeout");
            verifyTimeout(new Probe(LS, 2500, 2500), 1, "shared files/stat + ls timeout");
            verifyCancellation(STAT, 0);
            verifyCancellation(LS, 1);
            verifyFailures();
        } finally {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private void verifySuccess() throws Exception {
        var probe = new Probe(null, 0, 0);
        active = probe;
        if (!kubo.listDirectory(DIRECTORY_CID).get(6, TimeUnit.SECONDS).isEmpty()) {
            throw new IllegalStateException("Expected an empty listing");
        }
        probe.expectCalls(1, 1);
    }

    private void verifyTimeout(Probe probe, int listings, String description) throws Exception {
        active = probe;
        var listing = kubo.listDirectory(DIRECTORY_CID);
        probe.started.get(6, TimeUnit.SECONDS);
        expectFailure(listing, TimeoutException.class);
        probe.closed.get(2, TimeUnit.SECONDS);
        probe.expectCalls(1, listings);
        System.out.println("Kubo directory policy verified " + description + " and closed the active response.");
    }

    private void verifyCancellation(String rpc, int listings) throws Exception {
        var probe = new Probe(rpc, 0, 0);
        active = probe;
        var listing = kubo.listDirectory(DIRECTORY_CID);
        probe.started.get(6, TimeUnit.SECONDS);
        if (!listing.cancel(true) || !listing.isCancelled()) {
            throw new IllegalStateException("Could not cancel " + rpc);
        }
        probe.closed.get(2, TimeUnit.SECONDS);
        probe.expectCalls(1, listings);
        System.out.printf("Kubo directory policy cancelled %s, closed the response, and observed %d ls requests.%n",
                rpc, listings);
    }

    private void verifyFailures() throws Exception {
        var probe = new Probe(null, 0, 0);
        active = probe;
        expectFailure(kubo.listDirectory("invalid-cid"), IllegalArgumentException.class);
        probe.expectCalls(0, 0);
    }

    private void respond(HttpServerRequest request) {
        if (request.method() != HttpMethod.POST || !(STAT.equals(request.path()) || LS.equals(request.path()))
                || !"Bearer central-consumer-test".equals(request.getHeader("Authorization"))) {
            request.response().setStatusCode(403).end();
            return;
        }

        var probe = active;
        boolean stat = STAT.equals(request.path());
        (stat ? probe.statCalls : probe.listCalls).incrementAndGet();
        String body = stat ? DIRECTORY : EMPTY_LIST;
        var response = request.response().putHeader("Content-Type", "application/json");
        if (request.path().equals(probe.stallAt)) {
            sendPartialResponse(probe, response, body);
        } else if (stat && probe.statDelay > 0) {
            vertx.setTimer(probe.statDelay, ignored -> response.end(body));
        } else {
            response.end(body);
        }
    }

    private void sendPartialResponse(Probe probe, HttpServerResponse response, String body) {
        response.closeHandler(ignored -> probe.closed.complete(null));
        response.setChunked(true).write(body.substring(0, 1)).onComplete(written -> {
            if (written.failed()) {
                probe.started.completeExceptionally(written.cause());
                return;
            }
            probe.started.complete(null);
            if (probe.listDelay > 0) {
                vertx.setTimer(probe.listDelay, ignored -> {
                    if (!response.closed()) {
                        response.end(body.substring(1));
                    }
                });
            }
        });
    }

    private static void expectFailure(CompletableFuture<?> future, Class<? extends Throwable> type) throws Exception {
        try {
            future.get(6, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (type.isInstance(failure.getCause())) {
                return;
            }
            throw new IllegalStateException("Expected " + type.getSimpleName(), failure);
        }
        throw new IllegalStateException("Expected listing to fail with " + type.getSimpleName());
    }

    private static final class Probe {
        final String stallAt;
        final long statDelay;
        final long listDelay;
        final AtomicInteger statCalls = new AtomicInteger();
        final AtomicInteger listCalls = new AtomicInteger();
        final CompletableFuture<Void> started = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();

        Probe(String stallAt, long statDelay, long listDelay) {
            this.stallAt = stallAt;
            this.statDelay = statDelay;
            this.listDelay = listDelay;
        }

        void expectCalls(int stats, int listings) {
            if (statCalls.get() != stats || listCalls.get() != listings) {
                throw new IllegalStateException("Expected " + stats + " files/stat and " + listings
                        + " ls requests but received " + statCalls.get() + " and " + listCalls.get());
            }
        }
    }
}
