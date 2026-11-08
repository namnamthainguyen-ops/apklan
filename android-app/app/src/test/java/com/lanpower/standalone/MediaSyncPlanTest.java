package com.lanpower.standalone;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public final class MediaSyncPlanTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String ID = "00000000-0000-4000-8000-000000000001", NONCE = "b".repeat(32);
    private RoomPlaylist playlist() {
        RoomPlaylist p = new RoomPlaylist(); p.version = "c".repeat(32);
        p.files.add(new MediaItem("a".repeat(64), "poster.png", "image/png", 123, 10));
        p.files.add(new MediaItem("b".repeat(64), "video.mp4", "video/mp4", 456, 0));
        return p;
    }
    private JSONObject signed(JSONObject plan, RoomPlaylist p) throws Exception {
        byte[] bytes = plan.toString().getBytes(StandardCharsets.UTF_8);
        return new JSONObject().put("type", "media-plan").put("nonce", NONCE).put("deviceId", ID).put("version", p.version)
                .put("payload", Base64.getEncoder().encodeToString(bytes)).put("signature", LanProtocol.hmac(KEY, "media-plan\n" + NONCE + "\n" + ID + "\n" + p.version + "\n" + RoomPlaylist.sha256(bytes)));
    }
    private JSONObject plan(JSONArray missing, JSONArray removed) throws Exception { return new JSONObject().put("missing", missing).put("removed", removed).put("added", new JSONArray()); }

    @Test public void transfersOnlyMissingBytesAndReportsRemovedNames() throws Exception {
        RoomPlaylist p = playlist();
        MediaSyncPlan parsed = MediaSyncPlan.read(signed(plan(new JSONArray().put("b".repeat(64)), new JSONArray().put(new JSONObject().put("id", "d".repeat(64)).put("name", "old.png"))), p), KEY, ID, p, NONCE);
        assertEquals(1, parsed.missing.size()); assertEquals(456, parsed.bytes);
        assertEquals("video.mp4", parsed.uploadedNames.toString()); assertEquals("old.png", parsed.removedNames.toString());
        MediaSyncPlan none = MediaSyncPlan.read(signed(plan(new JSONArray(), new JSONArray()), p), KEY, ID, p, NONCE);
        assertEquals(0, none.bytes); assertTrue(none.missing.isEmpty());
    }
    @Test public void addingAnAlreadyCachedFileRequiresNoUploadButIsReported() throws Exception {
        RoomPlaylist p = playlist();
        JSONObject contents = plan(new JSONArray(), new JSONArray()).put("added", new JSONArray().put(new JSONObject().put("id", "a".repeat(64)).put("name", "poster.png")));
        MediaSyncPlan result = MediaSyncPlan.read(signed(contents, p), KEY, ID, p, NONCE);
        assertEquals(0, result.bytes); assertEquals(1, result.addedIds.size()); assertEquals("poster.png", result.addedNames.toString());
        contents.getJSONArray("added").put(new JSONObject().put("id", "d".repeat(64)).put("name", "unknown.png"));
        assertThrows(Exception.class, () -> MediaSyncPlan.read(signed(contents, p), KEY, ID, p, NONCE));
    }
    @Test public void rejectsUnrequestedAndDuplicateUploadsOrRemovalOfWantedFile() throws Exception {
        RoomPlaylist p = playlist();
        for (JSONArray missing : new JSONArray[] { new JSONArray().put("d".repeat(64)), new JSONArray().put("a".repeat(64)).put("a".repeat(64)) })
            assertThrows(Exception.class, () -> MediaSyncPlan.read(signed(plan(missing, new JSONArray()), p), KEY, ID, p, NONCE));
        JSONObject wanted = new JSONObject().put("id", "a".repeat(64)).put("name", "poster.png");
        assertThrows(Exception.class, () -> MediaSyncPlan.read(signed(plan(new JSONArray(), new JSONArray().put(wanted)), p), KEY, ID, p, NONCE));
    }
    @Test public void planIsBoundToKeyDeviceNonceVersionAndExactPayload() throws Exception {
        RoomPlaylist p = playlist(); JSONObject envelope = signed(plan(new JSONArray(), new JSONArray()), p);
        assertThrows(Exception.class, () -> MediaSyncPlan.read(envelope, Base64.getEncoder().encodeToString(new byte[31]), ID, p, NONCE));
        assertThrows(Exception.class, () -> MediaSyncPlan.read(envelope, KEY, "00000000-0000-4000-8000-000000000002", p, NONCE));
        assertThrows(Exception.class, () -> MediaSyncPlan.read(envelope, KEY, ID, p, "d".repeat(32)));
        RoomPlaylist newer = p.copy(); newer.changed();
        assertThrows(Exception.class, () -> MediaSyncPlan.read(envelope, KEY, ID, newer, NONCE));
        envelope.put("payload", Base64.getEncoder().encodeToString("{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(Exception.class, () -> MediaSyncPlan.read(envelope, KEY, ID, p, NONCE));
    }
}
