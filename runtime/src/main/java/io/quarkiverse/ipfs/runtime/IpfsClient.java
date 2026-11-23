package io.quarkiverse.ipfs.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;

/**
 * Primary injectable facade for application code.
 * Inject this bean or {@link EmbeddedIpfs} directly.
 */
@ApplicationScoped
public class IpfsClient {

    @Inject
    EmbeddedIpfs embeddedIpfs;

    public EmbeddedIpfs getNode() {
        return embeddedIpfs;
    }
}
