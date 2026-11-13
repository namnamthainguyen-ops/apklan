package com.lanpower.standalone;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.Assert.*;

public final class RoomModelTest {
    private static ManagedDevice device(String id) { return new ManagedDevice(id, "PC-" + id, "192.168.1.20", "00:11:22:AA:BB:CC"); }

    @Test public void successfulScanRetainsSavedOfflineDevicesAndUpdatesSeenTime() {
        RoomModel room = new RoomModel("room", "Phòng A", false, "key");
        room.applyScan(Arrays.asList(device("one"), device("two")), 1000);
        assertEquals(2, room.onlineCount());
        ManagedDevice updated = device("one");
        updated.ip = "192.168.1.30";
        room.applyScan(Collections.singletonList(updated), 2000);
        assertEquals(2, room.devices.size());
        assertEquals(1, room.onlineCount());
        assertEquals("192.168.1.30", room.devices.get(device("one").listKey()).ip);
        assertEquals(2000, room.devices.get(device("one").listKey()).lastSeen);
        assertFalse(room.devices.get(device("two").listKey()).online);
        assertEquals(1000, room.devices.get(device("two").listKey()).lastSeen);
    }

    @Test public void clonedIdsWithDistinctMacsRemainSeparatelySelectable() {
        RoomModel room = new RoomModel("room", "A", false, "key");
        ManagedDevice first = device("clone");
        ManagedDevice second = new ManagedDevice(first.id, "PC-02", "192.168.1.21", "00:11:22:AA:BB:DD");
        room.applyScan(Arrays.asList(first, second), 1000);
        assertEquals(2, room.onlineCount());
        assertEquals(second.ip, room.selected(Collections.singleton(second.listKey())).get(0).ip);
        assertEquals(first.id, second.id);
    }

    @Test public void zeroReplyScanMarksSavedDevicesOfflineWithoutDeletingThem() {
        RoomModel room = new RoomModel("room", "Phòng A", false, "key");
        room.applyScan(Collections.singletonList(device("one")), 1000);
        room.applyScan(Collections.emptyList(), 2000);
        assertEquals(1, room.devices.size());
        assertEquals(0, room.onlineCount());
        assertEquals(2000, room.lastScan);
    }

    @Test public void scanningOneRoomNeverMutatesAnotherRoom() {
        RoomModel first = new RoomModel("first", "A", false, "keyA");
        RoomModel second = new RoomModel("second", "B", false, "keyB");
        first.applyScan(Collections.singletonList(device("one")), 1000);
        second.applyScan(Collections.singletonList(device("two")), 1000);
        first.applyScan(Collections.emptyList(), 2000);
        assertEquals(0, first.onlineCount());
        assertEquals(1, second.onlineCount());
        assertFalse(second.devices.containsKey(device("one").listKey()));
    }

    @Test public void selectionUsesOnlyThisRoomsIdsAndCopiesSnapshots() {
        RoomModel room = new RoomModel("room", "A", false, "key");
        room.applyScan(Arrays.asList(device("one"), device("two")), 1000);
        List<ManagedDevice> selected = room.selected(new LinkedHashSet<>(Arrays.asList(device("one").listKey(), "other-room-id")));
        assertEquals(1, selected.size());
        room.applyScan(Collections.emptyList(), 2000);
        assertTrue(selected.get(0).online);
        assertFalse(room.devices.get(device("one").listKey()).online);
    }

    @Test public void unsignedOrSubstringCapabilitiesNeverEnableUwf() {
        ManagedDevice device = device("one");
        device.capabilities = "power,uwf";
        device.uwf = "ON";
        assertFalse(device.supportsUwf());
        assertFalse("ON".equals(device.trustedUwf()));
        device.stateAuthenticated = true;
        assertTrue(device.supportsUwf());
        assertEquals("ON", device.trustedUwf());
        device.capabilities = "power,notuwf";
        assertFalse(device.supportsUwf());
    }

    @Test public void currentAndNextUwfStatesRemainDistinctInDisplay() {
        ManagedDevice device = device("one");
        device.stateAuthenticated = true;
        device.capabilities = "power,uwf";
        device.uwf = "OFF (next ON)";
        assertTrue(device.uwfDescription().startsWith("Hiện tại: Không đóng băng"));
        assertTrue(device.uwfDescription().endsWith("Sau khởi động lại: Đóng băng"));
        device.uwf = "ON (next OFF)";
        assertTrue(device.uwfDescription().startsWith("Hiện tại: Đóng băng"));
        assertTrue(device.uwfDescription().endsWith("Sau khởi động lại: Không đóng băng"));
    }
}
