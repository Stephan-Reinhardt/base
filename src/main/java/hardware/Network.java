package hardware;

import context.Problem;
import context.Result;

import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;

public class Network implements Runnable {

    private Result<List<NicInfo>> interfaces;

    @Override
    public void run() {
        this.interfaces = Result.of(() -> {
            Enumeration<NetworkInterface> en = null;
            try {
                en = NetworkInterface.getNetworkInterfaces();
                return Collections.list(en).stream()
                        .map(Network::toNicInfo)
                        .toList();
            } catch (SocketException e) {
                interfaces = Result.err(new Problem(e));
            }
            return null;
        });

        print();
    }

    public record NicInfo(
            Result<Integer> index,
            Result<String> name,
            Result<String> displayName,
            Result<Boolean> up,
            Result<Boolean> loopback,
            Result<Boolean> virtual,
            Result<Boolean> pointToPoint,
            Result<Boolean> multicast,
            Result<Integer> mtu,
            Result<String> mac,
            Result<String> parentName,
            Result<List<String>> subInterfaceNames,
            Result<List<String>> inetAddresses,
            Result<List<String>> interfaceAddresses
    ) {
        public NicInfo {
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(up, "up");
            Objects.requireNonNull(loopback, "loopback");
            Objects.requireNonNull(virtual, "virtual");
            Objects.requireNonNull(pointToPoint, "pointToPoint");
            Objects.requireNonNull(multicast, "multicast");
            Objects.requireNonNull(mtu, "mtu");
            Objects.requireNonNull(mac, "mac");
            Objects.requireNonNull(parentName, "parentName");
            Objects.requireNonNull(subInterfaceNames, "subInterfaceNames");
            Objects.requireNonNull(inetAddresses, "inetAddresses");
            Objects.requireNonNull(interfaceAddresses, "interfaceAddresses");
        }
    }

    public void print() {
        System.out.println("== Network Interfaces ==");
        interfaces.fold(
                problem -> {
                    System.err.println("network.interfaces ERROR: " + problem);
                    return null;
                },
                list -> {
                    if (list.isEmpty()) {
                        System.out.println("(none)");
                        return null;
                    }
                    for (int i = 0; i < list.size(); i++) {
                        NicInfo ni = list.get(i);
                        System.out.println();
                        System.out.println("-- nic[" + i + "] --");
                        printField("index", ni.index());
                        printField("name", ni.name());
                        printField("displayName", ni.displayName());
                        printField("up", ni.up());
                        printField("loopback", ni.loopback());
                        printField("virtual", ni.virtual());
                        printField("pointToPoint", ni.pointToPoint());
                        printField("multicast", ni.multicast());
                        printField("mtu", ni.mtu());
                        printField("mac", ni.mac());
                        printField("parentName", ni.parentName());

                        printList("subInterfaces", ni.subInterfaceNames());
                        printList("inetAddresses", ni.inetAddresses());
                        printList("interfaceAddresses", ni.interfaceAddresses());
                    }
                    return null;
                }
        );
    }

    private static NicInfo toNicInfo(NetworkInterface ni) {
        return new NicInfo(
                Result.of(ni::getIndex),
                Result.of(ni::getName),
                Result.of(() -> Objects.toString(ni.getDisplayName(), "")),
                Result.ofThrowing(ni::isUp),
                Result.ofThrowing(ni::isLoopback),
                Result.of(ni::isVirtual),
                Result.ofThrowing(ni::isPointToPoint),
                Result.ofThrowing(ni::supportsMulticast),
                Result.ofThrowing(ni::getMTU),
                Result.ofThrowing(() -> formatMac(ni.getHardwareAddress())),
                Result.of(() -> ni.getParent() != null ? Objects.toString(ni.getParent().getName(), "") : ""),
                Result.of(() -> {
                    Enumeration<NetworkInterface> subs = ni.getSubInterfaces();
                    if (subs == null) return List.of();
                    return Collections.list(subs).stream()
                            .map(s -> Objects.toString(s.getName(), ""))
                            .toList();
                }),
                Result.of(() -> {
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    if (addrs == null) return List.of();
                    return Collections.list(addrs).stream()
                            .map(Network::formatInetAddress)
                            .toList();
                }),
                Result.of(() -> {
                    List<InterfaceAddress> ifas = ni.getInterfaceAddresses();
                    if (ifas == null) return List.of();
                    return ifas.stream()
                            .map(Network::formatInterfaceAddress)
                            .toList();
                })
        );
    }

    private static String formatMac(byte[] mac) {
        if (mac == null || mac.length == 0) return "";
        StringBuilder sb = new StringBuilder(mac.length * 3);
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", mac[i]));
        }
        return sb.toString();
    }

    private static String formatInetAddress(InetAddress a) {
        if (a == null) return "";
        String ip = a.getHostAddress();
        String kind = a.getClass().getSimpleName();
        return kind + " " + ip
                + (a.isLoopbackAddress() ? " [loopback]" : "")
                + (a.isLinkLocalAddress() ? " [link-local]" : "")
                + (a.isSiteLocalAddress() ? " [site-local]" : "")
                + (a.isMulticastAddress() ? " [multicast]" : "");
    }

    private static String formatInterfaceAddress(InterfaceAddress ia) {
        if (ia == null) return "";
        String addr = ia.getAddress() != null ? ia.getAddress().getHostAddress() : "";
        String bcast = ia.getBroadcast() != null ? ia.getBroadcast().getHostAddress() : "";
        short prefix = ia.getNetworkPrefixLength();
        return addr + "/" + prefix + (bcast.isEmpty() ? "" : " broadcast=" + bcast);
    }

    private static <T> void printField(String label, Result<T> r) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(r, "result");

        r.fold(
                problem -> {
                    System.err.println("  " + label + " ERROR: " + problem);
                    return null;
                },
                x -> {
                    System.out.println("  " + label + ": " + x);
                    return null;
                }
        );
    }

    private static void printList(String label, Result<List<String>> r) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(r, "result");

        r.fold(
                problem -> {
                    System.err.println("  " + label + " ERROR: " + problem);
                    return null;
                },
                xs -> {
                    System.out.println("  " + label + ":");
                    if (xs == null || xs.isEmpty()) {
                        System.out.println("    (none)");
                    } else {
                        for (String x : xs) System.out.println("    - " + x);
                    }
                    return null;
                }
        );
    }
}
