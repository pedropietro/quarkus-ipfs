package io.quarkiverse.ipfs.runtime.health;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.peergos.EmbeddedIpfs;

import io.libp2p.core.multiformats.Multiaddr;
import io.quarkus.arc.properties.IfBuildProperty;

/**
 * Readiness health check for the embedded IPFS node.
 * Reports UP when the node is running and listening on at least one address.
 * Registered conditionally via {@code quarkus.ipfs.health-enabled=true}.
 */
@IfBuildProperty(name = "quarkus.ipfs.enabled", stringValue = "true", enableIfMissing = true)
@Readiness
@ApplicationScoped
public class IpfsHealthCheck implements HealthCheck {

    @Inject
    EmbeddedIpfs ipfs;

    @Override
    public HealthCheckResponse call() {
        try {
            List<Multiaddr> addrs = ipfs.node.listenAddresses();
            if (addrs != null && !addrs.isEmpty()) {
                return HealthCheckResponse.builder()
                        .name("ipfs")
                        .up()
                        .withData("peerId", ipfs.node.getPeerId().toBase58())
                        .withData("addresses", addrs.stream()
                                .map(Multiaddr::toString)
                                .reduce((a, b) -> a + ", " + b)
                                .orElse(""))
                        .build();
            }
            return HealthCheckResponse.builder()
                    .name("ipfs")
                    .down()
                    .withData("reason", "Node has no listen addresses")
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.builder()
                    .name("ipfs")
                    .down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
