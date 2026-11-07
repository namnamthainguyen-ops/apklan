package com.lanpower.standalone;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public final class RoomPlaylistTest {
    private static MediaItem image(String id, long length) { return new MediaItem(id, "Ảnh phòng.jpg", "image/jpeg", length, 10); }
    private static RoomPlaylist playlist() { RoomPlaylist value = new RoomPlaylist(); value.changed(); return value; }

    @Test public void roomsHaveIndependentPrivatePlaylistsAndVersions() {
        RoomModel a = new RoomModel("a", "Phòng A", false, "keyA");
        RoomModel b = new RoomModel("b", "Phòng B", false, "keyB");
        a.playlist.files.add(image("a".repeat(64), 12)); a.playlist.changed();
        assertTrue(b.playlist.files.isEmpty());
        assertEquals("none", b.playlist.version);
        RoomPlaylist edited = a.playlist.copy(); edited.files.get(0).durationSeconds = 20; edited.changed();
        assertEquals(10, a.playlist.files.get(0).durationSeconds);
        assertNotEquals(a.playlist.version, edited.version);
        assertNotEquals(a.playlist.version, b.playlist.version);
        edited.validate(); a.playlist.validate();
    }

    @Test public void orderAndDurationChangesDoNotMutateSavedSnapshot() {
        RoomPlaylist original = playlist();
        original.files.add(image("a".repeat(64), 10)); original.files.add(image("b".repeat(64), 20));
        RoomPlaylist snapshot = original.copy();
        Collections.swap(original.files, 0, 1); original.changed();
        original.files.get(0).durationSeconds = 30;
        assertEquals("a".repeat(64), snapshot.files.get(0).id);
        assertEquals(10, snapshot.files.get(1).durationSeconds);
        assertNotEquals(original.version, snapshot.version);
    }

    @Test public void enforcesFileCountPerFileAndRoomByteLimits() {
        RoomPlaylist value = playlist();
        value.files.add(image("a".repeat(64), RoomPlaylist.MAX_FILE_BYTES));
        value.files.add(image("b".repeat(64), RoomPlaylist.MAX_FILE_BYTES));
        value.validate();
        assertEquals(RoomPlaylist.MAX_TOTAL_BYTES, value.totalBytes());
        value.files.add(image("c".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, value::validate);
        assertThrows(IllegalArgumentException.class, () -> image("a".repeat(64), RoomPlaylist.MAX_FILE_BYTES + 1).validate());
        assertThrows(IllegalArgumentException.class, () -> image("a".repeat(64), 0).validate());
        RoomPlaylist many = playlist();
        for (int i = 0; i < 21; i++) many.files.add(image(String.format("%064x", i), 1));
        assertThrows(IllegalArgumentException.class, many::validate);
    }

    @Test public void rejectsDuplicatesExecutableTypesInvalidIdsAndDurations() {
        RoomPlaylist duplicates = playlist(); duplicates.files.add(image("a".repeat(64), 1)); duplicates.files.add(image("a".repeat(64), 1));
        assertThrows(IllegalArgumentException.class, duplicates::validate);
        assertThrows(IllegalArgumentException.class, () -> new MediaItem("a".repeat(64), "run.exe", "application/octet-stream", 1, 10).validate());
        assertThrows(IllegalArgumentException.class, () -> image("../escape", 1).validate());
        assertThrows(IllegalArgumentException.class, () -> new MediaItem("a".repeat(64), "a.jpg", "image/jpeg", 1, 0).validate());
        assertThrows(IllegalArgumentException.class, () -> new MediaItem("a".repeat(64), "a.mp4", "video/mp4", 1, 10).validate());
        RoomPlaylist clearing = playlist(); clearing.validate(); assertEquals(0, clearing.totalBytes());
    }

    @Test public void mediaRequestSignaturesBindRoomKeyDestinationVersionHashAndLength() throws Exception {
        String keyA = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        String keyB = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String nonce = "a".repeat(32), version = "b".repeat(32), digest = "c".repeat(64);
        String canonical = LanProtocol.mediaCanonical("pc-a", 10, nonce, version, 20, digest);
        String signature = LanProtocol.hmac(keyA, canonical);
        assertEquals("media\npc-a\n10\n" + nonce + "\n" + version + "\n20\n" + digest, canonical);
        assertTrue(LanProtocol.verify(keyA, canonical, signature));
        assertFalse(LanProtocol.verify(keyB, canonical, signature));
        assertFalse(LanProtocol.verify(keyA, LanProtocol.mediaCanonical("pc-b", 10, nonce, version, 20, digest), signature));
        assertFalse(LanProtocol.verify(keyA, LanProtocol.mediaCanonical("pc-a", 11, nonce, version, 20, digest), signature));
        assertFalse(LanProtocol.verify(keyA, LanProtocol.mediaCanonical("pc-a", 10, nonce, "d".repeat(32), 20, digest), signature));
        assertFalse(LanProtocol.verify(keyA, LanProtocol.mediaCanonical("pc-a", 10, nonce, version, 21, digest), signature));
        assertFalse(LanProtocol.verify(keyA, LanProtocol.mediaCanonical("pc-a", 10, nonce, version, 20, "d".repeat(64)), signature));
    }

    @Test public void signedLockStateAndMediaAckRejectTampering() throws Exception {
        String key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        String state = LanProtocol.lockStateCanonical("nonce", "pc", "locked", "a".repeat(32), 17842, "b".repeat(64));
        String signature = LanProtocol.hmac(key, state);
        assertTrue(LanProtocol.verify(key, state, signature));
        assertFalse(LanProtocol.verify(key, LanProtocol.lockStateCanonical("nonce", "pc", "unlocked", "a".repeat(32), 17842, "b".repeat(64)), signature));
        assertFalse(LanProtocol.verify(key, LanProtocol.lockStateCanonical("nonce", "pc", "locked", "a".repeat(32), 17842, "c".repeat(64)), signature));
        assertThrows(IllegalArgumentException.class, () -> LanProtocol.lockStateCanonical("nonce", "pc", "success", "none", 17842, "b".repeat(64)));
        String ack = LanProtocol.mediaResultCanonical("n", "pc", "v", "accepted", "Applied");
        String ackSignature = LanProtocol.hmac(key, ack);
        assertFalse(LanProtocol.verify(key, LanProtocol.mediaResultCanonical("n", "other", "v", "accepted", "Applied"), ackSignature));
        assertFalse(LanProtocol.verify(key, LanProtocol.mediaResultCanonical("n", "pc", "v", "accepted", "Forged"), ackSignature));
    }
}
