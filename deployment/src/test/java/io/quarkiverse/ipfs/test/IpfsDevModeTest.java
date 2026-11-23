package io.quarkiverse.ipfs.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusDevModeTest;

class IpfsDevModeTest {

    private static final Set<String> WORKERS = Set.of("Kademlia bootstrap", "CidReprovider", "NewCidProvider",
            "autonat-v2-client");
    private static final Pattern PEER_ID = Pattern.compile("\"peerId\"\\s*:\\s*\"([^\"]+)\"");
    private static final String PROPERTIES = "application.properties";
    private static final int HTTP_PORT = availablePort();
    private static final int DEV_PORT = availablePort();
    private static final int EXPLICIT_PORT = availablePort();

    @RegisterExtension
    static final QuarkusDevModeTest DEV = new QuarkusDevModeTest()
            .setForcedDependencies(List.of(
                    Dependency.of("io.quarkus", "quarkus-smallrye-health", System.getProperty("ipfs.test.quarkus-version"))))
            .withApplicationRoot(jar -> jar.addAsResource(new StringAsset("""
                    quarkus.http.port=%d
                    quarkus.ipfs.devservices.port=%d
                    quarkus.ipfs.provide-blocks=true
                    """.formatted(HTTP_PORT, DEV_PORT)), PROPERTIES));

    @Test
    void reloadsOneNodeAndReleasesItsPorts() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            String first = readiness(client);
            assertListensOn(first, DEV_PORT);
            assertWorkers(1);

            DEV.modifyResourceFile(PROPERTIES, properties -> properties + "\nquarkus.log.level=INFO\n");
            String second = readiness(client);
            assertListensOn(second, DEV_PORT);
            assertNotEquals(peerId(first), peerId(second));
            assertWorkers(1);

            DEV.modifyResourceFile(PROPERTIES, properties -> properties
                    + "\nquarkus.ipfs.swarm-addresses=/ip4/127.0.0.1/tcp/" + EXPLICIT_PORT + "\n");
            assertListensOn(readiness(client), EXPLICIT_PORT);
            assertPortReleased(DEV_PORT);
            assertWorkers(1);

            DEV.modifyResourceFile(PROPERTIES,
                    properties -> properties.replaceAll("(?m)^quarkus.ipfs.swarm-addresses=.*$", "")
                            + "\nquarkus.devservices.enabled=false\n");
            assertRegularNode(readiness(client));
            assertPortReleased(EXPLICIT_PORT);

            DEV.modifyResourceFile(PROPERTIES,
                    properties -> properties.replace("quarkus.devservices.enabled=false",
                            "quarkus.ipfs.devservices.enabled=false"));
            assertRegularNode(readiness(client));
            assertWorkers(1);

            try (var occupied = new ServerSocket()) {
                occupied.bind(new InetSocketAddress("127.0.0.1", EXPLICIT_PORT));
                DEV.modifyResourceFile(PROPERTIES, properties -> properties + "\nquarkus.ipfs.swarm-addresses="
                        + "/ip4/127.0.0.1/tcp/" + DEV_PORT + ",/ip4/127.0.0.1/tcp/" + EXPLICIT_PORT + "\n");
                assertEquals(500, healthResponse(client).statusCode());
                assertPortReleased(DEV_PORT);
                assertWorkers(0);
            }
            DEV.modifyResourceFile(PROPERTIES,
                    properties -> properties.replace(",/ip4/127.0.0.1/tcp/" + EXPLICIT_PORT, ""));
            assertListensOn(readiness(client), DEV_PORT);
            assertWorkers(1);

            DEV.modifyResourceFile(PROPERTIES, properties -> properties + "\nquarkus.ipfs.enabled=false\n");
            assertFalse(readiness(client).contains("\"peerId\""));
            assertPortReleased(DEV_PORT);
            assertWorkers(0);
        }
    }

    private static String readiness(HttpClient client) throws Exception {
        var response = healthResponse(client);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private static HttpResponse<String> healthResponse(HttpClient client) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + HTTP_PORT + "/q/health/ready"))
                .timeout(Duration.ofSeconds(30)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String peerId(String response) {
        var match = PEER_ID.matcher(response);
        assertTrue(match.find(), response);
        return match.group(1);
    }

    private static void assertRegularNode(String response) throws IOException {
        peerId(response);
        assertFalse(response.contains("/tcp/" + DEV_PORT + "/"), response);
        assertPortReleased(DEV_PORT);
    }

    private static void assertListensOn(String response, int port) {
        assertTrue(response.contains("/tcp/" + port + "/"), response);
    }

    private static void assertWorkers(long expected) {
        var threads = Thread.getAllStackTraces().keySet();
        for (String name : WORKERS) {
            assertEquals(expected, threads.stream().filter(t -> t.getName().equals(name)).count(), name);
        }
    }

    private static void assertPortReleased(int port) throws IOException {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }

    private static int availablePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
