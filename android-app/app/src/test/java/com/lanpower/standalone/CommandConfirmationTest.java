package com.lanpower.standalone;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CommandConfirmationTest {
    @Test public void signedExplicitOutcomeConfirmsOnlyTheMatchingActionWithoutAnotherScan() {
        ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
        device.stateAuthenticated = true;
        assertFalse(CommandConfirmation.applyAcknowledgement("unlock", "LOCK_CONFIRMED:locked; confirmed", device));
        assertFalse(CommandConfirmation.applyAcknowledgement("lock", "Desired state saved", device));
        assertFalse(CommandConfirmation.applyAcknowledgement("lock", "LOCK_PLAYER_UNCONFIRMED", device));
        assertTrue(CommandConfirmation.applyAcknowledgement("lock", "LOCK_CONFIRMED:locked; confirmed", device));
        assertTrue(CommandConfirmation.matches("lock", device));
        assertTrue(CommandConfirmation.applyAcknowledgement("unlock", "Room TN20 display unlocked; the interactive player confirmed the screen.", device));
        assertTrue(CommandConfirmation.matches("unlock", device));
        assertFalse(CommandConfirmation.applyAcknowledgement("freeze", "UWF freeze scheduled: OFF (next OFF)", device));
        assertTrue(CommandConfirmation.applyAcknowledgement("freeze", "UWF freeze scheduled: OFF (next ON)", device));
        assertTrue(CommandConfirmation.matches("freeze", device));
    }
    @Test public void delayedLockIsPendingButMissingRuntimeIsRejected() {
        for (String code : new String[]{"LOCK_PLAYER_UNCONFIRMED", "LOCK_NO_CONSOLE_USER", "LOCK_REQUEST_SUPERSEDED", "LOCK_UI_TIMEOUT"}) {
            assertTrue(LanProtocol.pendingResult("lock", "Exception: " + code + ": saved"));
            assertTrue(LanProtocol.pendingResult("unlock", code));
            assertFalse(LanProtocol.pendingResult("shutdown", code));
        }
        assertFalse(LanProtocol.pendingResult("lock", "LOCK_RUNTIME_UNAVAILABLE"));
        assertFalse(LanProtocol.pendingResult("lock", "MEDIA_RUNTIME_UNAVAILABLE"));
        assertFalse(LanProtocol.pendingResult("lock", "XLOCK_PLAYER_UNCONFIRMED"));
    }

    @Test public void displayStateMustBeAuthenticatedAndMatchRequestedAction() {
        ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
        device.lockState = "locked";
        assertFalse(CommandConfirmation.matches("lock", device));
        device.lockAuthenticated = true;
        assertTrue(CommandConfirmation.matches("lock", device));
        assertFalse(CommandConfirmation.matches("unlock", device));
        device.lockState = "unavailable";
        assertFalse(CommandConfirmation.matches("lock", device));
        assertFalse(CommandConfirmation.matches("unlock", device));
        assertFalse(CommandConfirmation.matches("lock", null));
    }

    @Test public void uwfChecksNextBootStateAndWakeRequiresSignedResponse() {
        ManagedDevice device = new ManagedDevice("id", "pc", "127.0.0.1", "00:11:22:33:44:55");
        device.uwf = "ON (next OFF)";
        assertFalse(CommandConfirmation.matches("unfreeze", device));
        assertFalse(CommandConfirmation.matches("wake", device));
        device.stateAuthenticated = true;
        assertTrue(CommandConfirmation.matches("unfreeze", device));
        assertFalse(CommandConfirmation.matches("freeze", device));
        assertTrue(CommandConfirmation.matches("wake", device));
        device.uwf = "OFF (next ON)";
        assertTrue(CommandConfirmation.matches("freeze", device));
        device.uwf = "unknown";
        assertFalse(CommandConfirmation.matches("freeze", device));
        assertFalse(CommandConfirmation.matches("unfreeze", device));
    }
}
