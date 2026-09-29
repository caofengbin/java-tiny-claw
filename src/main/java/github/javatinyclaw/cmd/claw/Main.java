package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;
import github.javatinyclaw.internal.tools.Registry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class Main {

    // ==========================================
    // 1. 伪造的大模型 Provider
    // ==========================================
    static class MockProvider implements LLMProvider {
        int turn;

        // 模拟大模型的响应：第一轮请求执行 bash，第二轮输出最终结果
        @Override
        public Message generate(Context ctx, List<Message> msgs, List<ToolDefinition> tools) {
            // 如果工具列表为空，说明这是引擎发起的 Phase 1: Thinking 阶段
            if (tools == null || tools.isEmpty()) {
                Message msg = new Message();
                msg.role = Role.ASSISTANT;
                msg.content = "【推理中】目标是检查文件。我不能直接盲猜，我需要先调用 bash 工具执行 ls 命令，看看当前目录下有什么，然后再做定夺。";
                return msg;
            }

            // 如果工具列表不为空，说明这是 Phase 2: Action 阶段
            turn++;
            if (turn == 1) {
                // 第一轮 Action：顺着刚才的 Thinking，精准调用工具
                Message msg = new Message();
                msg.role = Role.ASSISTANT;
                msg.content = "我要执行我刚才计划的步骤了。";
                ToolCall call = new ToolCall();
                call.id = "call_123";
                call.name = "bash";
                call.arguments = "{\"command\": \"ls -la\"}".getBytes(StandardCharsets.UTF_8);
                List<ToolCall> toolCalls = new ArrayList<ToolCall>();
                toolCalls.add(call);
                msg.toolCalls = toolCalls;
                return msg;
            }

            // 第二轮 Action：直接总结退出
            Message msg = new Message();
            msg.role = Role.ASSISTANT;
            msg.content = "根据工具返回的结果，我看到了 main.go，任务圆满完成！";
            return msg;
        }
    }

    // ==========================================
    // 2. 伪造的 Tool Registry
    // ==========================================
    static class MockRegistry implements Registry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
            ToolDefinition bash = new ToolDefinition();
            bash.name = "bash";
            tools.add(bash);
            return tools;
        }

        @Override
        public ToolResult execute(Context ctx, ToolCall call) {
            // 直接返回一段伪造的终端输出
            ToolResult result = new ToolResult();
            result.toolCallId = call.id;
            result.output = "-rw-r--r--  1 user group  234 Oct 24 10:00 main.go\n";
            result.isError = false;
            return result;
        }
    }

    // ==========================================
    // 3. 组装运行
    // ==========================================
    public static void main(String[] args) {
        // 获取当前执行目录作为 WorkDir 物理边界
        String workDir = System.getProperty("user.dir");
        if (workDir == null) {
            workDir = "";
        }

        MockProvider p = new MockProvider();
        MockRegistry r = new MockRegistry();

        // 实例化引擎，开启 EnableThinking = true
        AgentEngine eng = new AgentEngine(p, r, workDir, true);

        // 发起任务指令
        try {
            eng.run(Context.background(), "帮我检查当前目录的文件");
        } catch (Exception err) {
            System.err.printf("引擎崩溃: %s%n", err.getMessage());
            System.exit(1);
        }
    }
}
