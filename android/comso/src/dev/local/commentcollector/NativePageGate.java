package dev.local.commentcollector;

final class NativePageGate {
    private static final long RESPONSE_TIMEOUT_MS = 45000;
    enum Decision { READY, WAIT, TIMEOUT }
    private long task, generation, lastRequest = -3000;
    private String stream = "";
    private int progress;
    private boolean pending;

    Decision check(long now, long task, long generation, String stream, int progress) {
        if (this.task != task || this.generation != generation || !this.stream.equals(stream)) {
            this.task = task; this.generation = generation; this.stream = stream;
            pending = false; lastRequest = now - 3000;
        }
        if (pending && this.progress != progress) pending = false;
        if (pending) return now - lastRequest >= RESPONSE_TIMEOUT_MS ? Decision.TIMEOUT : Decision.WAIT;
        return now - lastRequest >= 3000 ? Decision.READY : Decision.WAIT;
    }

    void requested(long now, int progress) { this.progress = progress; lastRequest = now; pending = true; }

    void localCompleted() { pending = false; }

    /** Forget an outstanding request (task paused/resumed or page left the foreground) so a stale one cannot time out. */
    void reset() { pending = false; lastRequest = -3000; task = 0; generation = 0; stream = ""; }
}
