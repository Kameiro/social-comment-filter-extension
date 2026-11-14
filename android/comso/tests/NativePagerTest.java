package dev.local.commentcollector;

public final class NativePagerTest {
    public static void main(String[] args) {
        NativePageGate gate = new NativePageGate();
        assert gate.check(10000, 1, 1, "root", 1) == NativePageGate.Decision.READY;
        gate.requested(10000, 1);
        assert gate.check(12000, 1, 1, "root", 1) == NativePageGate.Decision.WAIT;
        assert gate.check(12000, 1, 1, "root", 2) == NativePageGate.Decision.WAIT;
        assert gate.check(13000, 1, 1, "root", 2) == NativePageGate.Decision.READY;
        gate.requested(13000, 2);
        assert gate.check(57999, 1, 1, "root", 2) == NativePageGate.Decision.WAIT;
        assert gate.check(58000, 1, 1, "root", 2) == NativePageGate.Decision.TIMEOUT;
        assert gate.check(58001, 1, 1, "root", 3) == NativePageGate.Decision.READY;
        gate.requested(58001, 3);
        assert gate.check(58002, 1, 1, "reply", 9) == NativePageGate.Decision.READY;
        gate.requested(58002, 9);
        assert gate.check(58003, 1, 2, "reply", 9) == NativePageGate.Decision.READY;
        gate.requested(58003, 9);
        assert gate.check(58004, 2, 1, "root", 0) == NativePageGate.Decision.READY;
        gate.requested(58004, 0);
        gate.localCompleted();
        assert gate.check(60004, 2, 1, "root", 0) == NativePageGate.Decision.WAIT;
        assert gate.check(61004, 2, 1, "root", 0) == NativePageGate.Decision.READY;
        gate.requested(61004, 0);
        assert gate.check(64004, 2, 1, "root", 0) == NativePageGate.Decision.WAIT;
        assert gate.check(106004, 2, 1, "root", 0) == NativePageGate.Decision.TIMEOUT;
        assert gate.check(107000, 2, 1, "reply", 20) == NativePageGate.Decision.READY;
        gate.requested(107000, 20);
        assert gate.check(110000, 2, 1, "reply", 21) == NativePageGate.Decision.READY;
        System.out.println("PASS: 16 native paging gate assertions");
        NativeEofGuard guard = new NativeEofGuard();
        assert !guard.persistent(1000, 7, 1, 5);
        assert !guard.persistent(15999, 7, 1, 5);
        assert guard.persistent(16000, 7, 1, 5);
        assert !guard.persistent(16001, 7, 1, 6);          // a new response restarts the timer
        assert !guard.persistent(30000, 7, 1, 6);
        assert guard.persistent(31001, 7, 1, 6);
        assert !guard.persistent(31002, 7, 2, 6);          // new generation restarts
        guard.reset();
        assert !guard.persistent(40000, 7, 2, 6);          // k0 true / EOF / pause reset
        assert !guard.persistent(54999, 7, 2, 6) && guard.persistent(55000, 7, 2, 6);
        // A reset gate (pause/resume, post covered) must not time out on a stale request.
        NativePageGate stale = new NativePageGate();
        stale.requested(1000, 1);
        stale.reset();
        assert stale.check(100000, 1, 1, "root", 1) == NativePageGate.Decision.READY;
        System.out.println("PASS: eof guard / gate reset assertions");
        assert TargetPostGate.onTarget(true, "9", "9");
        assert !TargetPostGate.onTarget(true, "9", "8") && !TargetPostGate.onTarget(false, "9", "9") && !TargetPostGate.onTarget(true, "", "");
        TargetPostGate nav = new TargetPostGate();
        assert !nav.tryNavigate(100000, 95000, 1, 1);      // touched 5 s ago
        assert nav.tryNavigate(100000, 90000, 1, 1);       // idle 10 s: attempt 1
        assert !nav.tryNavigate(120000, 0, 1, 1);          // within 45 s gap
        assert nav.tryNavigate(145000, 0, 1, 1);           // attempt 2
        assert nav.tryNavigate(190000, 0, 1, 1);           // attempt 3
        assert !nav.tryNavigate(500000, 0, 1, 1) && nav.exhausted();
        assert nav.tryNavigate(500000, 0, 1, 2) && !nav.exhausted(); // resume/new generation re-arms
        nav.reset();
        assert nav.tryNavigate(600000, 0, 1, 2);
        TargetPostGate open = new TargetPostGate();
        assert !open.tryOpenComments(100000, 95000);       // user touched 5 s ago
        assert open.tryOpenComments(100000, 0);
        assert !open.tryOpenComments(105999, 0) && open.tryOpenComments(106000, 0);
        assert open.tryOpenComments(112000, 0) && open.tryOpenComments(118000, 0) && open.tryOpenComments(124000, 0);
        assert !open.tryOpenComments(130000, 0) && open.openExhausted();
        open.newVisit();                                    // left and returned, or drawer opened
        assert !open.openExhausted() && open.tryOpenComments(131000, 0);
        System.out.println("PASS: target post gate assertions");
        assert NativePageGate.isUiFallbackState("native_fallback_ui");
        assert !NativePageGate.isUiFallbackState("native_eof_pending");
        System.out.println("PASS: native stall fallback policy assertion");
    }
}
