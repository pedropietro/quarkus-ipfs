package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.ipfs.cid.Cid;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
public class UnixFsResourceTest {

    @Test
    public void readsEmptySingleChunkAndMultiBlockFiles() {
        for (int size : new int[] { 0, 256 * 1024, 256 * 1024 + 123 }) {
            byte[] bytes = contents(size);
            String cid = addFile(bytes);
            assertEquals(size <= 256 * 1024 ? Cid.Codec.Raw : Cid.Codec.DagProtobuf, Cid.decode(cid).codec);
            byte[] read = given().get("/unixfs/file/{cid}", cid).then().statusCode(200).extract().asByteArray();
            assertArrayEquals(bytes, read);
        }
    }

    @Test
    public void producesTheSameDirectoryCidForBothInsertionOrders() {
        String file = addFile(contents(5));
        String first = given().post("/unixfs/directory/{cid}", file).then().statusCode(200).extract().asString();
        String second = given().queryParam("reverse", true)
                .post("/unixfs/directory/{cid}", file).then().statusCode(200).extract().asString();
        assertEquals(first, second);
        given().get("/unixfs/directory/{cid}", first).then().statusCode(200).body(is("cópia.bin\npayload.bin"));
    }

    private static String addFile(byte[] bytes) {
        return given().contentType("application/octet-stream").body(bytes)
                .post("/unixfs/file").then().statusCode(200).extract().asString();
    }

    static byte[] contents(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (index % 251);
        }
        return bytes;
    }
}
