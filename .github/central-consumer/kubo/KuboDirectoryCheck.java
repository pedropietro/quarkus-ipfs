package org.acme;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import io.quarkiverse.ipfs.runtime.KuboClient;
import io.quarkiverse.ipfs.runtime.KuboClient.DirectoryEntry;

@Singleton
class KuboDirectoryCheck implements KuboSmokeTest.DirectoryCheck {
    private static final Path FIXTURE = Path.of("/directories");

    @Inject
    KuboClient kubo;

    @Override
    public void verify(String scenario) throws Exception {
        switch (scenario) {
            case "directory-listing" -> verifyListings();
            case "directory-unauthenticated" -> {
                var error = expectFailure(kubo.listDirectory(cid("root")), KuboClient.KuboRequestException.class);
                if (error.statusCode() != 401 && error.statusCode() != 403) {
                    throw new IllegalStateException("Expected an authentication failure", error);
                }
                System.out.println("Kubo rejected directory listing without a bearer token.");
            }
            case "directory-limit" -> {
                var error = expectFailure(kubo.listDirectory(cid("empty")), UncheckedIOException.class);
                if (!error.getCause().getMessage().contains("exceeded 1048576 bytes")) {
                    throw new IllegalStateException("Expected the Kubo listing size limit", error);
                }
                System.out.println("Kubo directory listing rejected a response above 1 MiB.");
            }
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    private void verifyListings() throws Exception {
        var root = verifyListing("root", cid("root"));
        String nestedCid = root.stream().filter(entry -> entry.name().equals("nested")).findFirst().orElseThrow().cid();
        if (!nestedCid.equals(cid("nested")) || root.stream().noneMatch(entry -> entry.name().equals("préface 世界.txt"))) {
            throw new IllegalStateException("Missing expected nested CID or Unicode name");
        }
        if (!verifyListing("empty", cid("empty")).isEmpty()
                || verifyListing("nested", nestedCid).stream().noneMatch(entry -> entry.name().equals("ação.txt"))) {
            throw new IllegalStateException("Unexpected empty or nested directory entries");
        }
        expectFailure(kubo.listDirectory("invalid-cid"), IllegalArgumentException.class);
        expectFailure(kubo.listDirectory(cid("file")), IllegalArgumentException.class);
        expectFailure(kubo.listDirectory(cid("missing")), NoSuchElementException.class);
        System.out.println("Kubo directory listing rejected invalid, file, and missing CIDs.");
    }

    private List<DirectoryEntry> verifyListing(String sample, String cid) throws Exception {
        var expected = new ArrayList<DirectoryEntry>();
        for (String line : Files.readAllLines(FIXTURE.resolve(sample + ".ls"))) {
            // ipfs ls --size=false --resolve-type=false prints a CID followed by the entry name.
            String[] fields = line.split("\\s+", 2);
            expected.add(new DirectoryEntry(fields[1], fields[0]));
        }
        var actual = kubo.listDirectory(cid).get(20, TimeUnit.SECONDS);
        if (!actual.equals(expected)) {
            throw new IllegalStateException("Kubo directory listing differs from ipfs ls for " + sample
                    + ". Expected " + expected + ", got " + actual);
        }
        System.out.println("Authenticated HTTPS listing matched ipfs ls for " + sample + ": " + actual);
        return actual;
    }

    private static String cid(String sample) throws Exception {
        return Files.readString(FIXTURE.resolve(sample + ".cid")).strip();
    }

    private static <T extends Throwable> T expectFailure(CompletableFuture<?> operation, Class<T> type) throws Exception {
        try {
            operation.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (type.isInstance(failure.getCause())) {
                return type.cast(failure.getCause());
            }
            throw new IllegalStateException("Expected " + type.getSimpleName(), failure);
        }
        throw new IllegalStateException("Expected Kubo directory listing to fail");
    }
}
