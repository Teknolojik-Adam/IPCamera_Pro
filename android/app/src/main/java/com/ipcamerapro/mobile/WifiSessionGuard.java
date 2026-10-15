package com.ipcamerapro.mobile;

/** Pure-Java guard: stop an MJPEG session after sustained Wi-Fi IP loss/change. */
final class WifiSessionGuard {
    private final String expectedIp;
    private final int mismatchLimit;
    private int mismatches;

    WifiSessionGuard(String expectedIp, int mismatchLimit) {
        if (expectedIp == null || expectedIp.isEmpty() || mismatchLimit < 1) {
            throw new IllegalArgumentException("Invalid Wi-Fi session guard");
        }
        this.expectedIp = expectedIp;
        this.mismatchLimit = mismatchLimit;
    }

    /** Returns true only when enough consecutive checks disagree with the bind IP. */
    boolean shouldStop(String currentIp) {
        if (expectedIp.equals(currentIp)) {
            mismatches = 0;
            return false;
        }
        if (mismatches < mismatchLimit) mismatches++;
        return mismatches >= mismatchLimit;
    }
}
