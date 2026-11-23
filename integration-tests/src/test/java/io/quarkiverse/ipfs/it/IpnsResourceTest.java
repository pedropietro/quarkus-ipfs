package io.quarkiverse.ipfs.it;

import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.peergos.EmbeddedIpfs;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;
import org.peergos.protocol.ipns.IPNS;
import org.peergos.protocol.ipns.IpnsRecord;
import org.peergos.protocol.ipns.pb.Ipns;

import com.google.protobuf.ByteString;

import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import io.quarkiverse.ipfs.runtime.IpfsRecorder;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig.StorageType;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.smallrye.config.SmallRyeConfigBuilder;

@QuarkusTest
public class IpnsResourceTest {

    private static final String PATH = "/ipfs/bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku/";
    private static final long TTL_NANOS = 3_600_000_000_000L;
    private static final PeerShutdown SHUTDOWN = new PeerShutdown();
    private static EmbeddedIpfs first;
    private static EmbeddedIpfs second;
    private static EmbeddedIpfs third;

    @BeforeAll
    static void startPeers() {
        first = startPeer();
        second = startPeer();
        third = startPeer();
    }

    @AfterAll
    static void closePeers() {
        while (!SHUTDOWN.tasks.isEmpty()) {
            SHUTDOWN.tasks.removeFirst().run();
        }
    }

    @Test
    public void publishesSignedRecordToAnotherPeer() {
        connect(first, second, third);

        var before = LocalDateTime.now(ZoneOffset.UTC);
        String name = given().queryParam("sequence", 1).contentType("text/plain").body(PATH + "published")
                .post("/ipns").then().statusCode(200).header("X-IPNS-Replicas", is("3")).extract().asString();

        var publisher = Multihash.fromBase58(name);
        var raw = first.records.get(publisher).orElseThrow().raw;
        var record = IPNS.parseAndValidateIpnsEntry(IPNS.getKey(publisher), raw).orElseThrow().value;
        assertEquals(PATH + "published", new String(record.value, UTF_8));
        assertEquals(1, record.sequence);
        assertTrue(!record.expiry.isBefore(before.plusHours(1)));
        assertTrue(!record.expiry.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
    }

    @Test
    public void selectsHighestValidSequenceAcrossPeers() throws Exception {
        var key = newKey();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        store(first, key, "older", 2, now.plusHours(2));
        store(second, key, "newer", (1L << 32) + 2, now.plusHours(1));

        // Retain a higher-sequence record with a forged signature on a remote peer.
        var raw = signedRecord(key, "forged", (1L << 32) + 3, now.plusHours(3));
        byte[] forged = Ipns.IpnsEntry.parseFrom(raw).toBuilder()
                .setSignatureV2(ByteString.copyFrom(new byte[64])).build().toByteArray();
        third.records.put(publisher(key), new IpnsRecord(forged, (1L << 32) + 3, TTL_NANOS, now.plusHours(3),
                (PATH + "forged").getBytes(UTF_8)));
        connect(first, second, third);

        // Even with a target of one record, resolve the whole batch of queried peers.
        resolve(key, 1).then().statusCode(200).body(is(PATH + "newer"));
    }

    @Test
    public void selectsLaterExpirationWhenSequenceIsEqual() {
        var key = newKey();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        store(first, key, "shorter", 5, now.plusHours(1));
        store(second, key, "longer", 5, now.plusHours(2));
        connect(first, second, third);

        resolve(key, 2).then().statusCode(200).body(is(PATH + "longer"));
    }

    @Test
    public void rejectsExpiredRecordAdvertisedByAnotherPeer() {
        var key = newKey();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        long sequence = 3;
        var raw = signedRecord(key, "expired", sequence, now.minusMinutes(1));
        // Keep the record in the peer's store so the resolver sees its signed, expired EOL on the wire.
        first.records.put(publisher(key), new IpnsRecord(raw, sequence, TTL_NANOS, now.plusHours(1),
                (PATH + "expired").getBytes(UTF_8)));
        connect(first, second, third);

        resolve(key, 1).then().statusCode(404).body(is("No valid IPNS records found for: " + publisher(key).toBase58()));
    }

    private static EmbeddedIpfs startPeer() {
        var config = new SmallRyeConfigBuilder().withMapping(IpfsRuntimeConfig.class)
                .withDefaultValue("quarkus.ipfs.run-bitswap", "false")
                .build().getConfigMapping(IpfsRuntimeConfig.class);
        return new IpfsRecorder(new RuntimeValue<>(config)).start(0, StorageType.RAM, SHUTDOWN).getValue();
    }

    private static void connect(EmbeddedIpfs... peers) {
        var addresses = Stream.of(peers).map(peer -> peer.node.listenAddresses().getFirst().toString())
                .collect(Collectors.joining("\n"));
        given().contentType("text/plain").body(addresses).post("/ipns/peers")
                .then().statusCode(200).body(is(Integer.toString(peers.length)));
    }

    private static Response resolve(PrivKey key, int minResults) {
        return given().queryParam("minResults", minResults).get("/ipns/{name}", publisher(key).toBase58());
    }

    private static PrivKey newKey() {
        return new HostBuilder(new RamAddressBook()).generateIdentity().getPrivateKey();
    }

    private static Multihash publisher(PrivKey key) {
        return Multihash.deserialize(PeerId.fromPubKey(key.publicKey()).getBytes());
    }

    private static byte[] signedRecord(PrivKey key, String value, long sequence, LocalDateTime expiry) {
        return IPNS.createSignedRecord((PATH + value).getBytes(UTF_8), expiry, sequence, TTL_NANOS,
                Optional.empty(), Optional.empty(), key);
    }

    private static void store(EmbeddedIpfs peer, PrivKey key, String value, long sequence, LocalDateTime expiry) {
        var raw = signedRecord(key, value, sequence, expiry);
        peer.records.put(publisher(key), new IpnsRecord(raw, sequence, TTL_NANOS, expiry, (PATH + value).getBytes(UTF_8)));
    }

    private static class PeerShutdown implements ShutdownContext {
        final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void addShutdownTask(Runnable task) {
            tasks.addFirst(task);
        }

        @Override
        public void addLastShutdownTask(Runnable task) {
            tasks.addLast(task);
        }
    }
}
