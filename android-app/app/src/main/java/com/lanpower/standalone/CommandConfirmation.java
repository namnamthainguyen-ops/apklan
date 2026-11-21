package com.lanpower.standalone;

/** Receipt and timeout are never proof that the requested state has been reached. */
final class CommandConfirmation {
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
        return actual.lockDescription();
    }
}
