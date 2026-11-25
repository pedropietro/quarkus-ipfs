package io.quarkiverse.ipfs.runtime;

import java.util.function.Consumer;

import org.peergos.EmbeddedIpfs;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;
import io.quarkus.runtime.metrics.MetricsFactory;

@Recorder
public class IpfsMetricsRecorder {

    public RuntimeValue<KuboMetrics> createKubo() {
        return new RuntimeValue<>(new KuboMetrics());
    }

    public Consumer<MetricsFactory> registerKubo(RuntimeValue<KuboMetrics> metrics) {
        return factory -> metrics.getValue().register(factory);
    }

    public RuntimeValue<IpfsMetrics> create(boolean enabled) {
        return new RuntimeValue<>(new IpfsMetrics(enabled));
    }

    public Consumer<MetricsFactory> register(RuntimeValue<IpfsMetrics> metrics, RuntimeValue<EmbeddedIpfs> node) {
        return factory -> metrics.getValue().register(factory, node.getValue());
    }
}
