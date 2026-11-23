package io.quarkiverse.ipfs.deployment.devservices;

import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.IpfsNodeInfo;
import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.runtime.RuntimeValue;

/**
 * Build item produced by {@link IpfsDevServicesProcessor} when dev services is active.
 * Carries the {@link RuntimeValue}s for the dev-services-managed EmbeddedIpfs and IpfsNodeInfo
 * so the main {@link io.quarkiverse.ipfs.deployment.IpfsProcessor} can register them as CDI beans
 * without starting a second node.
 */
public final class IpfsDevServicesBuildItem extends SimpleBuildItem {

    private final RuntimeValue<EmbeddedIpfs> ipfsValue;
    private final RuntimeValue<IpfsNodeInfo> nodeInfoValue;

    public IpfsDevServicesBuildItem(RuntimeValue<EmbeddedIpfs> ipfsValue,
            RuntimeValue<IpfsNodeInfo> nodeInfoValue) {
        this.ipfsValue = ipfsValue;
        this.nodeInfoValue = nodeInfoValue;
    }

    public RuntimeValue<EmbeddedIpfs> getIpfsValue() {
        return ipfsValue;
    }

    public RuntimeValue<IpfsNodeInfo> getNodeInfoValue() {
        return nodeInfoValue;
    }
}
