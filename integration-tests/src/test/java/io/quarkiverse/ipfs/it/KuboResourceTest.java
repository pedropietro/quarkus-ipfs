package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    public static class Rpc implements QuarkusTestResourceLifecycleManager {
        private HttpServer server;
        private ExecutorService executor;
        private final CountDownLatch stop = new CountDownLatch(1);

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
            server.start();
            return Map.of("quarkus.ipfs.kubo.api-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy/",
                    "quarkus.ipfs.kubo.bearer-token", "test-token",
                    "quarkus.ipfs.kubo.timeout", "1s");
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
