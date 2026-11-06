package com.lanpower.standalone;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class VpnTargetsTest {
    @Test public void expandsPrivateAndCgnatNetworksAndDeduplicates() {
        assertEquals(Arrays.asList("192.168.1.1", "192.168.1.2", "100.64.0.10", "10.0.0.1"),
                VpnTargets.expand("192.168.1.0/30,192.168.1.2;100.64.0.10\n10.0.0.1"));
        assertTrue(VpnTargets.expand("").isEmpty());
    }
    @Test public void boundariesAreBoundedAndNetworkBroadcastNotScanned() {
        List<String> hosts = VpnTargets.expand("10.0.0.0/22");
        assertEquals(1022, hosts.size());
        assertEquals("10.0.0.1", hosts.get(0));
        assertEquals("10.0.3.254", hosts.get(hosts.size() - 1));
        assertEquals(Arrays.asList("172.16.0.0", "172.16.0.1"), VpnTargets.expand("172.16.0.0/31"));
        assertEquals(Arrays.asList("100.127.255.255"), VpnTargets.expand("100.127.255.255/32"));
    }
    @Test public void refusesPublicHostnamesMalformedAndBroadNetworks() {
        for (String input : new String[]{"8.8.8.8", "example.com", "0.0.0.0/0", "10.0.0.0/21", "100.63.255.255", "100.128.0.0", "172.32.0.1", "192.169.0.1", "127.0.0.1", "10.0.0.999", "10.00.0.1", "10.0.0.1/33", "10.0.0.1/-1", "10.0.0.1/24/24", "10.0.0.1/"}) {
            assertThrows(input, IllegalArgumentException.class, () -> VpnTargets.expand(input));
        }
    }
    @Test public void refusesMoreThan1024UniqueHostsAcrossEntries() {
        assertThrows(IllegalArgumentException.class, () -> VpnTargets.expand("10.0.0.0/22 10.0.4.0/30 10.0.4.4"));
        assertEquals(1024, VpnTargets.expand("10.0.0.0/22 10.0.4.0/30").size());
    }
}
