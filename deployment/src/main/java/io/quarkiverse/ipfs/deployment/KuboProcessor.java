package io.quarkiverse.ipfs.deployment;

import jakarta.inject.Singleton;

import org.jboss.jandex.DotName;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkiverse.ipfs.runtime.config.IpfsBuildTimeConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ExtensionSslNativeSupportBuildItem;

class KuboProcessor {

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
