package com.lanpower.standalone;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict numeric IPv4 expansion; never resolves hostnames or scans public addresses. */
final class VpnTargets {
    private VpnTargets() { }

    static List<String> expand(String input) {
        if (input == null || input.trim().isEmpty()) return new ArrayList<>();
        if (input.length() > 65536) throw new IllegalArgumentException("Danh sách mạng quá dài");
        Set<String> hosts = new LinkedHashSet<>();
        String[] entries = input.trim().split("[\\s,;]+");
        if (entries.length > 1024) throw new IllegalArgumentException("Tối đa 1024 đích trong mỗi phòng");
        for (String entry : entries) {
            String[] parts = entry.split("/", -1);
            if (parts.length > 2) throw new IllegalArgumentException("IPv4/CIDR không hợp lệ: " + entry);
            long address = parse(parts[0]);
            int prefix = 32;
            if (parts.length == 2) {
                if (!parts[1].matches("[0-9]{1,2}")) throw new IllegalArgumentException("CIDR không hợp lệ: " + entry);
                prefix = Integer.parseInt(parts[1]);
                if (prefix < 22 || prefix > 32) throw new IllegalArgumentException("Chỉ nhận CIDR /22 đến /32 (tối đa 1024 địa chỉ)");
            }
            long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL;
            long first = address & mask;
            long last = first | (~mask & 0xffffffffL);
            if (!isPrivate(first) || !isPrivate(last)) throw new IllegalArgumentException("Chỉ nhận IPv4 LAN/VPN riêng hoặc CGNAT: " + entry);
            if (prefix <= 30) { first++; last--; }
            for (long host = first; host <= last; host++) {
                hosts.add(format(host));
                if (hosts.size() > 1024) throw new IllegalArgumentException("Tổng số đích vượt 1024 máy trong một phòng");
            }
        }
        return new ArrayList<>(hosts);
    }

    static boolean isPrivateAddress(String value) {
        try { return isPrivate(parse(value)); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static long parse(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IllegalArgumentException("Cần IPv4 dạng số: " + value);
        long result = 0;
        for (String part : value.split("\\.")) {
            if (part.length() > 1 && part.startsWith("0")) throw new IllegalArgumentException("IPv4 không dùng số 0 thừa: " + value);
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new IllegalArgumentException("IPv4 không hợp lệ: " + value);
            result = (result << 8) | octet;
        }
        return result;
    }

    private static boolean isPrivate(long address) {
        return (address & 0xff000000L) == 0x0a000000L || (address & 0xfff00000L) == 0xac100000L
                || (address & 0xffff0000L) == 0xc0a80000L || (address & 0xffc00000L) == 0x64400000L;
    }

    private static String format(long address) {
        return ((address >>> 24) & 255) + "." + ((address >>> 16) & 255) + "." + ((address >>> 8) & 255) + "." + (address & 255);
    }
}
