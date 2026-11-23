package io.quarkiverse.ipfs.runtime;

import jakarta.inject.Inject;

import org.peergos.EmbeddedIpfs;

/**
 * Primary injectable facade for application code.
 * Inject this bean or {@link EmbeddedIpfs} directly.
 */
public class IpfsClient {

    @Inject
    EmbeddedIpfs embeddedIpfs;

    public EmbeddedIpfs getNode() {
        return embeddedIpfs;
    }
}
