package io.quarkiverse.ipfs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.ipfs.cid.Cid;

/**
 * High-level file and DAG operations built on top of {@link BlockService}.
 * Files are chunked into 256KB raw blocks linked via a simple flat DAG-PB structure.
 */
@ApplicationScoped
public class DagService {

    static final int CHUNK_SIZE = 256 * 1024;

    @Inject
    BlockService blockService;

    /**
     * Add file content from an InputStream.
     * Small files (≤ CHUNK_SIZE) are stored as a single raw block.
     * Larger files are chunked and linked via a UnixFS file node.
     *
     * @return CID string of the root block
     */
    public CompletableFuture<String> addFile(String name, InputStream data) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] content = readAll(data);
                if (content.length <= CHUNK_SIZE) {
                    Cid cid = blockService.put(content, Cid.Codec.Raw).join();
                    return cid.toString();
                }
                // Chunk and build a simple link list DAG
                List<Cid> chunkCids = new ArrayList<>();
                for (int offset = 0; offset < content.length; offset += CHUNK_SIZE) {
                    int end = Math.min(offset + CHUNK_SIZE, content.length);
                    byte[] chunk = java.util.Arrays.copyOfRange(content, offset, end);
                    chunkCids.add(blockService.put(chunk, Cid.Codec.Raw).join());
                }
                // Build a minimal DAG-PB node linking to all chunks
                byte[] dagNode = buildUnixFSFileNode(chunkCids, content.length);
                Cid rootCid = blockService.put(dagNode, Cid.Codec.DagProtobuf).join();
                return rootCid.toString();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read file data", e);
            }
        });
    }

    /**
     * Retrieve file bytes by root CID.
     * Supports single-block raw files and simple flat DAG-PB files.
     */
    public CompletableFuture<byte[]> getFile(String cidString) {
        return CompletableFuture.supplyAsync(() -> {
            Cid cid = Cid.decode(cidString);
            if (cid.codec == Cid.Codec.Raw) {
                return blockService.get(cidString).join();
            }
            // DAG-PB: parse links and reassemble
            byte[] dagNode = blockService.get(cidString).join();
            List<Cid> links = parseUnixFSLinks(dagNode);
            if (links.isEmpty()) {
                return dagNode;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Cid linkCid : links) {
                byte[] chunk = blockService.get(linkCid.toString()).join();
                try {
                    out.write(chunk);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return out.toByteArray();
        });
    }

    /**
     * Create a directory node linking name → CID entries.
     *
     * @param entries map of filename to CID string
     * @return CID string of the directory node
     */
    public CompletableFuture<String> makeDirectory(Map<String, String> entries) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] dirNode = buildUnixFSDirectoryNode(entries);
            Cid cid = blockService.put(dirNode, Cid.Codec.DagProtobuf).join();
            return cid.toString();
        });
    }

    /**
     * List filenames linked from a directory node.
     */
    public CompletableFuture<List<String>> listDirectory(String dirCid) {
        return CompletableFuture.supplyAsync(() -> {
            byte[] dagNode = blockService.get(dirCid).join();
            return parseUnixFSLinkNames(dagNode);
        });
    }

    // --- Minimal DAG-PB encoding helpers ---

    private byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * Builds a minimal DAG-PB PBNode with PBLinks pointing to chunk CIDs.
     * Protobuf encoding:
     * field 2 (Links) repeated: { field 1 (Hash): cid.bytes, field 2 (Name): "", field 3 (Tsize): 0 }
     * field 1 (Data): UnixFS Data message with type=File and filesize
     */
    private byte[] buildUnixFSFileNode(List<Cid> chunkCids, long fileSize) {
        ByteArrayOutputStream node = new ByteArrayOutputStream();
        try {
            for (Cid cid : chunkCids) {
                byte[] link = encodeLink(cid, "");
                writeVarint(node, 2 << 3 | 2); // field 2, wire type 2 (LEN)
                writeVarint(node, link.length);
                node.write(link);
            }
            byte[] unixfsData = buildUnixFSData(1, fileSize); // type=File
            writeVarint(node, 1 << 3 | 2); // field 1, wire type 2
            writeVarint(node, unixfsData.length);
            node.write(unixfsData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return node.toByteArray();
    }

    private byte[] buildUnixFSDirectoryNode(Map<String, String> entries) {
        ByteArrayOutputStream node = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                Cid cid = Cid.decode(entry.getValue());
                byte[] link = encodeLink(cid, entry.getKey());
                writeVarint(node, 2 << 3 | 2);
                writeVarint(node, link.length);
                node.write(link);
            }
            byte[] unixfsData = buildUnixFSData(1, 0); // type=Directory (1)
            writeVarint(node, 1 << 3 | 2);
            writeVarint(node, unixfsData.length);
            node.write(unixfsData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return node.toByteArray();
    }

    private byte[] encodeLink(Cid cid, String name) throws IOException {
        ByteArrayOutputStream link = new ByteArrayOutputStream();
        byte[] cidBytes = cid.toBytes();
        // field 1 (Hash): cidBytes
        writeVarint(link, 1 << 3 | 2);
        writeVarint(link, cidBytes.length);
        link.write(cidBytes);
        if (!name.isEmpty()) {
            // field 2 (Name): name bytes
            byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVarint(link, 2 << 3 | 2);
            writeVarint(link, nameBytes.length);
            link.write(nameBytes);
        }
        return link.toByteArray();
    }

    // UnixFS Data: field 1 (DataType) varint, field 4 (filesize) varint
    private byte[] buildUnixFSData(int dataType, long fileSize) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        writeVarint(data, 1 << 3 | 0); // field 1, varint
        writeVarint(data, dataType);
        if (fileSize > 0) {
            writeVarint(data, 4 << 3 | 0); // field 4, varint
            writeVarint(data, fileSize);
        }
        return data.toByteArray();
    }

    private void writeVarint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.write((int) value);
    }

    /**
     * Parse PBLinks from a DAG-PB node — returns CIDs of linked blocks.
     */
    private List<Cid> parseUnixFSLinks(byte[] dagNode) {
        List<Cid> links = new ArrayList<>();
        int pos = 0;
        while (pos < dagNode.length) {
            int tag = (int) readVarint(dagNode, pos);
            pos += varintSize(dagNode, pos);
            int field = tag >>> 3;
            int wireType = tag & 0x7;
            if (wireType == 2) {
                int len = (int) readVarint(dagNode, pos);
                pos += varintSize(dagNode, pos);
                byte[] data = java.util.Arrays.copyOfRange(dagNode, pos, pos + len);
                if (field == 2) { // PBLink
                    Cid cid = parseLinkCid(data);
                    if (cid != null)
                        links.add(cid);
                }
                pos += len;
            } else if (wireType == 0) {
                readVarint(dagNode, pos);
                pos += varintSize(dagNode, pos);
            } else {
                break; // unsupported wire type
            }
        }
        return links;
    }

    private List<String> parseUnixFSLinkNames(byte[] dagNode) {
        List<String> names = new ArrayList<>();
        int pos = 0;
        while (pos < dagNode.length) {
            int tag = (int) readVarint(dagNode, pos);
            pos += varintSize(dagNode, pos);
            int field = tag >>> 3;
            int wireType = tag & 0x7;
            if (wireType == 2) {
                int len = (int) readVarint(dagNode, pos);
                pos += varintSize(dagNode, pos);
                byte[] data = java.util.Arrays.copyOfRange(dagNode, pos, pos + len);
                if (field == 2) {
                    String name = parseLinkName(data);
                    if (name != null)
                        names.add(name);
                }
                pos += len;
            } else if (wireType == 0) {
                readVarint(dagNode, pos);
                pos += varintSize(dagNode, pos);
            } else {
                break;
            }
        }
        return names;
    }

    private Cid parseLinkCid(byte[] linkBytes) {
        int pos = 0;
        while (pos < linkBytes.length) {
            int tag = (int) readVarint(linkBytes, pos);
            pos += varintSize(linkBytes, pos);
            int field = tag >>> 3;
            int wireType = tag & 0x7;
            if (wireType == 2) {
                int len = (int) readVarint(linkBytes, pos);
                pos += varintSize(linkBytes, pos);
                if (field == 1) { // Hash
                    try {
                        return Cid.cast(java.util.Arrays.copyOfRange(linkBytes, pos, pos + len));
                    } catch (Exception e) {
                        return null;
                    }
                }
                pos += len;
            } else if (wireType == 0) {
                readVarint(linkBytes, pos);
                pos += varintSize(linkBytes, pos);
            } else {
                break;
            }
        }
        return null;
    }

    private String parseLinkName(byte[] linkBytes) {
        int pos = 0;
        while (pos < linkBytes.length) {
            int tag = (int) readVarint(linkBytes, pos);
            pos += varintSize(linkBytes, pos);
            int field = tag >>> 3;
            int wireType = tag & 0x7;
            if (wireType == 2) {
                int len = (int) readVarint(linkBytes, pos);
                pos += varintSize(linkBytes, pos);
                if (field == 2) { // Name
                    return new String(linkBytes, pos, len, java.nio.charset.StandardCharsets.UTF_8);
                }
                pos += len;
            } else if (wireType == 0) {
                readVarint(linkBytes, pos);
                pos += varintSize(linkBytes, pos);
            } else {
                break;
            }
        }
        return null;
    }

    private long readVarint(byte[] buf, int pos) {
        long result = 0;
        int shift = 0;
        while (pos < buf.length) {
            byte b = buf[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                break;
            shift += 7;
        }
        return result;
    }

    private int varintSize(byte[] buf, int pos) {
        int size = 0;
        while (pos < buf.length) {
            size++;
            if ((buf[pos++] & 0x80) == 0)
                break;
        }
        return size;
    }
}
