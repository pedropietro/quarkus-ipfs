# Quarkus IPFS

Quarkus IPFS embeds a Peergos Nabu node in a Quarkus application. Inject CDI services to store and retrieve blocks by CID, manage peers, and work with DAGs and IPNS. The extension is experimental.

## Quick start

Use Java 21 or newer and a Maven application with the Quarkus 4.0.0 BOM. The release coordinates are `io.github.pedropietro:quarkus-ipfs:0.1.0`. Once the version is available in Maven Central, add the dependency directly. To try the same version from a source checkout, install it in your local Maven repository first.

```sh
git clone https://github.com/pedropietro/quarkus-ipfs.git
cd quarkus-ipfs
./mvnw -B -pl deployment -am install -DskipTests
```

On Windows, use `.\mvnw.cmd` in place of `./mvnw`. The build downloads and verifies the pinned Nabu source archive. Maven resolves some networking dependencies from JitPack.

In a Quarkus REST application, add this dependency to `pom.xml` under `<dependencies>`.

```xml
<dependency>
    <groupId>io.github.pedropietro</groupId>
    <artifactId>quarkus-ipfs</artifactId>
    <version>0.1.0</version>
</dependency>
```

The example uses `io.quarkus:quarkus-rest`. Add it if the application does not already use Quarkus REST. Ensure the application can resolve dependencies from Maven Central and JitPack. If your repository policy ignores repositories declared by dependencies, add the following to your application's existing `<repositories>` section, or create that section.

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

[Maven Central release preparation](docs/publishing-central.md)
