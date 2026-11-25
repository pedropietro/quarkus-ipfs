package io.quarkiverse.ipfs.runtime;

import jakarta.inject.Inject;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;

/** Readiness of the configured external Kubo RPC. */
@Readiness
public class KuboHealthCheck implements AsyncHealthCheck {

    @Inject
    KuboClient kubo;

    @Override
    public Uni<HealthCheckResponse> call() {
        return Uni.createFrom().completionStage(kubo::checkReadiness)
                .map(up -> HealthCheckResponse.named("kubo").status(up).build())
                .onFailure().recoverWithItem(() -> HealthCheckResponse.down("kubo"));
    }
}
