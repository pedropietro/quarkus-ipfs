package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.ipfs.cid.Cid;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusIntegrationTest;

@QuarkusIntegrationTest
@QuarkusTestResource(value = IpfsBitswapIT.Provider.class, restrictToAnnotatedClass = true)
public class IpfsBitswapIT {

    private static final byte[] BLOCK = "block fetched over Bitswap by the packaged app".getBytes(UTF_8);

    Cid cid;

    @Test
    void fetchesAndCachesRemoteBlock() {
        String key = cid.toString();
        given().get("/ipfs/block/{cid}/has", key).then().statusCode(200).body(is("false"));
        byte[] result = given().get("/ipfs/block/{cid}", key).then().statusCode(200).extract().asByteArray();
        assertArrayEquals(BLOCK, result);
        given().get("/ipfs/block/{cid}/has", key).then().statusCode(200).body(is("true"));
    }

    public static class Provider implements QuarkusTestResourceLifecycleManager {
        private final IpfsTestPeers peers = new IpfsTestPeers();
        private Cid cid;

        @Override
        public Map<String, String> start() {
            try {
                var peer = peers.start(true);
                cid = peer.blockstore.put(BLOCK, Cid.Codec.Raw).orTimeout(5, TimeUnit.SECONDS).join();
                return Map.of("quarkus.ipfs.run-bitswap", "true",
                        "quarkus.ipfs.bootstrap-addresses", peer.node.listenAddresses().getFirst().toString());
            } catch (RuntimeException | Error failure) {
                peers.close();
                throw failure;
            }
        }

        @Override
        public void inject(TestInjector injector) {
            injector.injectIntoFields(cid, new TestInjector.MatchesType(Cid.class));
        }

        @Override
        public void stop() {
            peers.close();
        }
    }
}
