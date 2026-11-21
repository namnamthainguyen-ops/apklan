package com.lanpower.standalone;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public final class DeviceHistoryTest {
    @Test public void historyRetainsFiftyEntriesAndSeparatesRoomsAndMachines() throws Exception {
        Map<String, String> saved = new HashMap<>();
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getString".equals(method.getName())) return saved.getOrDefault(args[0], (String) args[1]);
                    if ("edit".equals(method.getName())) {
                        Map<String, String> pending = new HashMap<>();
                        return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                                new Class<?>[]{SharedPreferences.Editor.class}, (editor, operation, values) -> {
                                    if ("putString".equals(operation.getName())) {
                                        pending.put((String) values[0], (String) values[1]);
                                        return editor;
                                    }
                                    if ("commit".equals(operation.getName())) { saved.putAll(pending); return true; }
                                    throw new UnsupportedOperationException(operation.getName());
                                });
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        DeviceHistory history = new DeviceHistory(preferences);
        for (int i = 0; i < 60; i++) history.record("room-a", "pc-1", "Action", "RESULT[" + i + "]");
        JSONArray stored = new JSONArray(saved.get("room-a|pc-1"));
        assertEquals(50, stored.length());
        assertEquals("RESULT[10]", stored.getJSONObject(0).getString("result"));
        assertEquals("RESULT[59]", stored.getJSONObject(49).getString("result"));
        DeviceHistory reopened = new DeviceHistory(preferences);
        assertTrue(reopened.read("room-a", "pc-1").contains("RESULT[59]"));
        assertFalse(reopened.read("room-b", "pc-1").contains("RESULT[59]"));
        assertFalse(reopened.read("room-a", "pc-2").contains("RESULT[59]"));
        history.record("room-a", "pc-1", "Action", "x".repeat(2000));
        stored = new JSONArray(saved.get("room-a|pc-1"));
        assertEquals(1201, stored.getJSONObject(49).getString("result").length());
    }
}
