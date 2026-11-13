package dev.local.commentcollector;

/**
 * "No more roots but no explicit hasMore=false" is only believed after it has held continuously: a covered or
 * not-yet-active post page reports it transiently. The caller reports each observation; any contrary signal
 * (more pages available, a new response, another task/generation) restarts the timer.
 */
final class NativeEofGuard {
    static final long PERSIST_MS = 15000L;
    private long task = -1, generation = -1, firstSeen = -1;
    private int progress;

    /** @return true once the condition has been observed continuously for PERSIST_MS. */
    boolean persistent(long now, long task, long generation, int progress) {
        if (this.task != task || this.generation != generation || this.progress != progress) {
            this.task = task; this.generation = generation; this.progress = progress; firstSeen = -1;
        }
        if (firstSeen < 0) firstSeen = now;
        return now - firstSeen >= PERSIST_MS;
    }
    void reset() { firstSeen = -1; task = -1; generation = -1; }
}
