package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.HexFormat;

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
            byte[] streamed = given().get("/unixfs/file/{cid}/stream", cid).then().statusCode(200).extract().asByteArray();
            assertArrayEquals(bytes, streamed);
        }
    }

    @Test
    public void readsLegacyFileAndDirectoryByTheirOriginalCids() {
        int chunkSize = 256 * 1024;
        byte[] bytes = contents(chunkSize + 123);
        addFile(Arrays.copyOfRange(bytes, 0, chunkSize));
        addFile(Arrays.copyOfRange(bytes, chunkSize, bytes.length));
        String file = UnixFsCompatibilityTest.LEGACY_FILE;
        String directory = UnixFsCompatibilityTest.LEGACY_DIRECTORY;
        putLegacyBlock(file, UnixFsCompatibilityTest.LEGACY_FILE_BLOCK);
        putLegacyBlock(directory, UnixFsCompatibilityTest.LEGACY_DIRECTORY_BLOCK);

        assertArrayEquals(bytes,
                given().get("/unixfs/file/{cid}", file).then().statusCode(200).extract().asByteArray());
        assertArrayEquals(bytes,
                given().get("/unixfs/file/{cid}/stream", file).then().statusCode(200).extract().asByteArray());
        given().get("/unixfs/directory/{cid}", directory).then().statusCode(200).body(is("z.bin\na.bin"));
    }

    private static void putLegacyBlock(String cid, String block) {
        given().contentType("application/octet-stream").body(HexFormat.of().parseHex(block))
                .post("/unixfs/block/{cid}", cid).then().statusCode(200).body(is(cid));
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
