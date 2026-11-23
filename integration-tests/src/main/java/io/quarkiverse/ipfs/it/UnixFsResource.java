package io.quarkiverse.ipfs.it;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletionStage;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;

/** HTTP access to CDI operations in the integration-test application. */
@Path("/unixfs")
public class UnixFsResource {

    @Inject
    DagService dag;

    @Inject
    BlockService blocks;

    @POST
    @Path("/file")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> addFile(byte[] bytes) {
        return dag.addFile("payload.bin", new ByteArrayInputStream(bytes));
    }

    @GET
    @Path("/file/{cid}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public CompletionStage<byte[]> getFile(@PathParam("cid") String cid) {
        return dag.getFile(cid);
    }

    @POST
    @Path("/directory/{cid}")
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> makeDirectory(@PathParam("cid") String cid, @QueryParam("reverse") boolean reverse) {
        var entries = new LinkedHashMap<String, String>();
        entries.put(reverse ? "payload.bin" : "cópia.bin", cid);
        entries.put(reverse ? "cópia.bin" : "payload.bin", cid);
        return dag.makeDirectory(entries);
    }

    @GET
    @Path("/directory/{cid}")
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> directoryNames(@PathParam("cid") String cid) {
        return dag.listDirectory(cid).thenApply(names -> String.join("\n", names));
    }

    /** Load an external block into the local test application for the Kubo fixture. */
    @POST
    @Path("/block/{cid}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> putBlock(@PathParam("cid") String cid, byte[] bytes) {
        return blocks.put(bytes, Cid.decode(cid).codec).thenApply(Cid::toString);
    }
}
