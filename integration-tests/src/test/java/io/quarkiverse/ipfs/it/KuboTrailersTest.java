package io.quarkiverse.ipfs.it;

import static io.quarkiverse.ipfs.it.KuboResourceTest.MISSING;
import static io.quarkiverse.ipfs.it.KuboResourceTest.OK;
import static io.quarkiverse.ipfs.it.KuboResourceTest.PAYLOAD;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;

@QuarkusTest
@QuarkusTestResource(value = KuboTrailersTest.Rpc.class, restrictToAnnotatedClass = true)
public class KuboTrailersTest {
    @Test
    void rejectsAValidUploadCidFollowedByAStreamError() {
        given().get("/kubo/test-upload/read").then().statusCode(200).body(is("trailer-error:false"));
    }

    @Test
    void rejectsALateErrorAfterReadingAllAdvertisedBytes() {
        given().get("/kubo/test-transfer/read/{cid}", OK)
                .then().statusCode(200).body(is("trailer-error:" + PAYLOAD.length + ":false:false"));
    }

    @Test
    void acceptsACompleteResponseWithAnUnrelatedTrailer() {
        given().get("/kubo/test-transfer/read/{cid}", MISSING)
                .then().statusCode(200).body(is("success:" + PAYLOAD.length + ":false:false"));
    }

    public static final class Rpc implements QuarkusTestResourceLifecycleManager {
        private Vertx vertx;

        @Override
        public Map<String, String> start() {
            vertx = Vertx.vertx();
            try {
                var server = vertx.createHttpServer().requestHandler(this::respond).listen(0, "127.0.0.1")
                        .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
                return Map.of("quarkus.ipfs.kubo.api-url", "http://127.0.0.1:" + server.actualPort(),
                        "quarkus.ipfs.kubo.bearer-token", "test-token",
                        "quarkus.ipfs.kubo.timeout", "3s");
            } catch (Exception failure) {
                vertx.close();
                throw new IllegalStateException("Cannot start Kubo trailer fixture", failure);
            }
        }

        private void respond(HttpServerRequest request) {
            if (request.method() != HttpMethod.POST || !"Bearer test-token".equals(request.getHeader("Authorization"))) {
                request.response().setStatusCode(401).end();
                return;
            }
            request.body().onSuccess(body -> {
                var response = request.response().setChunked(true);
                if ("/api/v0/add".equals(request.path())) {
                    response.putHeader("Trailer", "X-Stream-Error")
                            .putTrailer("X-Stream-Error", "pin failed after writing CID")
                            .end("{\"Hash\":\"" + OK + "\"}\n");
                } else {
                    boolean error = OK.equals(request.getParam("arg"));
                    String trailer = error ? "x-stream-error" : "X-Trace";
                    response.putHeader("X-Content-Length", Integer.toString(PAYLOAD.length))
                            .putHeader("Trailer", trailer).putTrailer(trailer, "late response")
                            .end(Buffer.buffer(PAYLOAD));
                }
            });
        }

        @Override
        public void stop() {
            vertx.close().toCompletionStage().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        }
    }
}
