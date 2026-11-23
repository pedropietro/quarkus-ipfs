# Quarkus Ipfs

[![Version](https://img.shields.io/maven-central/v/io.quarkiverse.ipfs/quarkus-ipfs?logo=apache-maven&style=flat-square)](https://central.sonatype.com/artifact/io.quarkiverse.ipfs/quarkus-ipfs-parent)

## Welcome to Quarkiverse!

Congratulations and thank you for creating a new Quarkus extension project in Quarkiverse!

Feel free to replace this content with the proper description of your new project and necessary instructions how to use and contribute to it.

You can find the basic info, Quarkiverse policies and conventions in [the Quarkiverse wiki](https://github.com/quarkiverse/quarkiverse/wiki).

In case you are creating a Quarkus extension project for the first time, please follow [Building My First Extension](https://quarkus.io/guides/building-my-first-extension) guide.

Other useful articles related to Quarkus extension development can be found under the [Writing Extensions](https://quarkus.io/guides/#writing-extensions) guide category on the [Quarkus.io](https://quarkus.io) website.

Thanks again, good luck and have fun!

## Build requirements

Use Java 21 or newer and the Maven Wrapper. The `nabu-upstream` module downloads the source archive of the pinned Peergos Nabu commit, checks its SHA-256 and packages its MIT license with the library. [Pinned Nabu source](https://github.com/Peergos/nabu/tree/d2454c9af6e05e15d703ef394687857aab2432a9)

This extension targets Quarkus 4.0 and uses its BOM to align Netty and Vert.x with the Nabu networking stack.

The default IPFS swarm transport is TCP. QUIC swarm addresses use the same Netty codec as Vert.x and require the `io.netty:netty-codec-native-quic` classifier for the target platform.

On Windows, run `mvnw.cmd -B clean verify -DskipITs=false`. On Linux or macOS, run `bash ./mvnw -B clean verify -DskipITs=false`.

## Documentation

The documentation for this extension should be maintained as part of this repository and it is stored in the `docs/` directory.

The layout should follow the [Antora's Standard File and Directory Set](https://docs.antora.org/antora/2.3/standard-directories/).

Once the docs are ready to be published, please open a PR including this repository in the [Quarkiverse Docs Antora playbook](https://github.com/quarkiverse/quarkiverse-docs/blob/main/antora-playbook.yml#L7). See an example [here](https://github.com/quarkiverse/quarkiverse-docs/pull/1)

Your documentation will then be published to the <https://docs.quarkiverse.io/> website.
