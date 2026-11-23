package io.quarkiverse.ipfs.runtime;

import java.util.concurrent.atomic.AtomicReference;

import org.peergos.EmbeddedIpfs;

/**
 * Static holder for the embedded IPFS node started by Dev Services.
 * Allows the recorder to reuse an existing Dev Services node instead of starting a new one.
 */
public final class IpfsDevServiceNode {

    private static final AtomicReference<EmbeddedIpfs> NODE = new AtomicReference<>();

    private IpfsDevServiceNode() {
    }

    public static void set(EmbeddedIpfs node) {
        NODE.set(node);
    }

    public static EmbeddedIpfs get() {
        return NODE.get();
    }

    public static boolean isPresent() {
        return NODE.get() != null;
    }

    public static void clear() {
        NODE.set(null);
    }
}
