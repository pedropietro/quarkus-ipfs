package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class IpfsResourceTest {

    @Test
    public void helloEndpoint() {
        given().when().get("/ipfs").then().statusCode(200).body(is("Hello ipfs"));
    }

    @Test
    public void peerIdIsAvailable() {
        given().when().get("/ipfs/node/peer-id").then().statusCode(200).body(not(emptyString()));
    }

    @Test
    public void storesReadsListsAndRemovesBlock() {
        byte[] data = ("hello ipfs block " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        String cid = given().contentType("application/octet-stream").body(data)
                .when().post("/ipfs/block").then().statusCode(200).body(not(emptyString())).extract().asString();
        try {
            given().when().get("/ipfs/block/{cid}/has", cid).then().statusCode(200).body(is("true"));
            byte[] result = given().when().get("/ipfs/block/{cid}", cid)
                    .then().statusCode(200).extract().asByteArray();
            assertArrayEquals(data, result);
            given().when().get("/ipfs/block/list").then().statusCode(200).body(containsString(cid));
        } finally {
            given().when().delete("/ipfs/block/{cid}", cid).then().statusCode(204);
        }
        given().when().get("/ipfs/block/{cid}/has", cid).then().statusCode(200).body(is("false"));
        given().when().get("/ipfs/block/{cid}", cid).then().statusCode(404);
    }

    @Test
    public void rejectsInvalidCid() {
        given().when().get("/ipfs/block/{cid}", "invalid-cid").then().statusCode(400);
    }

    @Test
    public void reportsReadiness() {
        given().when().get("/q/health/ready").then().statusCode(200)
                .body(containsString("\"status\": \"UP\"")).body(containsString("ipfs"));
    }
}
