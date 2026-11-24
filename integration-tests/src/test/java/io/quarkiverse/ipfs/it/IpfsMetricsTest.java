package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class IpfsMetricsTest {

    @Test
    public void reportsBlockOutcomesWhenMicrometerIsAvailable() {
        if (!micrometerAvailable()) {
            given().get("/q/metrics").then().statusCode(404);
            given().get("/ipfs/node/peer-id").then().statusCode(200);
            return;
        }

        String before = scrape();
        String cid = given().contentType("application/octet-stream").body("metered block".getBytes(UTF_8))
                .post("/ipfs/block").then().statusCode(200).extract().asString();
        given().get("/ipfs/block/{cid}", cid).then().statusCode(200);
        given().delete("/ipfs/block/{cid}", cid).then().statusCode(204);
        given().get("/ipfs/block/{cid}", cid).then().statusCode(404);

        String after = scrape();
        assertIncrement(before, after, "block_write", "success");
        assertIncrement(before, after, "block_read", "success");
        assertIncrement(before, after, "block_read", "failure");
        assertEquals(8L, after.lines().filter(line -> line.startsWith("ipfs_operations_total{")).count());
        given().get("/q/metrics").then().body(containsString("ipfs_peers_connected "), not(containsString(cid)));
    }

    static boolean micrometerAvailable() {
        return Boolean.getBoolean("ipfs.test.micrometer");
    }

    static String scrape() {
        return given().accept("text/plain").get("/q/metrics").then().statusCode(200).extract().asString();
    }

    static void assertIncrement(String before, String after, String operation, String outcome) {
        assertEquals(counter(before, operation, outcome) + 1, counter(after, operation, outcome));
    }

    private static double counter(String metrics, String operation, String outcome) {
        return metrics.lines().filter(line -> line.startsWith("ipfs_operations_total{"))
                .filter(line -> line.contains("operation=\"" + operation + "\"")
                        && line.contains("outcome=\"" + outcome + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .findFirst().orElseThrow();
    }
}
