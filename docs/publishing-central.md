# Publish to Maven Central

The publishing namespace for this repository is `io.github.pedropietro`. Sign into [Central Portal](https://central.sonatype.com/) using the `pedropietro` GitHub account and confirm that the namespace is verified before uploading. [GitHub namespace verification](https://central.sonatype.org/register/namespace/)

The release coordinates are `io.github.pedropietro:quarkus-ipfs:0.1.0`. The Java imports stay under `io.quarkiverse.ipfs`. The bundle contains the parent POM and the runtime, deployment, and Nabu artifacts.

## Prepare the bundle

Use Java 21 or newer, the Maven Wrapper, and the GPG key you use for Maven Central releases. Make its public key discoverable using a key server supported by Central Portal. [Signing requirements](https://central.sonatype.org/publish/requirements/#sign-files-with-gpgpgp)

Run the build from the release checkout.

```sh
./mvnw -B clean verify
./mvnw -B -Pcentral-release -DperformRelease=true -DskipTests clean deploy
```

On PowerShell, replace `./mvnw` with `.\mvnw.cmd`.

The `central-release` profile attaches Javadoc, signs the Maven artifacts, and creates `target/central-publishing/central-bundle.zip` for manual upload. The usual Maven build also attaches the source JARs. `-DperformRelease=true` limits the release reactor to the four published modules.

## Upload and verify

Upload the bundle in Central Portal. Review the validation results and confirm the publication there. After the Portal reports the deployment as published, check that Maven can resolve the release from Maven Central. Run this outside the source checkout and use an empty directory for the Maven cache.

~~~sh
mvn -Dmaven.repo.local=./central-verification-repo dependency:get -Dartifact=io.github.pedropietro:quarkus-ipfs:0.1.0 -Dtransitive=false
~~~

[Manual bundle upload](https://central.sonatype.org/publish/publish-portal-upload/)

Keep the existing GitHub `v0.1.0` source tag. If you tag the commit that contains the Central packaging changes, use a distinct tag such as `maven-v0.1.0`.

The runtime transitives include the pinned libp2p stack from JitPack. The release POM declares that repository for Maven consumers. Add JitPack explicitly if your build tool or repository manager only uses repositories declared in your application.
