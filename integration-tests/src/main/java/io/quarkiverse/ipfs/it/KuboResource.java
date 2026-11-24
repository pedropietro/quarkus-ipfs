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
import jakarta.ws.rs.core.StreamingOutput;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.smallrye.common.annotation.Blocking;

@Path("/kubo")
public class KuboResource {
    @Inject
    Instance<KuboClient> client;

    @GET
    @Blocking
    @Path("/file/{cid}")
    public Response streamFile(@PathParam("cid") String cid) {
        if (client.isUnsatisfied()) {
            return Response.status(503).build();
        }
        var kubo = client.get();
        StreamingOutput body = output -> kubo.writeFile(cid, output).join();
        return Response.ok(body, MediaType.APPLICATION_OCTET_STREAM)
                .header("X-Test-Max-Heap", Runtime.getRuntime().maxMemory()).build();
    }

    @GET
    @Path("/block/{cid}")
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
