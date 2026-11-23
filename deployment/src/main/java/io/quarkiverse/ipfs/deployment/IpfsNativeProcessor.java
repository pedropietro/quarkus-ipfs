package io.quarkiverse.ipfs.deployment;

import java.util.Set;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.quarkiverse.ipfs.runtime.graal.DisabledDnsResolverConfigProvider;
import io.quarkus.bootstrap.classloading.QuarkusClassLoader;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourcePatternsBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.RuntimeInitializedPackageBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;

/**
 * GraalVM native image support for the IPFS extension.
 * Registers Nabu, libp2p, and Netty classes for reflection and runtime initialization.
 */
class IpfsNativeProcessor {

    private static final String ANDROID_RESOLVER = "org/xbill/DNS/config/AndroidResolverConfigProvider";
    private static final String WINDOWS_RESOLVER = "org/xbill/DNS/config/WindowsResolverConfigProvider";

    @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
    BytecodeTransformerBuildItem skipUnavailableDnsProviders() {
        // ResolverConfig eagerly constructs all providers, even those with optional platform APIs.
        Set<String> unavailable = hasJna() ? Set.of(ANDROID_RESOLVER) : Set.of(ANDROID_RESOLVER, WINDOWS_RESOLVER);
        String disabledProvider = Type.getInternalName(DisabledDnsResolverConfigProvider.class);
        return new BytecodeTransformerBuildItem("org.xbill.DNS.ResolverConfig",
                (name, visitor) -> new ClassVisitor(Opcodes.ASM9, visitor) {
                    @Override
                    public MethodVisitor visitMethod(int access, String methodName, String descriptor,
                            String signature, String[] exceptions) {
                        MethodVisitor method = super.visitMethod(access, methodName, descriptor, signature, exceptions);
                        if (!"<init>".equals(methodName)) {
                            return method;
                        }
                        return new MethodVisitor(Opcodes.ASM9, method) {
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                super.visitTypeInsn(opcode,
                                        opcode == Opcodes.NEW && unavailable.contains(type) ? disabledProvider : type);
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName, String descriptor,
                                    boolean isInterface) {
                                String target = opcode == Opcodes.INVOKESPECIAL && "<init>".equals(methodName)
                                        && unavailable.contains(owner) ? disabledProvider : owner;
                                super.visitMethodInsn(opcode, target, methodName, descriptor, isInterface);
                            }
                        };
                    }
                });
    }

    private static boolean hasJna() {
        return QuarkusClassLoader.isClassPresentAtRuntime("com.sun.jna.Pointer")
                && QuarkusClassLoader.isClassPresentAtRuntime("com.sun.jna.platform.win32.Guid");
    }

    @BuildStep
    void registerReflection(BuildProducer<ReflectiveClassBuildItem> reflection) {
        // Nabu core classes
        reflection.produce(ReflectiveClassBuildItem
                .builder(
                        "org.peergos.EmbeddedIpfs",
                        "org.peergos.HostBuilder",
                        "org.peergos.RamAddressBook",
                        "org.peergos.blockstore.RamBlockstore",
                        "org.peergos.config.IdentitySection",
                        "org.peergos.protocol.dht.RamRecordStore",
                        "org.peergos.protocol.dht.RecordStore")
                .methods(true)
                .fields(true)
                .build());

        // libp2p crypto classes
        reflection.produce(ReflectiveClassBuildItem
                .builder(
                        "io.libp2p.core.PeerId",
                        "io.libp2p.crypto.keys.Ed25519PrivateKey",
                        "io.libp2p.crypto.keys.Ed25519PublicKey",
                        "io.libp2p.core.crypto.KeyKt")
                .methods(true)
                .fields(true)
                .build());

        // Protobuf generated classes used by Nabu and libp2p
        reflection.produce(ReflectiveClassBuildItem
                .builder(
                        "com.google.protobuf.GeneratedMessageV3",
                        "com.google.protobuf.DescriptorProtos")
                .methods(true)
                .build());
    }

    @BuildStep
    void runtimeInitialization(
            BuildProducer<RuntimeInitializedClassBuildItem> runtimeInit,
            BuildProducer<RuntimeInitializedPackageBuildItem> runtimePackages) {
        if (QuarkusClassLoader.isClassPresentAtRuntime("io.netty.channel.epoll.Epoll")) {
            runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.netty.channel.epoll.Epoll"));
            runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.netty.channel.epoll.EpollIoHandler"));
            runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.netty.channel.epoll.EpollEventArray"));
        }
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.netty.handler.ssl.OpenSsl"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.netty.handler.ssl.ReferenceCountedOpenSslEngine"));
        // Netty loads the optional platform QUIC library when the transport is used.
        runtimePackages.produce(new RuntimeInitializedPackageBuildItem("io.netty.handler.codec.quic"));

        if (hasJna()) {
            runtimeInit.produce(new RuntimeInitializedClassBuildItem(
                    "org.xbill.DNS.config.WindowsResolverConfigProvider$InnerWindowsResolverConfigProvider"));
            runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.xbill.DNS.config.IPHlpAPI"));
            runtimePackages.produce(new RuntimeInitializedPackageBuildItem("com.sun.jna"));
        }

        // DNS configuration, shared I/O clients and random seeds belong to the running application.
        runtimePackages.produce(new RuntimeInitializedPackageBuildItem("org.xbill.DNS"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.libp2p.discovery.mdns.impl.JmDNSImpl"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("com.southernstorm.noise.protocol.Noise"));

        // The port mapper creates socket addresses for device discovery in static initializers.
        runtimePackages.produce(new RuntimeInitializedPackageBuildItem("com.offbynull.portmapper"));

        // Start with a fresh Prometheus registry and randomly striped counters.
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.prometheus.client.CollectorRegistry"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("io.prometheus.client.Striped64"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.peergos.AggregatedMetrics"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.peergos.blockstore.s3.S3Blockstore"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.peergos.protocol.bitswap.BitswapProtocol"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.peergos.protocol.dht.KademliaEngine"));
        runtimeInit.produce(new RuntimeInitializedClassBuildItem("org.peergos.protocol.dht.KademliaProtocol"));
    }

    @BuildStep
    void registerResources(
            BuildProducer<NativeImageResourceBuildItem> resources,
            BuildProducer<NativeImageResourcePatternsBuildItem> patterns) {
        // Include Nabu internal properties
        patterns.produce(NativeImageResourcePatternsBuildItem.builder()
                .includeGlob("org/peergos/**.properties")
                .build());
        // Include libp2p ServiceLoader manifests
        resources.produce(new NativeImageResourceBuildItem(
                "META-INF/services/io.libp2p.core.multiformats.Protocol"));
    }
}
