package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IpfsMetricsDisabledTest.Disabled.class)
@EnabledIfSystemProperty(named = "ipfs.test.micrometer", matches = "true")
class IpfsMetricsDisabledTest {

    @Test
    void startsTheNodeWithoutRegisteringIpfsMeters() {
        given().get("/ipfs/node/peer-id").then().statusCode(200);
        given().get("/q/metrics").then().statusCode(200)
                .body(not(containsString("ipfs_operations")), not(containsString("ipfs_peers_connected")));
    }

    public static class Disabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.ipfs.metrics-enabled", "false");
        }
    }
}
