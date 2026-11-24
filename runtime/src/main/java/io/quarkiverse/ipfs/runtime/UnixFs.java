package io.quarkiverse.ipfs.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import io.ipfs.cid.Cid;

/** Protobuf wire encoding for the DAG-PB nodes and UnixFS metadata supported by DagService. */
final class UnixFs {

    private static final int RAW = 0;
    private static final int DIRECTORY = 1;
    private static final int FILE = 2;

    private UnixFs() {
    }

    static byte[] encodeFile(List<FileLink> children, long fileSize) {
        byte[] data = encode(out -> {
            out.writeEnum(1, FILE);
            out.writeUInt64(3, fileSize);
            for (FileLink child : children) {
                out.writeUInt64(4, child.fileSize());
            }
        });
        var links = children.stream().map(child -> new Link(child.cid(), ByteString.EMPTY, child.dagSize())).toList();
        return encodeNode(data, links);
    }

    static byte[] encodeDirectory(Map<String, String> entries) {
        List<Link> links = entries.entrySet().stream().map(entry -> {
            String name = entry.getKey();
            if (name.isEmpty() || name.equals(".") || name.equals("..") || name.indexOf('/') >= 0
                    || name.indexOf(0) >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(name)) {
                throw new IllegalArgumentException("Invalid UnixFS directory entry name");
            }
            return new Link(Cid.decode(entry.getValue()), ByteString.copyFromUtf8(name), -1);
        }).sorted(Comparator.comparing(Link::name, ByteString.unsignedLexicographicalComparator())).toList();
        return encodeNode(encode(out -> out.writeEnum(1, DIRECTORY)), links);
    }

    private static byte[] encodeNode(byte[] data, List<Link> links) {
        return encode(out -> {
            for (Link link : links) {
                out.writeByteArray(2, encode(fields -> {
                    fields.writeByteArray(1, link.cid().toBytes());
                    if (!link.name().isEmpty()) {
                        fields.writeBytes(2, link.name());
                    }
                    if (link.totalSize() >= 0) {
                        fields.writeUInt64(3, link.totalSize());
                    }
                }));
            }
            out.writeByteArray(1, data);
        });
    }

    static Node decode(byte[] bytes) {
        try {
            var input = CodedInputStream.newInstance(bytes);
            List<Link> links = new ArrayList<>();
            byte[] metadata = null;
            for (int tag; (tag = input.readTag()) != 0;) {
                switch (tag) {
                    case 10 -> metadata = input.readByteArray();
                    case 18 -> links.add(decodeLink(input.readByteArray()));
                    default -> skip(input, tag);
                }
            }
            if (metadata == null) {
                throw new IllegalArgumentException("DAG-PB node is missing UnixFS metadata");
            }
            return decodeData(metadata, links);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid UnixFS DAG-PB node", failure);
        }
    }

    private static Link decodeLink(byte[] bytes) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        Cid cid = null;
        ByteString name = ByteString.EMPTY;
        long totalSize = -1;
        for (int tag; (tag = input.readTag()) != 0;) {
            switch (tag) {
                case 10 -> cid = Cid.cast(input.readByteArray());
                case 18 -> name = input.readBytes();
                case 24 -> totalSize = input.readUInt64();
                default -> skip(input, tag);
            }
        }
        if (cid == null || !name.isValidUtf8()) {
            throw new IllegalArgumentException("Invalid UnixFS link");
        }
        return new Link(cid, name, totalSize);
    }

    private static Node decodeData(byte[] bytes, List<Link> links) throws IOException {
        var input = CodedInputStream.newInstance(bytes);
        int type = -1;
        byte[] data = new byte[0];
        long fileSize = -1;
        List<Long> blockSizes = new ArrayList<>();
        for (int tag; (tag = input.readTag()) != 0;) {
            switch (tag) {
                case 8 -> type = input.readEnum();
                case 18 -> data = input.readByteArray();
                case 24 -> fileSize = input.readUInt64();
                case 32 -> blockSizes.add(input.readUInt64());
                case 34 -> {
                    var packed = CodedInputStream.newInstance(input.readByteArray());
                    while (!packed.isAtEnd()) {
                        blockSizes.add(packed.readUInt64());
                    }
                }
                default -> skip(input, tag);
            }
        }
        if (type < 0) {
            throw new IllegalArgumentException("Missing UnixFS node type");
        }
        return new Node(type, data, fileSize, blockSizes, links);
    }

    private static void skip(CodedInputStream input, int tag) throws IOException {
        if (!input.skipField(tag)) {
            throw new IOException("Unexpected Protobuf end group");
        }
    }

    private static byte[] encode(Encoder encoder) {
        var output = new ByteArrayOutputStream();
        var fields = CodedOutputStream.newInstance(output);
        try {
            encoder.write(fields);
            fields.flush();
        } catch (IOException failure) {
            throw new UncheckedIOException("Failed to encode UnixFS node", failure);
        }
        return output.toByteArray();
    }

    @FunctionalInterface
    private interface Encoder {
        void write(CodedOutputStream output) throws IOException;
    }

    record FileLink(Cid cid, long fileSize, long dagSize) {
    }

    record Link(Cid cid, ByteString name, long totalSize) {
    }

    record Node(int type, byte[] data, long fileSize, List<Long> blockSizes, List<Link> links) {

        List<Long> fileBlockSizes() {
            if (isLegacyFile()) {
                long total = blockSizes.getFirst();
                List<Long> sizes = new ArrayList<>(links.size());
                for (int index = 0; index < links.size(); index++) {
                    sizes.add(Math.min(total - (long) index * DagService.CHUNK_SIZE, DagService.CHUNK_SIZE));
                }
                return sizes;
            }
            if (type != FILE && (type != RAW || !links.isEmpty())) {
                throw new IllegalArgumentException("Expected a UnixFS file");
            }
            if (blockSizes.size() != links.size() || blockSizes.stream().anyMatch(size -> size < 0)
                    || blockSizes.stream().reduce((long) data.length, Math::addExact) != Math.max(fileSize, 0)) {
                throw new IllegalArgumentException("Invalid UnixFS file size metadata");
            }
            return blockSizes;
        }

        List<String> directoryNames() {
            if (type != DIRECTORY || isLegacyFile() || links.stream().anyMatch(link -> link.name().isEmpty())) {
                throw new IllegalArgumentException("Expected a UnixFS directory");
            }
            List<String> names = links.stream().map(link -> link.name().toStringUtf8()).toList();
            if (names.size() != names.stream().distinct().count()) {
                throw new IllegalArgumentException("Duplicate UnixFS directory entry");
            }
            return names;
        }

        private boolean isLegacyFile() {
            // Releases through 0.2.0 wrote the total size as field 4 and omitted link names and Tsize.
            if (type != DIRECTORY || fileSize != -1 || data.length != 0 || blockSizes.size() != 1 || links.size() < 2) {
                return false;
            }
            long total = blockSizes.getFirst();
            long chunk = DagService.CHUNK_SIZE;
            return total > (links.size() - 1L) * chunk && total <= links.size() * chunk
                    && links.stream().allMatch(link -> link.cid().codec == Cid.Codec.Raw
                            && link.name().isEmpty() && link.totalSize() == -1);
        }
    }
}
