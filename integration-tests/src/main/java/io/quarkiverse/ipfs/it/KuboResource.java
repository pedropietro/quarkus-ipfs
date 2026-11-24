package io.quarkiverse.ipfs.it;

import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.quarkiverse.ipfs.runtime.KuboClient;

@Path("/kubo/block")
public class KuboResource {
    @Inject
    Instance<KuboClient> client;

    @GET
    @Path("/{cid}")
    public Response getBlock(@PathParam("cid") String cid) throws Exception {
        if (client.isUnsatisfied()) {
            return Response.status(503).build();
        }
        try {
            return Response.ok(client.get().getBlock(cid).get(), MediaType.APPLICATION_OCTET_STREAM).build();
        } catch (ExecutionException failure) {
            return switch (failure.getCause()) {
                case IllegalArgumentException ignored -> Response.status(400).build();
                case NoSuchElementException ignored -> Response.status(404).build();
                case TimeoutException ignored -> Response.status(504).build();
                case KuboClient.KuboRequestException error -> Response.status(error.statusCode()).build();
                default -> throw failure;
            };
        }
    }
}
