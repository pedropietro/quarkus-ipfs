package io.quarkiverse.ipfs.runtime.health;

import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;
import org.peergos.EmbeddedIpfs;

import io.libp2p.core.multiformats.Multiaddr;

/** Local readiness of the embedded node. Registered by the SmallRye Health build step. */
@Readiness
public class IpfsHealthCheck implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(IpfsHealthCheck.class);
    private static final String NAME = "ipfs";

    @Inject
    EmbeddedIpfs ipfs;

    @Override
    public HealthCheckResponse call() {
        try {
            var addresses = ipfs.node.listenAddresses();
            if (addresses.isEmpty()) {
                return HealthCheckResponse.named(NAME).down().withData("reason", "no-listen-addresses").build();
            }

            return HealthCheckResponse.named(NAME).up()
                    .withData("peerId", ipfs.node.getPeerId().toBase58())
                    .withData("addresses", addresses.stream().map(Multiaddr::toString).collect(Collectors.joining(", ")))
                    .build();
        } catch (RuntimeException failure) {
            LOG.debug("Could not inspect local IPFS readiness", failure);
            return HealthCheckResponse.named(NAME).down().withData("reason", "node-inspection-failed").build();
        }
    }
}
