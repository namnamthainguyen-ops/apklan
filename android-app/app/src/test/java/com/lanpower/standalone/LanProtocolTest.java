package com.lanpower.standalone;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public final class LanProtocolTest {
    private static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    @Test public void relayWakeBindsTargetMacSeparatelyFromRelayId() throws Exception {
        String first = LanProtocol.wakeCanonical("request", "relay", 1, "nonce", "00:11:22:AA:BB:CC");
        assertEquals("command\nrequest\nrelay\nwake\n1\nnonce\n00:11:22:AA:BB:CC", first);
        String signature = LanProtocol.hmac(KEY, first);
        assertFalse(LanProtocol.verify(KEY, LanProtocol.wakeCanonical("request", "relay", 1, "nonce", "00:11:22:AA:BB:CD"), signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.wakeCanonical("request", "other-relay", 1, "nonce", "00:11:22:AA:BB:CC"), signature));
        assertEquals("00:11:22:AA:BB:CC", LanProtocol.normalizeMac("001122aabbcc"));
        assertThrows(IllegalArgumentException.class, () -> LanProtocol.wakeCanonical("request", "relay", 1, "nonce", "00:11:22:aa:bb:cc"));
    }

    @Test public void updateStatusSignatureBindsAllDisplayedFields() throws Exception {
        String canonical = LanProtocol.updateCanonical("nonce", "device", 5, "1.2.0", "ready", "Verified update");
        String signature = LanProtocol.hmac(KEY, canonical);
        assertTrue(LanProtocol.verify(KEY, canonical, signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.updateCanonical("other", "device", 5, "1.2.0", "ready", "Verified update"), signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.updateCanonical("nonce", "device", 6, "1.2.0", "ready", "Verified update"), signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.updateCanonical("nonce", "device", 5, "1.3.0", "ready", "Verified update"), signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.updateCanonical("nonce", "device", 5, "1.2.0", "failed", "Verified update"), signature));
        assertFalse(LanProtocol.verify(KEY, LanProtocol.updateCanonical("nonce", "device", 5, "1.2.0", "ready", "Forged update"), signature));
    }

    @Test public void roomKeysMatchIndependentNodeAndWindowsVectors() throws Exception {
        assertEquals("VUnd9gqHWPnL3YsvZF69DuCPHFp4YrHzQbv+TVHKsuc=", LanProtocol.deriveRoomKey("ROOM0001"));
        assertEquals("kLU0qzCKD7iBFMKXa8010x45Me8b/xIbLo/XH+co4yw=", LanProtocol.deriveRoomKey("ROOM0002"));
        assertEquals("LY3NnHDCmfxmr6g2rW06pLqDykLRBhHXSG0UwxJDhMQ=", LanProtocol.deriveRoomKey("Ab12Cd34"));
        assertEquals("cyzk0ZNRJdMHxC4GnzJbLzNOi8QHwfrM55eKrEVMuu8=", LanProtocol.deriveRoomKey("Ab12!@#$"));
        assertEquals(LanProtocol.deriveRoomKey("ROOM0001"), LanProtocol.deriveRoomKey("  ROOM0001  "));
        assertNotEquals(LanProtocol.deriveRoomKey("ROOM0001"), LanProtocol.deriveRoomKey("room0001"));
    }

    @Test public void roomKeysRejectInvalidLengthUnicodeAndInternalSpace() {
        for (String invalid : new String[]{"", "1234567", "123456789", "Ab12 Cd3", "Phòng123", "Ａbc12345", "Ab12\tCd3"}) {
            assertThrows(IllegalArgumentException.class, () -> LanProtocol.deriveRoomKey(invalid));
        }
    }

    @Test public void stateSignatureBindsNonceDeviceVersionUwfAndCapabilities() throws Exception {
        String canonical = LanProtocol.stateCanonical("nonce", "device", "1.1.0", "OFF (next ON)", "power,uwf");
        String signature = LanProtocol.hmac(KEY, canonical);
        assertTrue(LanProtocol.verify(KEY, canonical, signature));
        for (String changed : new String[]{
                LanProtocol.stateCanonical("other", "device", "1.1.0", "OFF (next ON)", "power,uwf"),
                LanProtocol.stateCanonical("nonce", "other", "1.1.0", "OFF (next ON)", "power,uwf"),
                LanProtocol.stateCanonical("nonce", "device", "1.0.0", "OFF (next ON)", "power,uwf"),
                LanProtocol.stateCanonical("nonce", "device", "1.1.0", "ON", "power,uwf"),
                LanProtocol.stateCanonical("nonce", "device", "1.1.0", "OFF (next ON)", "power")}) {
            assertFalse(LanProtocol.verify(KEY, changed, signature));
        }
    }

    @Test public void signedAckDetailCannotBeChangedOrReusedForAnotherRequest() throws Exception {
        String signature = LanProtocol.hmac(KEY, "ack-detail\nrequest\naccepted\nPending restart");
        assertTrue(LanProtocol.verifyAckDetail(KEY, "request", "accepted", "Pending restart", signature));
        assertFalse(LanProtocol.verifyAckDetail(KEY, "other", "accepted", "Pending restart", signature));
        assertFalse(LanProtocol.verifyAckDetail(KEY, "request", "rejected", "Pending restart", signature));
        assertFalse(LanProtocol.verifyAckDetail(KEY, "request", "accepted", "Applied already", signature));
    }

    @Test public void freezeAndUnfreezeAreExplicitSignedActions() {
        assertTrue(LanProtocol.commandCanonical("request", "device", "freeze", 1, "nonce").contains("\nfreeze\n"));
        assertTrue(LanProtocol.commandCanonical("request", "device", "unfreeze", 1, "nonce").contains("\nunfreeze\n"));
    }

    @Test public void hmacMatchesIndependentNodeVector() throws Exception {
        assertEquals("pn4gVeX+gg6Wjhr62xxvaKMEIO+JdCslrNO2GF13nR8=",
                LanProtocol.hmac(KEY, "discover\nnonce-123"));
    }

    @Test public void canonicalCommandMatchesWindowsWireFormat() throws Exception {
        String canonical = LanProtocol.commandCanonical("request-1", "device-1", "shutdown", 1700000000L, "nonce-123");
        assertEquals("command\nrequest-1\ndevice-1\nshutdown\n1700000000\nnonce-123", canonical);
        assertEquals("6azaz6J17wtbK24pn3k1yUDZGA1SvnIAm6KXelJhqPY=", LanProtocol.hmac(KEY, canonical));
    }

    @Test public void canonicalDiscoveryMatchesWindowsWireFormat() throws Exception {
        String canonical = LanProtocol.discoveryCanonical("nonce-123", "device-1", "PC-01", "00:11:22:AA:BB:CC");
        assertEquals("qlP5QnqsKdQf6I6IcrkwXNNeJPYoxUlQItonXcGf4rk=", LanProtocol.hmac(KEY, canonical));
    }

    @Test public void commandSignatureBindsEveryField() throws Exception {
        String canonical = LanProtocol.commandCanonical("request-1", "device-1", "shutdown", 1700000000L, "nonce-123");
        String signature = LanProtocol.hmac(KEY, canonical);
        assertTrue(LanProtocol.verify(KEY, canonical, signature));
        String[] changes = {
                LanProtocol.commandCanonical("request-2", "device-1", "shutdown", 1700000000L, "nonce-123"),
                LanProtocol.commandCanonical("request-1", "device-2", "shutdown", 1700000000L, "nonce-123"),
                LanProtocol.commandCanonical("request-1", "device-1", "restart", 1700000000L, "nonce-123"),
                LanProtocol.commandCanonical("request-1", "device-1", "shutdown", 1700000001L, "nonce-123"),
                LanProtocol.commandCanonical("request-1", "device-1", "shutdown", 1700000000L, "nonce-124")
        };
        for (String changed : changes) assertFalse(LanProtocol.verify(KEY, changed, signature));
        byte[] otherKey = Base64.getDecoder().decode(KEY);
        otherKey[0] ^= 1;
        assertFalse(LanProtocol.verify(Base64.getEncoder().encodeToString(otherKey), canonical, signature));
    }

    @Test public void forgedMalformedAndTruncatedSignaturesAreRejected() throws Exception {
        String canonical = "discover\nnonce-123";
        for (String signature : new String[]{"", "!not-base64!", "AAAA", Base64.getEncoder().encodeToString(new byte[32])}) {
            assertFalse(LanProtocol.verify(KEY, canonical, signature));
        }
    }

    @Test public void acknowledgementsBindRequestAndStatus() throws Exception {
        String signature = LanProtocol.hmac(KEY, "ack\nrequest-1\naccepted");
        assertTrue(LanProtocol.verifyAcknowledgement(KEY, "request-1", "accepted", signature));
        assertFalse(LanProtocol.verifyAcknowledgement(KEY, "request-2", "accepted", signature));
        assertFalse(LanProtocol.verifyAcknowledgement(KEY, "request-1", "rejected", signature));
        assertFalse(LanProtocol.verifyAcknowledgement(KEY, "request-1", "unknown", signature));
        assertFalse(LanProtocol.verifyAcknowledgement(KEY, "request-1", "accepted\ninjected", signature));
    }

    @Test public void keyNormalizationAcceptsOnly32ByteBase64() {
        assertEquals(KEY, LanProtocol.normalizeKey("  " + KEY + "  "));
        assertEquals(KEY, LanProtocol.normalizeKey(KEY.substring(0, KEY.length() - 1)));
        for (String invalid : new String[]{"", "not a key", Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33])}) {
            assertThrows(IllegalArgumentException.class, () -> LanProtocol.normalizeKey(invalid));
        }
    }

    @Test public void magicPacketContainsSixFfAndSixteenMacRepetitions() {
        byte[] packet = LanProtocol.magicPacket("00:11:22:AA:BB:CC");
        assertEquals(102, packet.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xff, packet[i]);
        byte[] mac = {0, 0x11, 0x22, (byte) 0xaa, (byte) 0xbb, (byte) 0xcc};
        for (int repeat = 0; repeat < 16; repeat++) {
            for (int i = 0; i < 6; i++) assertEquals(mac[i], packet[6 + repeat * 6 + i]);
        }
        assertArrayEquals(packet, LanProtocol.magicPacket("00-11-22-aa-bb-cc"));
        assertArrayEquals(packet, LanProtocol.magicPacket("001122AABBCC"));
    }

    @Test public void corruptMacAddressesAreNotSilentlyRepaired() {
        for (String invalid : new String[]{"", "0011223344", "00112233445566", "GG:11:22:AA:BB:CC",
                "xx00:11:22:AA:BB:CC", "00:11-22:AA:BB:CC", "00 11 22 AA BB CC", "00:11:22:AA:BB:CC\n"}) {
            assertThrows(IllegalArgumentException.class, () -> LanProtocol.magicPacket(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> LanProtocol.magicPacket(null));
    }

    @Test public void delimiterInjectionAndEmptyIdentityAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LanProtocol.discoveryCanonical("nonce", "", "PC-01", "00:11:22:AA:BB:CC"));
        assertThrows(IllegalArgumentException.class,
                () -> LanProtocol.discoveryCanonical("nonce", "device", "PC\n01", "00:11:22:AA:BB:CC"));
        assertThrows(IllegalArgumentException.class,
                () -> LanProtocol.commandCanonical("request\rinjected", "device", "shutdown", 1, "nonce"));
        assertThrows(IllegalArgumentException.class,
                () -> LanProtocol.commandCanonical("request", "device", "execute", 1, "nonce"));
    }
}
