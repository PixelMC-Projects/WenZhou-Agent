/* SPDX-License-Identifier: Apache-2.0 */
package com.pixelmc.wenzhouai.client;

/** A reversible disclosure whose height depends on elapsed time rather than frame count. */
final class WzDisclosure {
    private static final long DURATION = 200_000_000L;
    private float from, to;
    private long changedAt;

    WzDisclosure(boolean open) {
        from = to = open ? 1 : 0;
    }

    float value(long now, boolean reduced) {
        if (reduced || from == to) return to;
        float t = Math.max(0, Math.min(1, (now - changedAt) / (float) DURATION));
        return from + (to - from) * (1 - (1 - t) * (1 - t) * (1 - t));
    }

    void set(boolean open, long now, boolean reduced) {
        float target = open ? 1 : 0;
        if (target == to) return;
        from = value(now, reduced);
        to = target;
        changedAt = now;
    }
}
