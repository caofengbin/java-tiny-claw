package github.javatinyclaw.internal.feishu;

// ApprovalResult 审批结果包
public class ApprovalResult {
    public boolean allowed;
    public String reason;

    public ApprovalResult(boolean allowed, String reason) {
        this.allowed = allowed;
        this.reason = reason;
    }
}
