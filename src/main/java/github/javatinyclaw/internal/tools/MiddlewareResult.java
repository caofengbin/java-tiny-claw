package github.javatinyclaw.internal.tools;

// 承接 Go MiddlewareFunc 的双返回值 (allowed, rejectReason)
public class MiddlewareResult {
    public boolean allowed;
    public String rejectReason;

    public MiddlewareResult(boolean allowed, String rejectReason) {
        this.allowed = allowed;
        this.rejectReason = rejectReason;
    }
}
