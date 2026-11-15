package xmc.config;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Only private, loopback, link-local and CGNAT destinations are allowed for
 * the Xray relay. The relay must never be usable as a public TCP egress.
 */
public final class PrivateAddress {
    private PrivateAddress() {
    }

    public static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            // 100.64.0.0/10 (carrier-grade NAT)
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            return first == 100 && second >= 64 && second <= 127;
        }
        if (address instanceof Inet6Address) {
            // fc00::/7 (unique local addresses)
            return (bytes[0] & 0xFE) == 0xFC;
        }
        return false;
    }

    /**
     * Resolves a host and verifies that every address is private. Returns the
     * first resolved private address so the caller can connect to exactly what
     * was validated.
     */
    public static InetAddress resolvePrivate(String host) throws UnknownHostException {
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException("no address for " + host);
        }
        List<String> publicAddresses = new ArrayList<>();
        for (InetAddress address : addresses) {
            if (!isPrivate(address)) {
                publicAddresses.add(address.getHostAddress());
            }
        }
        if (!publicAddresses.isEmpty()) {
            throw new UnknownHostException("public relay address is not allowed: " + String.join(", ", publicAddresses));
        }
        return addresses[0];
    }
}
