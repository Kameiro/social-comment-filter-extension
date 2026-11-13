package dev.local.commentcollector;

public final class CollectorBackoffTest {
    public static void main(String[] args) {
        long[] now = {1000};
        CollectorBackoff backoff = new CollectorBackoff(() -> now[0]);
        assert !backoff.failFast();
        backoff.recordFailure();
        assert backoff.failFast();
        now[0] += 7999;
        assert backoff.failFast();
        now[0] += 1;
        assert !backoff.failFast();
        backoff.recordFailure();
        assert backoff.failFast();
        backoff.recordSuccess();
        assert !backoff.failFast();

        assert backoff.cachedStatus("{}") == null;
        backoff.cacheStatus("{}", "A");
        assert "A".equals(backoff.cachedStatus("{}"));
        assert backoff.cachedStatus("{\"taskId\":1}") == null;
        backoff.cacheStatus("{\"taskId\":1}", "B");
        assert "B".equals(backoff.cachedStatus("{\"taskId\":1}"));
        now[0] += 999;
        assert "A".equals(backoff.cachedStatus("{}"));
        now[0] += 1;
        assert backoff.cachedStatus("{}") == null;
        backoff.cacheStatus("{}", "C");
        backoff.invalidateStatus();
        assert backoff.cachedStatus("{}") == null;
        // Outage evidence.
        long[] wall = {5_000_000};
        CollectorBackoff o = new CollectorBackoff(() -> now[0], () -> wall[0], 8000, 1000);
        assert !o.unreachable() && o.recordSuccess() == null;
        o.recordFailure(); wall[0] += 100; now[0] += 100;
        assert o.unreachable();
        o.recordFastFailure(); o.recordFastFailure();
        o.recordFailure(); wall[0] += 50; now[0] += 50;
        long[] outage = o.recordSuccess();
        assert outage != null && outage[0] == 5_000_000 && outage[1] == 5_000_150 && outage[2] == 4;
        assert !o.unreachable() && o.recordSuccess() == null;
        o.recordFailure(); now[0] += CollectorBackoff.OUTAGE_STALE_MS;
        assert !o.unreachable(); // stale outage is not reported as current
        o.recordFastFailure();
        assert o.recordSuccess()[2] == 2; // but its evidence is kept until a success
        System.out.println("CollectorBackoffTest ok");
    }
}
