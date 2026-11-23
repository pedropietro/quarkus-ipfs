package io.quarkiverse.ipfs.runtime.graal;

import java.net.InetSocketAddress;
import java.util.List;

import org.xbill.DNS.Name;
import org.xbill.DNS.config.ResolverConfigProvider;

/** Placeholder for dnsjava providers whose optional platform APIs are absent. */
public final class DisabledDnsResolverConfigProvider implements ResolverConfigProvider {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void initialize() {
    }

    @Override
    public List<InetSocketAddress> servers() {
        return List.of();
    }

    @Override
    public List<Name> searchPaths() {
        return List.of();
    }
}
