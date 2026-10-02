package github.javatinyclaw.internal.engine;

import github.javatinyclaw.context.Context;

import java.nio.charset.StandardCharsets;

// TerminalReporter 实现了 Reporter 接口，用于在终端直观地打印 Agent 的状态
public class TerminalReporter implements Reporter {
    public static TerminalReporter newTerminalReporter() {
        return new TerminalReporter();
    }

    private TerminalReporter() {
    }

    @Override
    public void onThinking(Context ctx) {
        System.out.printf("\n[🤔 思考中] 模型正在推理...\n");
    }

    @Override
    public void onToolCall(Context ctx, String toolName, String args) {
        System.out.printf("[🛠️ 调用工具] %s\n", toolName);
        // 截断过长的参数显示，保持终端清爽
        String displayArgs = args == null ? "" : args;
        displayArgs = displayArgs.replace("\n", "\\n");
        displayArgs = displayArgs.replace("\r", "\\r");
        byte[] argBytes = displayArgs.getBytes(StandardCharsets.UTF_8);
        if (argBytes.length > 150) {
            displayArgs = new String(argBytes, 0, 150, StandardCharsets.UTF_8) + "... (已截断)";
        }
        System.out.printf("   参数: %s\n", displayArgs);
    }

    @Override
    public void onToolResult(Context ctx, String toolName, String result, boolean isError) {
        if (isError) {
            System.out.printf("[❌ 执行失败] %s\n", toolName);
            // 显示错误信息
            if (result != null && !result.isEmpty()) {
                System.out.printf("   错误: %s\n", result);
            }
        } else {
            System.out.printf("[✅ 执行成功] %s\n", toolName);
        }
    }

    @Override
    public void onMessage(Context ctx, String content) {
        if (content == null || content.isEmpty()) {
            return;
        }
        System.out.printf("\n🤖 Agent 回复:\n%s\n\n", content);
    }
}
