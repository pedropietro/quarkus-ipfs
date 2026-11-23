package io.quarkiverse.ipfs.deployment;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Singleton;

import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.deployment.devservices.IpfsDevServicesBuildItem;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.quarkiverse.ipfs.runtime.IpfsClient;
import io.quarkiverse.ipfs.runtime.IpfsNodeInfo;
import io.quarkiverse.ipfs.runtime.IpfsRecorder;
import io.quarkiverse.ipfs.runtime.IpnsService;
import io.quarkiverse.ipfs.runtime.SwarmService;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.config.IpfsRuntimeConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.runtime.RuntimeValue;

class IpfsProcessor {

    private static final String FEATURE = "ipfs";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    UnremovableBeanBuildItem unremovableRuntimeConfig() {
        return UnremovableBeanBuildItem.beanTypes(IpfsRuntimeConfig.class);
    }

    @BuildStep
    AdditionalBeanBuildItem additionalBeans(IpfsBuildTimeConfig buildTimeConfig) {
        if (!buildTimeConfig.enabled()) {
            return AdditionalBeanBuildItem.unremovableOf(IpfsClient.class);
        }
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .addBeanClasses(
                        IpfsClient.class,
                        BlockService.class,
                        DagService.class,
                        IpnsService.class,
                        SwarmService.class)
                .build();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    List<SyntheticBeanBuildItem> produceIpfsBeans(
            IpfsBuildTimeConfig buildTimeConfig,
            IpfsRecorder recorder,
            ShutdownContextBuildItem shutdownContextBuildItem,
            Optional<IpfsDevServicesBuildItem> devServicesBuildItem) {

        if (!buildTimeConfig.enabled()) {
            return List.of();
        }

        RuntimeValue<IpfsNodeInfo> nodeInfoValue;
        RuntimeValue<EmbeddedIpfs> ipfsValue;

        if (devServicesBuildItem.isPresent()) {
            // Dev Services started the node at RUNTIME_INIT — reuse its RuntimeValues
            nodeInfoValue = devServicesBuildItem.get().getNodeInfoValue();
            ipfsValue = devServicesBuildItem.get().getIpfsValue();
        } else {
            // Normal mode (or dev services disabled): start node via the main recorder
            nodeInfoValue = recorder.createNodeInfo();
            ipfsValue = recorder.createEmbeddedIpfs(
                    nodeInfoValue,
                    buildTimeConfig,
                    shutdownContextBuildItem);
        }

        return List.of(
                SyntheticBeanBuildItem
                        .configure(IpfsNodeInfo.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .runtimeValue(nodeInfoValue)
                        .setRuntimeInit()
                        .done(),
                SyntheticBeanBuildItem
                        .configure(EmbeddedIpfs.class)
                        .scope(Singleton.class)
                        .unremovable()
                        .runtimeValue(ipfsValue)
                        .setRuntimeInit()
                        .done());
    }
}
