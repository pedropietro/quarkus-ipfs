package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.ipfs.cid.Cid;
import io.ipfs.multihash.Multihash;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@QuarkusTestResource(value = KuboResourceTest.Rpc.class, restrictToAnnotatedClass = true)
public class KuboResourceTest {
    static final byte[] PAYLOAD = { 0, 1, 127, (byte) 0x80, (byte) 0xff };
    static final String OK = cid((byte) 1);
    static final String MISSING = cid((byte) 2);
    private static final String ERROR = cid((byte) 3);
    static final String SLOW = cid((byte) 4);
    static final String TRUNCATED = cid((byte) 5);
    static final String UNAUTHORIZED = cid((byte) 6);
    private static final String CANCEL = cid((byte) 7);
    private static final String BLOCKED_TIMEOUT = cid((byte) 8);
    private static final String BODY_TIMEOUT = cid((byte) 9);
    private static final String WRITE_FAILURE = cid((byte) 10);
    private static final String HEADER_TIMEOUT = cid((byte) 11);

    Rpc rpc;

    @Test
    void importsOneFileAndLeavesTheSourceOpen() {
        assertUpload(UploadReply.CID, "read", "cid:" + OK + ":false");
    }

    @Test
    void propagatesUploadAuthorizationFailure() {
        assertUpload(UploadReply.UNAUTHORIZED, "read", "http:401:false");
    }

    @Test
    void failsWhenTheUploadSourceFails() {
        assertUpload(UploadReply.CID, "fail", "io-error:false");
    }

    @Test
    void timesOutWhenTheUploadResponseStalls() {
        assertUpload(UploadReply.SLOW, "read", "timeout:false");
    }

    @Test
    void rejectsInvalidOrOversizedAddResponses() {
        assertUpload(UploadReply.INVALID, "read", "io-error:false");
        assertUpload(UploadReply.TOO_LARGE, "read", "io-error:false");
    }

    private void assertUpload(UploadReply reply, String mode, String outcome) {
        rpc.uploadReply = reply;
        given().get("/kubo/test-upload/{mode}", mode).then().statusCode(200).body(is(outcome));
    }

    private enum UploadReply {
        CID,
        UNAUTHORIZED,
        SLOW,
        INVALID,
        TOO_LARGE
    }

    @Test
    void cancellationClosesTheResponseWhileTheDestinationIsStillBlocked() throws Exception {
        assertInterruptedTransfer("cancel", CANCEL, "cancelled:1:false:true");
    }

    @Test
    void timeoutClosesTheResponseWhileTheDestinationIsStillBlocked() throws Exception {
        assertInterruptedTransfer("blocked", BLOCKED_TIMEOUT, "timeout:1:false:true");
    }

    @Test
    void timeoutClosesAStalledResponseBody() throws Exception {
        assertInterruptedTransfer("read", BODY_TIMEOUT, "timeout:1:false:false");
    }

    @Test
    void timeoutClosesTheExchangeBeforeResponseHeaders() throws Exception {
        assertInterruptedTransfer("read", HEADER_TIMEOUT, "timeout:0:false:false");
    }

    @Test
    void destinationFailureClosesTheResponseAndPreservesTheCause() throws Exception {
        assertInterruptedTransfer("fail", WRITE_FAILURE, "destination-error:0:false:false");
    }

    @Test
    void truncatedChunkedResponseFailsAndLeavesPartialOutput() {
        given().get("/kubo/test-transfer/read/{cid}", TRUNCATED)
                .then().statusCode(200).body(is("io-error:1:false:false"));
    }

    private void assertInterruptedTransfer(String mode, String cid, String outcome) throws Exception {
        given().get("/kubo/test-transfer/{mode}/{cid}", mode, cid)
                .then().statusCode(200).body(is(outcome));
        var probe = rpc.probes.get("arg=" + cid);
        probe.verifyDisconnect.countDown();
        assertTrue(probe.disconnected.get(4, TimeUnit.SECONDS), "Kubo still had an open HTTP response");
    }

    @Test
    void returnsExactBytesUsingPostAndBearerAuthentication() {
        byte[] bytes = given().get("/kubo/block/{cid}", OK).then().statusCode(200).extract().asByteArray();
        assertArrayEquals(PAYLOAD, bytes);
    }

    @Test
    void distinguishesAnInvalidCidMissingBlockAndOtherRpcError() {
        given().get("/kubo/block/{cid}", "invalid-cid").then().statusCode(400);
        given().get("/kubo/block/{cid}", MISSING).then().statusCode(404);
        given().get("/kubo/block/{cid}", ERROR).then().statusCode(500);
    }

    @Test
    void timesOutWhenTheResponseBodyStalls() {
        given().get("/kubo/block/{cid}", SLOW).then().statusCode(504);
    }

    private static String cid(byte firstByte) {
        var hash = new byte[32];
        hash[0] = firstByte;
        return Cid.buildCidV1(Cid.Codec.Raw, Multihash.Type.sha2_256, hash).toString();
    }

    private static final class TransferProbe {
        private final CountDownLatch verifyDisconnect = new CountDownLatch(1);
        private final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
        private final boolean stallHeaders;

        TransferProbe(boolean stallHeaders) {
            this.stallHeaders = stallHeaders;
        }

        void serve(HttpExchange exchange) {
            try {
                if (stallHeaders && !verifyDisconnect.await(10, TimeUnit.SECONDS)) {
                    return;
                }
                exchange.getResponseHeaders().set("X-Content-Length", Integer.toString(16 * 1024 * 1024));
                exchange.sendResponseHeaders(200, 0);
                var body = exchange.getResponseBody();
                body.write(PAYLOAD[0]);
                body.flush();
                if (!verifyDisconnect.await(10, TimeUnit.SECONDS)) {
                    return;
                }
                // A single write can succeed after the peer closes, so keep probing the connection.
                var probe = new byte[64 * 1024];
                for (int i = 0; i < 128; i++) {
                    body.write(probe);
                    body.flush();
                }
                disconnected.complete(false);
            } catch (IOException expected) {
                disconnected.complete(true);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                disconnected.completeExceptionally(failure);
            }
        }
    }

    public static class Rpc implements QuarkusTestResourceLifecycleManager {
        private HttpServer server;
        private volatile UploadReply uploadReply;
        private ExecutorService executor;
        private final CountDownLatch stop = new CountDownLatch(1);
        private final Map<String, TransferProbe> probes = Map.of(
                "arg=" + CANCEL, new TransferProbe(false),
                "arg=" + BLOCKED_TIMEOUT, new TransferProbe(false),
                "arg=" + BODY_TIMEOUT, new TransferProbe(false),
                "arg=" + WRITE_FAILURE, new TransferProbe(false),
                "arg=" + HEADER_TIMEOUT, new TransferProbe(true));

        @Override
        public void inject(TestInjector injector) {
            injector.injectIntoFields(this, new TestInjector.MatchesType(Rpc.class));
        }

        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException error) {
                throw new IllegalStateException("Cannot start Kubo RPC fixture", error);
            }
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/proxy/api/v0/block/get", this::handle);
            server.createContext("/proxy/api/v0/cat", this::handle);
            server.createContext("/proxy/api/v0/add", this::handle);
            server.start();
            return Map.of("quarkus.ipfs.kubo.api-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy/",
                    "quarkus.ipfs.kubo.bearer-token", "test-token",
                    "quarkus.ipfs.kubo.timeout", "2s");
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                String query = exchange.getRequestURI().getRawQuery();
                if (exchange.getRequestURI().getPath().endsWith("/add")) {
                    addFile(exchange);
                    return;
                }
                var probe = probes.get(query);
                if (probe != null) {
                    probe.serve(exchange);
                    return;
                }
                if (("arg=" + UNAUTHORIZED).equals(query)) {
                    exchange.sendResponseHeaders(401, -1);
                    return;
                }
                if (("arg=" + TRUNCATED).equals(query)) {
                    exchange.getResponseHeaders().set("X-Content-Length", Integer.toString(PAYLOAD.length));
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(PAYLOAD[0]);
                    return;
                }
                if (("arg=" + SLOW).equals(query)) {
                    exchange.sendResponseHeaders(200, PAYLOAD.length);
                    exchange.getResponseBody().write(PAYLOAD[0]);
                    exchange.getResponseBody().flush();
                    try {
                        stop.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }
                if (("arg=" + OK).equals(query)) {
                    respond(exchange, 200, PAYLOAD);
                } else if (("arg=" + MISSING).equals(query)) {
                    respond(exchange, 500, ("{\"Message\":\"ipld: could not find " + MISSING + "\"}").getBytes(UTF_8));
                } else if (("arg=" + ERROR).equals(query)) {
                    respond(exchange, 500, "{\"Message\":\"unexpected error\"}".getBytes(UTF_8));
                } else {
                    exchange.sendResponseHeaders(400, -1);
                }
            }
        }

        private void addFile(HttpExchange exchange) throws IOException {
            exchange.getRequestBody().transferTo(OutputStream.nullOutputStream());
            switch (uploadReply) {
                case CID -> respond(exchange, 200, ("{\"Hash\":\"" + OK + "\"}\n").getBytes(UTF_8));
                case UNAUTHORIZED -> exchange.sendResponseHeaders(401, -1);
                case SLOW -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        stop.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                case INVALID -> respond(exchange, 200, "{\"Hash\":\"invalid-cid\"}\n".getBytes(UTF_8));
                case TOO_LARGE -> respond(exchange, 200, new byte[4097]);
            }
        }

        private static void respond(HttpExchange exchange, int status, byte[] data) throws IOException {
            exchange.sendResponseHeaders(status, data.length);
            exchange.getResponseBody().write(data);
        }

        @Override
        public void stop() {
            stop.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
