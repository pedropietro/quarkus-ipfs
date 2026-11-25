package io.quarkiverse.ipfs.deployment;

import java.util.Optional;

import jakarta.inject.Singleton;

import org.jboss.jandex.DotName;

import io.quarkiverse.ipfs.runtime.IpfsMetricsRecorder;
import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkiverse.ipfs.runtime.KuboMetrics;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;
import io.quarkus.deployment.metrics.MetricsCapabilityBuildItem;
import io.quarkus.deployment.metrics.MetricsFactoryConsumerBuildItem;
import io.quarkus.runtime.metrics.MetricsFactory;

class KuboProcessor {

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    SyntheticBeanBuildItem kuboMetrics(IpfsBuildTimeConfig config, IpfsMetricsRecorder recorder,
            Optional<MetricsCapabilityBuildItem> capability, BuildProducer<MetricsFactoryConsumerBuildItem> consumers) {
        if (!config.kuboEnabled()) {
            return null;
        }
        var metrics = recorder.createKubo();
        if (config.metricsEnabled()
                && capability.filter(item -> item.metricsSupported(MetricsFactory.MICROMETER)).isPresent()) {
            consumers.produce(new MetricsFactoryConsumerBuildItem(recorder.registerKubo(metrics), ExecutionTime.RUNTIME_INIT));
        }
        return SyntheticBeanBuildItem.configure(KuboMetrics.class).scope(Singleton.class)
                .runtimeValue(metrics).setRuntimeInit().done();
    }

    @BuildStep
    ExtensionSslNativeSupportBuildItem kuboSsl(IpfsBuildTimeConfig config) {
        return config.kuboEnabled() ? new ExtensionSslNativeSupportBuildItem("ipfs") : null;
    }

    @BuildStep
    AdditionalBeanBuildItem kuboClient(IpfsBuildTimeConfig config) {
        if (!config.kuboEnabled()) {
            return null;
        }
        return AdditionalBeanBuildItem.builder()
                .addBeanClasses(KuboClient.class)
                .setDefaultScope(DotName.createSimple(Singleton.class.getName()))
                .setUnremovable().build();
    }
}
