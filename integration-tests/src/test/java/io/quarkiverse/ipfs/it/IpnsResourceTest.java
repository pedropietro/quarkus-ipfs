package io.quarkiverse.ipfs.it;

import static io.quarkiverse.ipfs.it.IpfsMetricsTest.assertIncrement;
import static io.quarkiverse.ipfs.it.IpfsMetricsTest.micrometerAvailable;
import static io.quarkiverse.ipfs.it.IpfsMetricsTest.scrape;
import static io.restassured.RestAssured.given;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peergos.EmbeddedIpfs;
import org.peergos.Hash;
import org.peergos.HostBuilder;
import org.peergos.RamAddressBook;
import org.peergos.protocol.ipns.IPNS;
import org.peergos.protocol.ipns.IpnsRecord;
import org.peergos.protocol.ipns.pb.Ipns;

import com.google.protobuf.ByteString;

import io.ipfs.multihash.Multihash;
import io.libp2p.core.PeerId;
import io.libp2p.core.crypto.PrivKey;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;

@QuarkusTest
public class IpnsResourceTest {

    private static final String PATH = "/ipfs/bafkreihdwdcefgh4dqkjv67uzcmw7ojee6xedzdetojuzjevtenxquvyku/";
    private static final long TTL_NANOS = 3_600_000_000_000L;
    private static final IpfsTestPeers PEERS = new IpfsTestPeers();
    private static EmbeddedIpfs first;
    private static EmbeddedIpfs second;
    private static EmbeddedIpfs third;

    @BeforeEach
    void startPeers() {
        if (first != null) {
            return;
        }
        var localPeer = Multihash.fromBase58(given().get("/ipfs/node/peer-id").then().statusCode(200).extract().asString());
        // Nabu's root bucket opposite its local routing ID contains all three peers.
        int prefix = (localPeer.bareMultihash().toBytes()[0] & 0x80) ^ 0x80;
        first = startPeerInBucket(prefix);
        second = startPeerInBucket(prefix);
        third = startPeerInBucket(prefix);
        connect(first, second);
        connect(third);
    }

    private static EmbeddedIpfs startPeerInBucket(int prefix) {
        for (int attempt = 0; attempt < 128; attempt++) {
            var key = newKey();
            if ((Hash.sha256(PeerId.fromPubKey(key.publicKey()).getBytes())[0] & 0x80) == prefix) {
                return PEERS.start(false, key);
            }
        }
        throw new AssertionError("Could not generate a test peer in the selected DHT bucket");
    }

    @AfterAll
    static void closePeers() {
        PEERS.close();
        first = null;
        second = null;
        third = null;
    }

    @Test
    public void publishesSignedRecordToAnotherPeer() {
        connect(first, second, third);
        String metricsBefore = micrometerAvailable() ? scrape() : null;

        var before = LocalDateTime.now(ZoneOffset.UTC);
        var reply = given().queryParam("sequence", 1).contentType("text/plain").body(PATH + "published")
                .post("/ipns").then().statusCode(200).extract();

        var publisher = Multihash.fromBase58(reply.asString());
        var replicas = Stream.of(first, second, third).flatMap(peer -> peer.records.get(publisher).stream()).toList();
        assertTrue(!replicas.isEmpty());
        assertEquals(replicas.size(), Integer.parseInt(reply.header("X-IPNS-Replicas")));
        for (var replica : replicas) {
            var record = IPNS.parseAndValidateIpnsEntry(IPNS.getKey(publisher), replica.raw).orElseThrow().value;
            assertEquals(PATH + "published", new String(record.value, UTF_8));
            assertEquals(1, record.sequence);
            assertTrue(!record.expiry.isBefore(before.plusHours(1)));
            assertTrue(!record.expiry.isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(1)));
        }
        if (metricsBefore != null) {
            String metricsAfter = scrape();
            assertIncrement(metricsBefore, metricsAfter, "ipns_publish", "success");
            assertTrue(metricsBefore.lines().anyMatch(line -> line.matches("ipfs_peers_connected [1-9][0-9]*\\.0")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void selectsHighestValidSequenceAcrossPeers(boolean winnerOnLastConnectedPeer) throws Exception {
        var key = newKey();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        store(winnerOnLastConnectedPeer ? first : third, key, "older", 2, now.plusHours(2));
        store(winnerOnLastConnectedPeer ? third : first, key, "newer", (1L << 32) + 2, now.plusHours(1));

        // Retain a higher-sequence record with a forged signature on a remote peer.
        var raw = signedRecord(key, "forged", (1L << 32) + 3, now.plusHours(3));
        byte[] forged = Ipns.IpnsEntry.parseFrom(raw).toBuilder()
                .setSignatureV2(ByteString.copyFrom(new byte[64])).build().toByteArray();
        second.records.put(publisher(key), new IpnsRecord(forged, (1L << 32) + 3, TTL_NANOS, now.plusHours(3),
                (PATH + "forged").getBytes(UTF_8)));

        // Even with a target of one record, resolve the whole batch of queried peers.
        String metricsBefore = micrometerAvailable() ? scrape() : null;
        resolve(key, 1).then().statusCode(200).body(is(PATH + "newer"));
        if (metricsBefore != null) {
            assertIncrement(metricsBefore, scrape(), "ipns_resolve", "success");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    public void selectsLaterExpirationWhenSequenceIsEqual(boolean winnerOnLastConnectedPeer) {
        var key = newKey();
        var now = LocalDateTime.now(ZoneOffset.UTC);
        store(winnerOnLastConnectedPeer ? first : third, key, "shorter", 5, now.plusHours(1));
        store(winnerOnLastConnectedPeer ? third : first, key, "longer", 5, now.plusHours(2));

        resolve(key, 2).then().statusCode(200).body(is(PATH + "longer"));
    }

    @Test
    public void resolvesV2OnlyRecordAndRejectsConflictingLegacyValue() throws Exception {
        var key = newKey();
        var expiry = LocalDateTime.now(ZoneOffset.UTC).plusHours(1);
        byte[] value = (PATH + "signed").getBytes(UTF_8);
        var v2 = Ipns.IpnsEntry.parseFrom(signedRecord(key, "signed", 9, expiry)).toBuilder()
                .clearValue().clearSequence().clearTtl().clearValidityType().clearValidity().build();
        connect(first);

        byte[] conflicting = v2.toBuilder().setValue(ByteString.copyFromUtf8(PATH + "unsigned")).build().toByteArray();
        first.records.put(publisher(key), new IpnsRecord(conflicting, 9, TTL_NANOS, expiry, value));
        resolve(key, 1).then().statusCode(404);

        first.records.remove(publisher(key));
        first.records.put(publisher(key), new IpnsRecord(v2.toByteArray(), 9, TTL_NANOS, expiry, value));
        resolve(key, 1).then().statusCode(200).body(is(PATH + "signed"));
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

        String metricsBefore = micrometerAvailable() ? scrape() : null;
        resolve(key, 1).then().statusCode(404).body(is("No valid IPNS records found for: " + publisher(key).toBase58()));
        if (metricsBefore != null) {
            assertIncrement(metricsBefore, scrape(), "ipns_resolve", "failure");
        }
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
}
