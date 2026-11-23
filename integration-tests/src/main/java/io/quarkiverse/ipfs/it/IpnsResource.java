package io.quarkiverse.ipfs.it;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.peergos.EmbeddedIpfs;

import io.ipfs.multiaddr.MultiAddress;
import io.quarkiverse.ipfs.runtime.IpnsService;

@Path("/ipns")
@Produces(MediaType.TEXT_PLAIN)
public class IpnsResource {

    @Inject
    IpnsService names;

    @Inject
    EmbeddedIpfs node;

    @POST
    @Path("/peers")
    @Consumes(MediaType.TEXT_PLAIN)
    public int connect(String addresses) {
        var peers = addresses.lines().map(MultiAddress::new).toList();
        return node.dht.bootstrapRoutingTable(node.node, peers, address -> true);
    }

    @POST
    @Consumes(MediaType.TEXT_PLAIN)
    public CompletableFuture<Response> publish(String value, @QueryParam("sequence") long sequence) {
        return names.publish(value, sequence, 1)
                .thenApply(peers -> Response.ok(names.getLocalIpnsName()).header("X-IPNS-Replicas", peers).build());
    }

    @GET
    @Path("/{name}")
    public CompletableFuture<Response> resolve(@PathParam("name") String name, @QueryParam("minResults") int minResults) {
        return names.resolve(name, minResults)
                .thenApply(value -> Response.ok(value).build())
                .exceptionally(failure -> {
                    if (failure.getCause() instanceof IllegalStateException unavailable) {
                        return Response.status(Response.Status.NOT_FOUND).entity(unavailable.getMessage()).build();
                    }
                    throw new CompletionException(failure);
                });
    }
}
