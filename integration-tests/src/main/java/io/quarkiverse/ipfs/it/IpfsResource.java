/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package io.quarkiverse.ipfs.it;

import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;
import io.quarkiverse.ipfs.runtime.IpfsClient;

@Path("/ipfs")
@ApplicationScoped
public class IpfsResource {

    @Inject
    IpfsClient ipfsClient;

    @Inject
    BlockService blockService;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello ipfs";
    }

    @GET
    @Path("/node/peer-id")
    @Produces(MediaType.TEXT_PLAIN)
    public String peerId() {
        return ipfsClient.getNode().node.getPeerId().toBase58();
    }

    @POST
    @Path("/block")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    public String putBlock(byte[] data) throws Exception {
        Cid cid = blockService.put(data, Cid.Codec.DagCbor).get();
        return cid.toString();
    }

    @GET
    @Path("/block/{cid}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public byte[] getBlock(@PathParam("cid") String cid) throws Exception {
        try {
            return blockService.get(cid).get();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IllegalArgumentException invalid) {
                throw new BadRequestException(invalid);
            }
            if (failure.getCause() instanceof NoSuchElementException missing) {
                throw new NotFoundException(missing);
            }
            throw failure;
        }
    }

    @GET
    @Path("/block/{cid}/has")
    @Produces(MediaType.TEXT_PLAIN)
    public boolean hasBlock(@PathParam("cid") String cid) throws Exception {
        return blockService.has(cid).get();
    }

    @DELETE
    @Path("/block/{cid}")
    public Response removeBlock(@PathParam("cid") String cid) throws Exception {
        blockService.remove(cid).get();
        return Response.noContent().build();
    }

    @GET
    @Path("/block/list")
    @Produces(MediaType.TEXT_PLAIN)
    public String listBlocks() throws Exception {
        return blockService.listAll().get().toString();
    }
}
