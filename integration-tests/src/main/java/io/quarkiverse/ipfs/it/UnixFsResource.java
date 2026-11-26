package io.quarkiverse.ipfs.it;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

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
import jakarta.ws.rs.core.StreamingOutput;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.DagService;
import io.smallrye.common.annotation.Blocking;

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
    @Path("/file/stream")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    @Blocking
    public String addStream(InputStream input) {
        return dag.addFile("payload.bin", input).join();
    }

    @GET
    @Path("/file/{cid}/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Blocking
    public Response writeStream(@PathParam("cid") String cid) {
        StreamingOutput body = output -> dag.writeFile(cid, output).join();
        return Response.ok(body).header("X-Test-Max-Heap", Runtime.getRuntime().maxMemory()).build();
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

    /** Create a test directory from lines containing a CID followed by the entry name. */
    @POST
    @Path("/directory")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> makeDirectory(String listing) {
        var entries = new LinkedHashMap<String, String>();
        if (listing != null) {
            for (String line : listing.lines().toList()) {
                String[] fields = line.split(" ", 2);
                entries.put(fields[1], fields[0]);
            }
        }
        return dag.makeDirectory(entries);
    }

    @GET
    @Path("/directory/{cid}/entries")
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> directoryEntries(@PathParam("cid") String cid) {
        return dag.listDirectoryEntries(cid).thenApply(entries -> entries.stream()
                .map(entry -> entry.cid() + " " + entry.name())
                .collect(Collectors.joining("\n")));
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
