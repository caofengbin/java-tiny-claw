package github.javatinyclaw.internal.engine;

import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

// ReminderInjector 负责在运行时监控上下文，并在模型陷入执念时动态注入强力打断信息
public class ReminderInjector {
    // 用于记录连续失败的工具调用指纹 (ToolName + Arguments 的 Hash)
    private Map<String, Integer> consecutiveFailures;

    public static ReminderInjector newReminderInjector() {
        ReminderInjector injector = new ReminderInjector();
        injector.consecutiveFailures = new HashMap<>();
        return injector;
    }

    // generateFingerprint 生成工具调用的唯一指纹，用于判断大模型是否在重复相同的动作
    static String generateFingerprint(String toolName, byte[] args) {
        try {
            MessageDigest hasher = MessageDigest.getInstance("MD5");
            hasher.update(toolName == null ? new byte[0] : toolName.getBytes(StandardCharsets.UTF_8));
            if (args != null) {
                hasher.update(args);
            }
            byte[] digest = hasher.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // CheckAndInject 分析本轮的执行结果，决定是否要在 Context 尾部追加 Reminder
    // 返回的 schema.Message 将作为最新的用户输入，强制大模型优先阅读。
    public Message checkAndInject(ToolCall lastToolCall, ToolResult lastResult) {
        String fingerprint = generateFingerprint(lastToolCall.name, lastToolCall.arguments);

        // 如果工具执行成功，说明 Agent 在这条路径上走通了，清空所有失败计数器
        if (!lastResult.isError) {
            consecutiveFailures = new HashMap<>();
            return null;
        }

        // 如果执行失败，累加该特征的失败次数
        int failCount = consecutiveFailures.getOrDefault(fingerprint, 0) + 1;
        consecutiveFailures.put(fingerprint, failCount);

        System.err.printf("[Reminder] 监控到工具 %s 执行失败，该参数特征连续失败次数: %d%n", lastToolCall.name, failCount);

        // 【驾驭底线】：触发死循环打断机制！
        // 我们设定阈值为 3 次。如果大模型连续 3 次都在同一个地方跌倒，必须强行打断它的局部执念。
        if (failCount >= 3) {
            System.err.println("[Reminder] ⚠️ 触发死循环干预！注入强力修正指令。");

            // 构造一条极其严厉的行动指南
            String nudgeMsg = String.format(
                    """
                            [SYSTEM REMINDER 警告]\s
                            你似乎陷入了死循环。你刚刚连续 %d 次使用相同的参数调用了 '%s' 工具，并且都失败了。
                            请立即停止这种无效的重试！你的注意力被当前的报错过度吸引了。
                            你需要：
                            1. 停止猜测参数。跳出当前的局部思维。
                            2. 彻底改变你的策略。
                            3. 如果你确实无法通过系统工具解决当前问题，请直接结束任务并向用户说明你需要什么人工帮助，而不是继续盲目消耗 API 资源尝试。
                            """,
                    failCount, lastToolCall.name);

            Message msg = new Message();
            msg.role = Role.USER; // 【核心】必须是 RoleUser，以保证在下一次 API 请求时拥有最高的近因效应权重
            msg.content = nudgeMsg;
            return msg;
        }

        return null;
    }
}
