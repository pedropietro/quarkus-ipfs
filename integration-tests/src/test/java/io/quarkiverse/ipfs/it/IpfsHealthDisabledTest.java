package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

@QuarkusTest
@TestProfile(IpfsHealthDisabledTest.HealthDisabled.class)
class IpfsHealthDisabledTest {

    @Test
    void startsTheNodeWithoutRegisteringItsReadinessCheck() {
        given().when().get("/ipfs/node/peer-id").then()
                .statusCode(200)
                .body(not(emptyString()));
        given().when().get("/q/health/ready").then()
                .statusCode(200)
                .body("status", is("UP"))
                .body("checks.name", not(hasItem("ipfs")));
    }

    public static class HealthDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.ipfs.health-enabled", "false");
        }
    }
}
