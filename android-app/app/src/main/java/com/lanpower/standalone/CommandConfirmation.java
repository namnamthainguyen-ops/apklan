package com.lanpower.standalone;

/** Receipt and timeout are never proof that the requested state has been reached. */
final class CommandConfirmation {
    // Called only after LanTransport verifies both ACK and its detail signature.
    static boolean applyAcknowledgement(String action, String message, ManagedDevice device) {
        if ("lock".equals(action) || "unlock".equals(action)) {
            String state = "lock".equals(action) ? "locked" : "unlocked";
            if (!message.startsWith("LOCK_CONFIRMED:" + state + ";")
                    && !message.equals("Room TN20 display " + state + "; the interactive player confirmed the screen.")) return false;
            int session = message.indexOf("SESSION_INFO:");
            if (session >= 0) {
                device.runtimeMessage = message.substring(session + "SESSION_INFO:".length()).trim();
                device.runtimeAuthenticated = true;
                device.runtimeStatus = "ready";
            }
            device.lockAuthenticated = true;
            device.lockState = state;
            return true;
        }
        if ("freeze".equals(action) || "unfreeze".equals(action)) {
            if (!message.startsWith("UWF " + action + " scheduled")) return false;
            java.util.regex.Matcher state = java.util.regex.Pattern.compile("(ON|OFF) \\(next (ON|OFF)\\)").matcher(message);
            if (!state.find()) return false;
            device.uwf = state.group();
            return matches(action, device);
        }
        return false;
    }

    static boolean matches(String action, ManagedDevice actual) {
        if (actual == null) return false;
        if ("lock".equals(action) || "unlock".equals(action))
            return actual.lockAuthenticated && ("lock".equals(action) ? "locked" : "unlocked").equals(actual.lockState);
        if ("wake".equals(action)) return actual.stateAuthenticated;
        if ("freeze".equals(action) || "unfreeze".equals(action)) {
            if (!actual.stateAuthenticated || !actual.uwf.matches("(ON|OFF)( \\(next (ON|OFF)\\))?")) return false;
            boolean next = actual.uwf.contains("next ON") || (!actual.uwf.contains("next OFF") && actual.uwf.startsWith("ON"));
            return next == "freeze".equals(action);
        }
        return false;
    }

    static String description(String action, ManagedDevice actual) {
        if ("wake".equals(action)) return actual.stateAuthenticated ? "Máy đã online" : "Chưa có trạng thái đã xác thực";
        if ("freeze".equals(action) || "unfreeze".equals(action)) return actual.uwfDescription();
        return actual.lockDescription() + (actual.runtimeAuthenticated && actual.runtimeMessage.startsWith("Phien ") ? " • " + actual.runtimeMessage : "");
    }
}
