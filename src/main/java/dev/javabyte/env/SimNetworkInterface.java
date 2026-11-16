package dev.javabyte.env;

import dev.javabyte.host.StandIn;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Imitates {@code java.net.NetworkInterface}, which is final. Samples read the MAC address to
 * build a machine identifier, so a stable configured value is returned.
 */
public final class SimNetworkInterface implements StandIn {

    private final byte[] mac;
    private final InetAddress address;

    public SimNetworkInterface(byte[] mac, InetAddress address) {
        this.mac = mac;
        this.address = address;
    }

    @Override
    public String standsFor() {
        return "java/net/NetworkInterface";
    }

    public byte[] getHardwareAddress() { return mac.clone(); }
    public String getName() { return "eth0"; }
    public String getDisplayName() { return "Intel(R) Ethernet Connection"; }
    public boolean isUp() { return true; }
    public boolean isLoopback() { return false; }
    public boolean isVirtual() { return false; }
    public boolean isPointToPoint() { return false; }
    public int getMTU() { return 1500; }
    public int getIndex() { return 2; }
    public Enumeration<InetAddress> getInetAddresses() { return Collections.enumeration(List.of(address)); }
    @Override public String toString() { return "name:eth0 (Intel(R) Ethernet Connection)"; }
}
