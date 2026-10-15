package com.ipcamerapro.mobile;

public final class WifiSessionGuardSmoke {
    private static void check(boolean actual, boolean expected, String label) {
        if (actual != expected) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        WifiSessionGuard guard = new WifiSessionGuard("192.168.1.42", 3);
        check(guard.shouldStop("192.168.1.42"), false, "same address");
        check(guard.shouldStop(null), false, "transient Wi-Fi loss");
        check(guard.shouldStop("192.168.1.42"), false, "Wi-Fi recovered");
        check(guard.shouldStop("192.168.1.55"), false, "first IP change");
        check(guard.shouldStop("192.168.1.55"), false, "second IP change");
        check(guard.shouldStop("192.168.1.55"), true, "third IP change");
        check(guard.shouldStop("192.168.1.55"), true, "stop remains true");
        check(guard.shouldStop("192.168.1.42"), false, "recovery resets counter");
        check(guard.shouldStop(null), false, "first disconnect");
        check(guard.shouldStop(null), false, "second disconnect");
        check(guard.shouldStop(null), true, "third disconnect");
        try {
            new WifiSessionGuard(null, 3);
            throw new AssertionError("null expected IP accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("PASS: Wi-Fi session guard tolerates transient drops, detects persistent IP changes and loss");
    }
}
