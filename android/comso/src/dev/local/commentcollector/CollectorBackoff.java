package dev.local.commentcollector;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure-Java failure memory and short status cache for host-app processes. When the collector process is
 * unreachable, only the first caller pays the wake/retry/bridge cost; callers in the next window fail fast.
 */
final class CollectorBackoff {
    interface Clock { long now(); }
    static final long DOWN_WINDOW_MS = 8000L;
    static final long STATUS_TTL_MS = 1000L;

    /** An outage with no failed call for this long no longer counts as "currently unreachable". */
    static final long OUTAGE_STALE_MS = 60000L;

    private final Clock clock, wall;
    private final long downWindow, statusTtl;
    private long failedAt = Long.MIN_VALUE;
    private long outageStartWall = Long.MIN_VALUE;
    private int outageFailures;
    private final Map<String, String> status = new HashMap<>();
    private long statusAt = Long.MIN_VALUE;

    CollectorBackoff(Clock clock) { this(clock, clock, DOWN_WINDOW_MS, STATUS_TTL_MS); }
    CollectorBackoff(Clock clock, long downWindow, long statusTtl) { this(clock, clock, downWindow, statusTtl); }
    /** {@code clock} is monotonic (windows); {@code wall} is epoch time used only for outage reports. */
    CollectorBackoff(Clock clock, Clock wall, long downWindow, long statusTtl) { this.clock = clock; this.wall = wall; this.downWindow = downWindow; this.statusTtl = statusTtl; }

    /** True while a recent full attempt failed; callers should make one quick attempt only. */
    synchronized boolean failFast() { return failedAt != Long.MIN_VALUE && clock.now() - failedAt < downWindow; }
    /** A full attempt (provider, wake retries, bridge) failed. Starts an outage if none is open. */
    synchronized void recordFailure() {
        failedAt = clock.now();
        if (outageStartWall == Long.MIN_VALUE) outageStartWall = wall.now();
        outageFailures++;
    }
    /** A fast-failed call during an outage: counts as evidence but does not extend the fail-fast window. */
    synchronized void recordFastFailure() { if (outageStartWall != Long.MIN_VALUE) outageFailures++; }
    /** Returns {startWall, endWall, failures} when this success ends an outage, else null. */
    synchronized long[] recordSuccess() {
        failedAt = Long.MIN_VALUE;
        if (outageStartWall == Long.MIN_VALUE) return null;
        long[] outage = {outageStartWall, wall.now(), outageFailures};
        outageStartWall = Long.MIN_VALUE; outageFailures = 0;
        return outage;
    }
    /** True while an outage is open and the last full failure is recent. Cheap, never does IPC. */
    synchronized boolean unreachable() {
        return outageStartWall != Long.MIN_VALUE && failedAt != Long.MIN_VALUE && clock.now() - failedAt < OUTAGE_STALE_MS;
    }

    synchronized String cachedStatus(String body) {
        if (statusAt == Long.MIN_VALUE || clock.now() - statusAt >= statusTtl) return null;
        return status.get(body);
    }
    synchronized void cacheStatus(String body, String data) {
        long now = clock.now();
        if (statusAt == Long.MIN_VALUE || now - statusAt >= statusTtl) { status.clear(); statusAt = now; }
        status.put(body, data);
    }
    /** Any non-status operation may change task state, so drop cached status. */
    synchronized void invalidateStatus() { status.clear(); statusAt = Long.MIN_VALUE; }
}
