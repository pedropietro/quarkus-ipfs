# Quarkus IPFS

Quarkus IPFS embeds a Peergos Nabu node in a Quarkus application. Inject CDI services to store and retrieve blocks by CID, manage peers, and work with DAGs and IPNS. The extension is experimental.

## Quick start

Use Java 21 or newer and a Maven application with the Quarkus 4.0.0 BOM. Choose an available release from Maven Central. [Quarkus IPFS on Maven Central](https://central.sonatype.com/artifact/io.github.pedropietro/quarkus-ipfs)

Add this dependency under `<dependencies>` in your application's `pom.xml`.

```xml
<dependency>
    <groupId>io.github.pedropietro</groupId>
    <artifactId>quarkus-ipfs</artifactId>
    <version>0.6.0</version>
</dependency>
```

The example uses `io.quarkus:quarkus-rest`. Add it if the application does not already use Quarkus REST. The extension POM declares JitPack for the libp2p dependencies. If your repository policy ignores repositories declared by dependencies, add the following to your application's existing `<repositories>` section, or create that section.

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```

Create `src/main/java/org/acme/BlocksResource.java`.

```java
package org.acme;

import java.util.concurrent.CompletionStage;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.ipfs.cid.Cid;
import io.quarkiverse.ipfs.runtime.BlockService;

@Path("/blocks")
public class BlocksResource {

    @Inject
    BlockService blocks;

    @POST
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.TEXT_PLAIN)
    public CompletionStage<String> put(byte[] bytes) {
        return blocks.put(bytes, Cid.Codec.Raw).thenApply(Cid::toString);
    }

    @GET
    @Path("/{cid}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public CompletionStage<byte[]> get(@PathParam("cid") String cid) {
        return blocks.get(cid);
    }
}
```

From the application directory, run `mvn quarkus:dev`. Send a block and read it back by its CID.

```sh
cid=$(curl -fsS -H 'Content-Type: application/octet-stream' \
  --data-binary 'hello ipfs' http://localhost:8080/blocks)
curl -fsS "http://localhost:8080/blocks/$cid"
```

The response is `hello ipfs`. The default `RAM` store lives for one application run. To persist blocks, set `quarkus.ipfs.storage-type=FILE` and `quarkus.ipfs.storage-path` to a dedicated directory that the application can write to. [Storage, Dev Services, and network configuration](docs/modules/ROOT/pages/index.adoc)

## Install from a source checkout

To install a local build from the source checkout, use the Maven Wrapper.

```sh
git clone https://github.com/pedropietro/quarkus-ipfs.git
cd quarkus-ipfs
./mvnw -B -pl deployment -am install -DskipTests
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`. The build downloads and verifies the pinned Nabu source archive.

## Verify the extension

The Maven Wrapper uses Maven 3.10.2. The JVM suite includes tests against the packaged JAR.

```sh
./mvnw -B clean verify
```

On Linux with Docker, use Mandrel 25 to build and test the Linux native executable. Use a Linux build environment when executing the Linux integration tests.

```sh
bash ./mvnw -B -pl deployment,integration-tests -am clean verify \
  -Dnative -Dquarkus.native.container-build=true -Dnative.surefire.skip=true \
  -Dquarkus.native.builder-image=quay.io/quarkus/ubi10-quarkus-mandrel-builder-image:jdk-25
```

GitHub Actions publishes the Surefire and Failsafe reports for the JVM and native jobs. [Build workflow](.github/workflows/build.yml)

### Verify UnixFS streaming with a limited heap

The Build workflow reads a 96 MiB UnixFS file into a file on disk with a 64 MiB application heap and checks the output size and SHA-256 digest. The packaged integration-test application uses FILE block storage in its production profile.

```sh
bash integration-tests/src/test/scripts/unixfs-stream.sh jvm integration-tests/target/quarkus-app
bash integration-tests/src/test/scripts/unixfs-stream.sh native integration-tests/target/*-runner
```

### Verify Kubo interoperability

The Build workflow runs the UnixFS, IPNS and authenticated HTTP scenarios against the packaged JVM application and native executable. Each scenario uses Kubo v0.43.1 pinned by digest, with its own internal Docker network. The workflow uploads one log per scenario and includes container logs when a scenario fails.

Run the same suite locally after the corresponding build.

```sh
bash integration-tests/src/test/scripts/kubo-ci.sh jvm integration-tests/target/quarkus-app target/kubo-jvm-logs
bash integration-tests/src/test/scripts/kubo-ci.sh native integration-tests/target/*-runner target/kubo-native-logs
```

### Stream a UnixFS file from Kubo

With the optional CDI `KuboClient` enabled, use `writeFile(cid, output)` to stream a UnixFS file to a caller-owned `OutputStream`. Wait for the returned future before closing the stream. It completes with the number of bytes written.

```java
try (var output = Files.newOutputStream(destination)) {
    long bytesWritten = kubo.writeFile(cid, output).join();
}
```

For authenticated Kubo RPC, grant the configured bearer token access to `/api/v0/cat` for files and `/api/v0/block/get` for raw blocks.

### Verify the Kubo HTTP client

Run the script after the JVM or native build. It starts the packaged application and a Kubo v0.43.1 server with bearer authentication on an isolated Docker network. It verifies a raw block and checks the size and SHA-256 of a 96 MiB UnixFS file streamed through the application with a 64 MiB heap.

```sh
bash integration-tests/src/test/scripts/kubo-http.sh jvm integration-tests/target/quarkus-app
bash integration-tests/src/test/scripts/kubo-http.sh native integration-tests/target/*-runner
```

### Verify IPNS with Kubo

Run the script against the packaged JAR or Linux native executable created by the corresponding build.
The script starts the application and two Kubo v0.43.1 peers on an isolated Docker network.
It checks publication and resolution in both directions, including a V2-only record published by Kubo.

```sh
bash integration-tests/src/test/scripts/kubo-ipns.sh jvm integration-tests/target/quarkus-app
bash integration-tests/src/test/scripts/kubo-ipns.sh native integration-tests/target/quarkus-ipfs-integration-tests-0.6.0-runner
```

### Verify UnixFS with Kubo

Build the integration-test JAR with the JVM command above and start a fresh copy of the application.

```sh
java -jar integration-tests/target/quarkus-app/quarkus-run.jar
```

In another terminal, start Kubo offline and run the block exchange from the repository root.

```sh
docker run --rm -d --network none --name quarkus-ipfs-kubo --entrypoint sh ipfs/kubo:v0.43.1 -ec '
  ipfs init --profile=test
  exec ipfs daemon --offline
'
# Wait for "Daemon is ready" in docker logs before starting the test.
QUARKUS_URL=http://127.0.0.1:8080 KUBO_CONTAINER=quarkus-ipfs-kubo \
  bash integration-tests/src/test/scripts/kubo-unixfs.sh
docker stop quarkus-ipfs-kubo
```

The test transfers blocks by CID and verifies that both implementations read the original multiblock file and its directory entry.

[Maven Central release preparation](docs/publishing-central.md)
