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
        public Message generate(Context ctx, List<Message> msgs, List<ToolDefinition> unused) {
            turn++;
            if (turn == 1) {
                Message msg = new Message();
                msg.role = Role.ASSISTANT;
                msg.content = "让我来看看当前目录下有什么文件。";
                ToolCall call = new ToolCall();
                call.id = "call_123";
                call.name = "bash";
                call.arguments = "{\"command\": \"ls -la\"}".getBytes(StandardCharsets.UTF_8);
                List<ToolCall> toolCalls = new ArrayList<ToolCall>();
                toolCalls.add(call);
                msg.toolCalls = toolCalls;
                return msg;
            }

            Message msg = new Message();
            msg.role = Role.ASSISTANT;
            msg.content = "我看到了文件列表，里面包含 Main.java，任务完成！";
            return msg;
        }
    }

    // ==========================================
    // 2. 伪造的 Tool Registry
    // ==========================================
    static class MockRegistry implements Registry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            return null;
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

        // 实例化核心引擎
        AgentEngine eng = new AgentEngine(p, r, workDir);

        // 发起任务指令
        try {
            eng.run(Context.background(), "帮我检查当前目录的文件");
        } catch (Exception err) {
            System.err.printf("引擎崩溃: %s%n", err.getMessage());
            System.exit(1);
        }
    }
}
