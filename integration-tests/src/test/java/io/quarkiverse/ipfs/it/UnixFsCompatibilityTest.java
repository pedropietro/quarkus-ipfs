package io.quarkiverse.ipfs.it;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletionException;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.google.protobuf.CodedInputStream;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.quarkiverse.ipfs.runtime.DagService.DirectoryEntry;
import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class UnixFsCompatibilityTest {

    private static final int CHUNK_SIZE = 256 * 1024;
    // Frozen DAG-PB bytes and CIDs from the 0.2.0 encoding.
    static final String LEGACY_FILE = "bafybeicqttevwchodivrbpgnxccfevko3lje2eaifsotdsqeu5k4jhksya";
    static final String LEGACY_DIRECTORY = "bafybeifseu67zvop7j6dnizhjanvqelconpplaveqadpvkk4xwmc44ih3m";
    private static final String FIRST_CHUNK = "0155122031a1f9dea0169551092d05e8bf4a446228c8c3eb4c9b713c66adcb7fd53c89be";
    private static final String LAST_CHUNK = "0155122002638e28fcfa132a6f6e9ae64e7457a09145f45d4d90ac574bb552d2f2173531";

    private static final int MAX_LINKS = 174;
    private static final String ZERO_CHUNK_LINK = "122a0a24015512208a39d2abd3999ab73c34db2476849cddf303ce389b35826850f9a700589b4a9018808010";
    private static final String OLD_FLAT_CID = "bafybeig6edlgzdvnfikpksdvx4hlhavi32wbsk25ije7rcojp2razbwt6i";

    static final String LEGACY_FILE_BLOCK = "12260a24" + FIRST_CHUNK + "12260a24" + LAST_CHUNK + "0a06080120fb8010";
    static final String LEGACY_DIRECTORY_BLOCK = "122d0a24" + FIRST_CHUNK + "12057a2e62696e122d0a24" + LAST_CHUNK
            + "1205612e62696e0a020801";

    @Inject
    BlockService blocks;

    @Inject
    DagService dag;

    @Test
    void readsIdentifiableLegacyFileAndDirectoryByTheirExistingCids() {
        byte[] bytes = UnixFsResourceTest.contents(CHUNK_SIZE + 123);
        String first = blocks.put(Arrays.copyOfRange(bytes, 0, CHUNK_SIZE), Cid.Codec.Raw).join().toString();
        String last = blocks.put(Arrays.copyOfRange(bytes, CHUNK_SIZE, bytes.length), Cid.Codec.Raw).join().toString();

        assertEquals(LEGACY_FILE, storeDag(LEGACY_FILE_BLOCK));
        assertEquals(LEGACY_DIRECTORY,
                storeDag(LEGACY_DIRECTORY_BLOCK));

        assertArrayEquals(bytes, dag.getFile(LEGACY_FILE).join());
        var streamed = new ByteArrayOutputStream();
        assertEquals((long) bytes.length, dag.writeFile(LEGACY_FILE, streamed).join());
        assertArrayEquals(bytes, streamed.toByteArray());
        assertEquals(List.of("z.bin", "a.bin"), dag.listDirectory(LEGACY_DIRECTORY).join());
        var entries = dag.listDirectoryEntries(LEGACY_DIRECTORY).join();
        assertEquals(List.of(new DirectoryEntry("z.bin", first), new DirectoryEntry("a.bin", last)), entries);
        assertThrows(UnsupportedOperationException.class, () -> entries.removeFirst());
        assertThrows(CompletionException.class, () -> dag.getFile(LEGACY_DIRECTORY).join());
        assertThrows(CompletionException.class, () -> dag.listDirectory(LEGACY_FILE).join());
        assertInstanceOf(IllegalArgumentException.class, directoryFailure(LEGACY_FILE));
        assertInstanceOf(IllegalArgumentException.class, directoryFailure(first));
    }

    @Test
    void rejectsAnInvalidOrMissingDirectoryCid() {
        assertInstanceOf(IllegalArgumentException.class, directoryFailure("invalid-cid"));
        String missing = blocks.put("missing directory fixture".getBytes(StandardCharsets.UTF_8), Cid.Codec.DagProtobuf)
                .join().toString();
        blocks.remove(missing).join();
        assertInstanceOf(NoSuchElementException.class, directoryFailure(missing));
    }

    private Throwable directoryFailure(String cid) {
        return assertThrows(CompletionException.class, () -> dag.listDirectoryEntries(cid).join()).getCause();
    }

    @Test
    void keepsFlatCidsThroughTheLimitAndWritesNestedNodesAboveIt() throws IOException {
        String below = dag.addFile("zeros", zeroChunks(MAX_LINKS - 1)).join();
        String at = dag.addFile("zeros", zeroChunks(MAX_LINKS)).join();
        assertEquals("bafybeib5bdvsyx46yc3i7y4z2mthp3zv4klvofo3zq5bhxzyw6z7b3hd4e", below);
        assertEquals("bafybeibu6fm3gxo7ap2l2utuetsjyzjr6movrt3llcgoeyabp4r7higice", at);
        assertEquals(MAX_LINKS - 1, links(blocks.get(below).join()).size());
        assertEquals(MAX_LINKS, links(blocks.get(at).join()).size());

        String nested = dag.addFile("zeros", zeroChunks(MAX_LINKS + 1)).join();
        assertEquals(nested, dag.addFile("zeros", zeroChunks(MAX_LINKS + 1)).join());
        assertNotEquals(OLD_FLAT_CID, nested);
        var rootLinks = links(blocks.get(nested).join());
        assertEquals(2, rootLinks.size());
        for (int i = 0; i < rootLinks.size(); i++) {
            var link = rootLinks.get(i);
            assertEquals(Cid.Codec.DagProtobuf, link.cid().codec);
            byte[] node = blocks.get(link.cid().toString()).join();
            int leaves = i == 0 ? MAX_LINKS : 1;
            assertEquals(leaves, links(node).size());
            assertEquals((long) leaves * CHUNK_SIZE + node.length, link.dagSize());
        }
        assertEquals((long) (MAX_LINKS + 1) * CHUNK_SIZE,
                dag.writeFile(nested, OutputStream.nullOutputStream()).join());
    }

    @Test
    void stillReadsAFlatV060RootAboveTheNewLinkLimit() {
        blocks.put(new byte[CHUNK_SIZE], Cid.Codec.Raw).join();
        // The 0.6.0 encoder wrote all 175 raw links in a single root. Retain those exact block bytes.
        String flatBytes = ZERO_CHUNK_LINK.repeat(175) + "0ac3050802188080f015" + "20808010".repeat(175);
        assertEquals(OLD_FLAT_CID, storeDag(flatBytes));
        assertEquals(175L * CHUNK_SIZE, dag.writeFile(OLD_FLAT_CID, OutputStream.nullOutputStream()).join());
    }

    private static InputStream zeroChunks(int count) {
        byte[] zeros = new byte[CHUNK_SIZE];
        var chunks = new ArrayList<InputStream>(count);
        for (int i = 0; i < count; i++) {
            chunks.add(new ByteArrayInputStream(zeros));
        }
        return new SequenceInputStream(Collections.enumeration(chunks));
    }

    private static List<EncodedLink> links(byte[] node) throws IOException {
        var links = new ArrayList<EncodedLink>();
        var fields = CodedInputStream.newInstance(node);
        for (int tag; (tag = fields.readTag()) != 0;) {
            if (tag == 18) {
                var link = CodedInputStream.newInstance(fields.readByteArray());
                assertEquals(10, link.readTag());
                Cid cid = Cid.cast(link.readByteArray());
                assertEquals(24, link.readTag());
                links.add(new EncodedLink(cid, link.readUInt64()));
            } else {
                fields.skipField(tag);
            }
        }
        return links;
    }

    private record EncodedLink(Cid cid, long dagSize) {
    }

    @Test
    void readsInlineDataAndRejectsMalformedNodes() {
        String inline = storeDag("0a0b0802120568656c6c6f1805");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), dag.getFile(inline).join());
        String wrongSize = storeDag("0a0b0802120568656c6c6f1806");
        String truncated = storeDag("0a0b0802");
        assertThrows(CompletionException.class, () -> dag.getFile(wrongSize).join());
        assertThrows(CompletionException.class, () -> dag.getFile(truncated).join());
    }

    @Test
    void preservesWriteFailureAndLeavesStreamOpen() {
        byte[] bytes = UnixFsResourceTest.contents(CHUNK_SIZE + 123);
        String cid = dag.addFile("test.bin", new ByteArrayInputStream(bytes)).join();
        var written = new ByteArrayOutputStream();
        var diskFull = new IOException("disk full");
        var output = new OutputStream() {
            @Override
            public void write(int value) {
                throw new AssertionError("Expected a block write");
            }

            @Override
            public void write(byte[] block, int offset, int length) throws IOException {
                if (written.size() != 0) {
                    throw diskFull;
                }
                written.write(block, offset, length);
            }

            @Override
            public void flush() {
                throw new AssertionError("The caller owns flushing");
            }

            @Override
            public void close() {
                throw new AssertionError("The caller owns closing");
            }
        };

        var failure = assertThrows(CompletionException.class, () -> dag.writeFile(cid, output).join());
        assertSame(diskFull, assertInstanceOf(UncheckedIOException.class, failure.getCause()).getCause());
        assertArrayEquals(Arrays.copyOf(bytes, CHUNK_SIZE), written.toByteArray());
    }

    private String storeDag(String hex) {
        return blocks.put(HexFormat.of().parseHex(hex), Cid.Codec.DagProtobuf).join().toString();
    }
}
