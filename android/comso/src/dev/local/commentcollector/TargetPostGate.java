package dev.local.commentcollector;

/**
 * Decides whether the user is on the task's post and whether AutoPager may navigate there on its own.
 * Navigation never fights the user: it needs a quiet period, is spaced out, and is capped per task/generation.
 */
final class TargetPostGate {
    static final long IDLE_MS = 8000L, GAP_MS = 45000L;
    static final int MAX_ATTEMPTS = 3;
    private long task = -1, generation = -1, lastAttempt = -1;
    private int attempts;

    /** The resumed activity can show a post page AND the resolver says the current post is the task's post. */
    static boolean onTarget(boolean postActivity, String taskPhoto, String currentPhoto) {
        return postActivity && taskPhoto != null && !taskPhoto.isEmpty() && taskPhoto.equals(currentPhoto);
    }

    /** @return true (and counts an attempt) when AutoPager may open the target post now. */
    boolean tryNavigate(long now, long lastTouchAt, long task, long generation) {
        if (this.task != task || this.generation != generation) reset(task, generation);
        if (attempts >= MAX_ATTEMPTS || now - lastTouchAt < IDLE_MS) return false;
        if (lastAttempt >= 0 && now - lastAttempt < GAP_MS) return false;
        attempts++; lastAttempt = now;
        return true;
    }

    static final long OPEN_GAP_MS = 6000L;
    static final int MAX_OPEN_ATTEMPTS = 5;
    private int openAttempts;
    private long lastOpen = -1;
    /** On the target post with the comment drawer closed: may the comment button be clicked now? Max 5 per visit. */
    boolean tryOpenComments(long now, long lastTouchAt) {
        if (openAttempts >= MAX_OPEN_ATTEMPTS || now - lastTouchAt < IDLE_MS) return false;
        if (lastOpen >= 0 && now - lastOpen < OPEN_GAP_MS) return false;
        openAttempts++; lastOpen = now;
        return true;
    }
    boolean openExhausted() { return openAttempts >= MAX_OPEN_ATTEMPTS; }
    /** The user left the target post or the drawer opened: the next visit gets a fresh budget. */
    void newVisit() { openAttempts = 0; lastOpen = -1; }
    boolean exhausted() { return attempts >= MAX_ATTEMPTS; }
    void reset() { reset(-1, -1); }
    private void reset(long task, long generation) { this.task = task; this.generation = generation; attempts = 0; lastAttempt = -1; newVisit(); }
}
