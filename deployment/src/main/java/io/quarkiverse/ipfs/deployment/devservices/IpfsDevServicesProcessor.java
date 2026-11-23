package io.quarkiverse.ipfs.deployment.devservices;

import org.jboss.logging.Logger;
import org.peergos.EmbeddedIpfs;

import io.quarkiverse.ipfs.runtime.IpfsDevServicesRecorder;
import io.quarkiverse.ipfs.runtime.IpfsNodeInfo;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkiverse.ipfs.runtime.config.IpfsDevServicesConfig;
import io.quarkus.deployment.IsDevServicesSupportedByLaunchMode;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.dev.devservices.DevServicesConfig;
import io.quarkus.runtime.RuntimeValue;

/**
 * Starts an embedded IPFS node in dev/test mode automatically — no Docker required.
 * The node runs in the application process and follows its lifecycle.
 */
public class IpfsDevServicesProcessor {

    private static final Logger LOG = Logger.getLogger(IpfsDevServicesProcessor.class);

    @BuildStep(onlyIf = { IsDevServicesSupportedByLaunchMode.class, DevServicesConfig.Enabled.class })
    @Record(ExecutionTime.RUNTIME_INIT)
    public IpfsDevServicesBuildItem startIpfsDevService(
            IpfsBuildTimeConfig buildTimeConfig,
            IpfsDevServicesConfig devServicesConfig,
            IpfsDevServicesRecorder recorder,
            ShutdownContextBuildItem shutdownContextBuildItem) {

        if (!buildTimeConfig.enabled() || !devServicesConfig.enabled()) {
            return null;
        }

        RuntimeValue<EmbeddedIpfs> ipfsValue = recorder.startDevNode(
                devServicesConfig.port(),
                shutdownContextBuildItem);
        RuntimeValue<IpfsNodeInfo> nodeInfoValue = recorder.extractNodeInfo(ipfsValue);

        LOG.info("IPFS Dev Services node will start at application startup");

        return new IpfsDevServicesBuildItem(ipfsValue, nodeInfoValue);
    }
}
