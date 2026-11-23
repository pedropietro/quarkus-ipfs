package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class IpfsResourceTest {

    private static String storedCid;

    @Test
    @Order(1)
    public void testHelloEndpoint() {
        given()
                .when().get("/ipfs")
                .then()
                .statusCode(200)
                .body(is("Hello ipfs"));
    }

    @Test
    @Order(2)
    public void testPeerIdNotEmpty() {
        given()
                .when().get("/ipfs/node/peer-id")
                .then()
                .statusCode(200)
                .body(not(emptyString()));
    }

    @Test
    @Order(3)
    public void testPutBlock() {
        byte[] data = "hello ipfs block".getBytes();
        storedCid = given()
                .contentType("application/octet-stream")
                .body(data)
                .when().post("/ipfs/block")
                .then()
                .statusCode(200)
                .body(not(emptyString()))
                .extract().asString();
    }

    @Test
    @Order(4)
    public void testHasBlock() {
        if (storedCid == null)
            testPutBlock();
        given()
                .when().get("/ipfs/block/{cid}/has", storedCid)
                .then()
                .statusCode(200)
                .body(is("true"));
    }

    @Test
    @Order(5)
    public void testGetBlock() {
        if (storedCid == null)
            testPutBlock();
        byte[] expected = "hello ipfs block".getBytes();
        byte[] actual = given()
                .when().get("/ipfs/block/{cid}", storedCid)
                .then()
                .statusCode(200)
                .extract().asByteArray();
        assertArrayEquals(expected, actual);
    }

    @Test
    @Order(6)
    public void testListBlocks() {
        if (storedCid == null)
            testPutBlock();
        given()
                .when().get("/ipfs/block/list")
                .then()
                .statusCode(200)
                .body(containsString(storedCid));
    }

    @Test
    @Order(7)
    public void testRemoveBlock() {
        if (storedCid == null)
            testPutBlock();
        given()
                .when().delete("/ipfs/block/{cid}", storedCid)
                .then()
                .statusCode(204);

        given()
                .when().get("/ipfs/block/{cid}/has", storedCid)
                .then()
                .statusCode(200)
                .body(is("false"));
    }

    @Test
    @Order(8)
    public void testHealthReady() {
        given()
                .when().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body(containsString("\"status\": \"UP\""))
                .body(containsString("ipfs"));
    }
}
