package campusnet.client.net;

import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Displays local network interface information (M2).
 *
 * Inspects all active, non-loopback network interfaces and outputs
 * their name, display name, MAC address, MTU, IPv4 and IPv6 addresses.
 */
public class NetInfo {

    /**
     * Default constructor required by frozen stub contract.
     */
    public NetInfo() {}

    /**
     * Prints all active, non-loopback interfaces and their network properties to standard out.
     */
    public void print() {
        print(System.out);
    }

    /**
     * Prints all active, non-loopback interfaces to the specified print stream.
     *
     * @param out the print stream to write to
     */
    public void print(PrintStream out) {
        Enumeration<NetworkInterface> interfaces;
        try {
            interfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            out.println("Failed to retrieve network interfaces: " + e.getMessage());
            return;
        }

        if (interfaces == null) {
            out.println("No network interfaces found.");
            return;
        }

        int count = 0;
        List<NetworkInterface> list = Collections.list(interfaces);
        for (NetworkInterface ni : list) {
            try {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }

                count++;
                out.println("Interface: " + ni.getName() + " (" + ni.getDisplayName() + ")");

                byte[] hardwareAddress = ni.getHardwareAddress();
                out.println("  MAC: " + formatMac(hardwareAddress));

                int mtu = ni.getMTU();
                out.println("  MTU: " + (mtu >= 0 ? mtu : "n/a"));

                List<String> ipv4List = new ArrayList<>();
                List<String> ipv6List = new ArrayList<>();

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address) {
                        ipv4List.add(addr.getHostAddress());
                    } else if (addr instanceof Inet6Address) {
                        // Strip scope id if present for cleaner presentation
                        String host = addr.getHostAddress();
                        int percentIdx = host.indexOf('%');
                        if (percentIdx != -1) {
                            host = host.substring(0, percentIdx);
                        }
                        ipv6List.add(host);
                    }
                }

                out.println("  IPv4: " + (ipv4List.isEmpty() ? "none" : String.join(", ", ipv4List)));
                out.println("  IPv6: " + (ipv6List.isEmpty() ? "none" : String.join(", ", ipv6List)));
            } catch (SocketException e) {
                out.println("  [Error reading interface " + ni.getName() + ": " + e.getMessage() + "]");
            }
        }

        if (count == 0) {
            out.println("No active non-loopback network interfaces found.");
        }
    }

    /**
     * Formats a raw MAC address byte array into the standard uppercase colon-separated format (AA:BB:CC:DD:EE:FF).
     * Returns "n/a" if the byte array is null or empty.
     *
     * @param mac the raw hardware address bytes
     * @return formatted MAC address or "n/a"
     */
    public static String formatMac(byte[] mac) {
        if (mac == null || mac.length == 0) {
            return "n/a";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }
}
