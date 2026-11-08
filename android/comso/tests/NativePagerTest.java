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
    }
}
