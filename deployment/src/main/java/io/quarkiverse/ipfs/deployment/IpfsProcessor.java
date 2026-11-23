package io.quarkiverse.ipfs.deployment;

import java.util.List;

import jakarta.inject.Singleton;

import org.jboss.jandex.DotName;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.quarkiverse.ipfs.runtime.IpfsClient;
import io.quarkiverse.ipfs.runtime.IpfsNodeInfo;
import io.quarkiverse.ipfs.runtime.IpfsRecorder;
import io.quarkiverse.ipfs.runtime.IpnsService;
import io.quarkiverse.ipfs.runtime.SwarmService;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.config.IpfsDevServicesConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.runtime.LaunchMode;

class IpfsProcessor {

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("ipfs");
    }

    @BuildStep
    AdditionalBeanBuildItem additionalBeans(IpfsBuildTimeConfig config) {
        if (!config.enabled()) {
            return null;
        }
        return AdditionalBeanBuildItem.builder()
                .setUnremovable()
                .setDefaultScope(DotName.createSimple("jakarta.enterprise.context.ApplicationScoped"))
                .addBeanClasses(IpfsClient.class, BlockService.class, DagService.class, IpnsService.class, SwarmService.class)
                .build();
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    List<SyntheticBeanBuildItem> produceIpfsBeans(
            IpfsBuildTimeConfig config,
            IpfsDevServicesConfig ipfsDevServices,
            DevServicesConfig devServices,
            LaunchModeBuildItem launchMode,
            IpfsRecorder recorder,
            ShutdownContextBuildItem shutdown) {
        if (!config.enabled()) {
            return List.of();
        }
        int defaultPort = launchMode.getLaunchMode() != LaunchMode.NORMAL && devServices.enabled() && ipfsDevServices.enabled()
                ? ipfsDevServices.port()
                : 0;
        var node = recorder.start(defaultPort, shutdown);
        return List.of(
                SyntheticBeanBuildItem.configure(EmbeddedIpfs.class)
                        .scope(Singleton.class).unremovable().runtimeValue(node).setRuntimeInit().done(),
                SyntheticBeanBuildItem.configure(IpfsNodeInfo.class)
                        .scope(Singleton.class).unremovable().runtimeValue(recorder.nodeInfo(node)).setRuntimeInit().done());
    }
}
