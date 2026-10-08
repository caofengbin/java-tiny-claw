package github.javatinyclaw.internal.feishu;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

// ApprovalManager 统一管理当前正在等待人类审批的任务
public class ApprovalManager {
    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock();
    // Key 是用于审批的唯一 TaskID，Value 是接收审批结果的 Channel
    private final Map<String, BlockingQueue<ApprovalResult>> pendingTasks = new HashMap<>();

    // 全局单例，方便在 Registry Middleware 和 Feishu Webhook 之间共享状态
    public static final ApprovalManager globalApprovalMgr = new ApprovalManager();

    // WaitForApproval 发送飞书通知，并阻塞当前协程等待回调结果
    public ApprovalResult waitForApproval(String taskID, String toolName, String args, FeishuReporter reporter) {
        // 1. 创建用于阻塞当前引擎协程的 channel (容量为 1 防止死锁)
        BlockingQueue<ApprovalResult> ch = new ArrayBlockingQueue<>(1);

        mu.writeLock().lock();
        try {
            pendingTasks.put(taskID, ch);
        } finally {
            mu.writeLock().unlock();
        }

        // 2. 通过 Reporter 向飞书发送请求信息
        // (在实际的高级应用中，这里可以构建一张带有交互 Button 的精致飞书卡片)
        String noticeMsg = String.format(
                "⚠️ **高危操作审批请求**\nAgent 试图执行以下动作:\n- 工具: %s\n- 参数: %s\n\n任务 ID: **%s**\n\n👉 请在此消息下方回复 \"approve %s\" 或 \"reject %s\" 来决定是否放行。",
                toolName, args, taskID, taskID, taskID);

        // 注意：因为 Middleware 的签名里没有带 Reporter，我们在 main.go 里初始化时必须把 reporter 传进来
        if (reporter != null) {
            reporter.sendMsg(noticeMsg);
        } else {
            // 回退到终端打印 (兼容本地 CLI 模式)
            System.out.printf("\n\033[31m[需要审批 TaskID: %s]\033[0m %s%n", taskID, noticeMsg);
        }

        System.err.printf("[Approval] 已发送审批请求 (TaskID: %s)，协程挂起等待...%n", taskID);

        // 3. 【驾驭核心】：死死阻塞，等待飞书 Webhook 唤醒！
        ApprovalResult result;
        try {
            result = ch.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }

        // 4. 获取到结果后，清理内存资源
        mu.writeLock().lock();
        try {
            pendingTasks.remove(taskID);
        } finally {
            mu.writeLock().unlock();
        }

        return result;
    }

    // ResolveApproval 由飞书 Webhook 回调触发，向 channel 发送信号解开阻塞
    public void resolveApproval(String taskID, boolean allowed, String reason) {
        mu.readLock().lock();
        BlockingQueue<ApprovalResult> ch;
        boolean exists;
        try {
            ch = pendingTasks.get(taskID);
            exists = ch != null;
        } finally {
            mu.readLock().unlock();
        }

        if (exists) {
            System.err.printf("[Approval] 收到来自飞书的审批结果 (TaskID: %s, Allowed: %s)%n", taskID, allowed);
            try {
                ch.put(new ApprovalResult(allowed, reason));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } else {
            System.err.printf("[Approval] 找不到对应的 TaskID: %s，可能已超时或处理完毕%n", taskID);
        }
    }

    // IsDangerousCommand 简单的正则检查黑名单，判断该工具调用是否需要审批
    public static boolean isDangerousCommand(String toolName, String args) {
        // 对于纯读取的工具，默认 YOLO 模式，全部放行
        if (!"bash".equals(toolName) && !"write_file".equals(toolName) && !"edit_file".equals(toolName)) {
            return false;
        }

        // 针对 bash 的高危模式匹配
        if ("bash".equals(toolName)) {
            String[] dangerousPatterns = {
                    "rm\\s+-r",   // 级联删除
                    "sudo\\s+",   // 提权
                    "drop\\s+",   // 数据库删除
                    ">.*\\.go",   // 恶意覆盖源代码
            };
            String text = args == null ? "" : args;
            for (String p : dangerousPatterns) {
                if (Pattern.compile(p).matcher(text).find()) {
                    return true;
                }
            }
        }
        return false;
    }
}
