package org.acme;

import java.net.InetAddress;
import java.net.ServerSocket;

import org.peergos.EmbeddedIpfs;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class KuboReadinessApp implements QuarkusApplication {
    @Override
    public int run(String... args) throws Exception {
        if (Arc.container().instance(EmbeddedIpfs.class).isAvailable()) {
            throw new IllegalStateException("The embedded IPFS node must be disabled");
        }
        try (var listener = new ServerSocket(4001, 1, InetAddress.getByName("127.0.0.1"))) {
            System.out.printf("Kubo readiness consumer started without an embedded node (swarm port %d available).%n",
                    listener.getLocalPort());
            Quarkus.waitForExit();
        }
        return 0;
    }
}
