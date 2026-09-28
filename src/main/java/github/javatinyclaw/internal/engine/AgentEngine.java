package github.javatinyclaw.internal.engine;

import github.javatinyclaw.context.Context;
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

// AgentEngine 是微型 OS 的核心驱动
public class AgentEngine {
    private LLMProvider provider;
    private Registry registry;

    // WorkDir (工作区): 借鉴 OpenClaw 的理念，Agent 必须有一个明确的物理边界
    public String workDir;

    public AgentEngine(LLMProvider p, Registry r, String workDir) {
        this.provider = p;
        this.registry = r;
        this.workDir = workDir;
    }

    // Run 启动 Agent 的生命周期
    public void run(Context ctx, String userPrompt) throws Exception {
        logPrintf("[Engine] 引擎启动，锁定工作区: %s%n", workDir);

        // 1. 初始化会话的 Context (上下文内存)
        // 在真实的场景中，这里会由动态 Prompt 组装器加载 AGENTS.md。目前我们先硬编码。
        List<Message> contextHistory = new ArrayList<Message>();

        Message systemMsg = new Message();
        systemMsg.role = Role.SYSTEM;
        systemMsg.content = "You are java-tiny-claw, an expert coding assistant. You have full access to tools in the workspace.";
        contextHistory.add(systemMsg);

        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = userPrompt;
        contextHistory.add(userMsg);

        int turnCount = 0;

        // 2. The Main Loop: 心跳开始 (标准的 ReAct 循环)
        while (true) {
            turnCount++;
            logPrintf("========== [Turn %d] 开始 ==========%n", turnCount);

            // 获取当前挂载的所有工具定义
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 向大模型发起推理请求 (包含 Reasoning)
            logPrintln("[Engine] 正在思考 (Reasoning)...");
            Message responseMsg;
            try {
                responseMsg = provider.generate(ctx, contextHistory, availableTools);
            } catch (Exception err) {
                throw new Exception("模型生成失败: " + err.getMessage(), err);
            }

            // 将模型的响应完整追加到上下文历史中
            contextHistory.add(responseMsg);

            // 如果模型回复了纯文本，打印出来 (这通常是它的思考过程，或是最终结果)
            if (responseMsg.content != null && !responseMsg.content.equals("")) {
                // Go 用 fmt.Printf 走 stdout、log 走 stderr。IDE 会先展示整段 stderr 再拼 stdout，
                // 两条流无法交错，这里改到同一条 stderr，才能看到真实的 Turn 顺序。
                logPrintf("🤖 模型: %s%n", responseMsg.content);
            }

            // 3. 退出条件判断
            // 如果模型没有请求任何工具调用，说明它认为任务已经完成，跳出循环。
            int toolCallCount = responseMsg.toolCalls == null ? 0 : responseMsg.toolCalls.size();
            if (toolCallCount == 0) {
                logPrintln("[Engine] 任务完成，退出循环。");
                break;
            }

            // 4. 执行行动 (Action) 与 获取观察结果 (Observation)
            logPrintf("[Engine] 模型请求调用 %d 个工具...%n", toolCallCount);

            for (ToolCall toolCall : responseMsg.toolCalls) {
                String arguments = toolCall.arguments == null ? "" : new String(toolCall.arguments, StandardCharsets.UTF_8);
                logPrintf("  -> 🛠️ 执行工具: %s, 参数: %s%n", toolCall.name, arguments);

                // 通过 Registry 路由并执行底层工具
                ToolResult result = registry.execute(ctx, toolCall);

                if (result.isError) {
                    logPrintf("  -> ❌ 工具执行报错: %s%n", result.output);
                } else {
                    int outputBytes = result.output == null ? 0 : result.output.getBytes(StandardCharsets.UTF_8).length;
                    logPrintf("  -> ✅ 工具执行成功 (返回 %d 字节)%n", outputBytes);
                }

                // 将工具执行的观察结果 (Observation) 封装为 User Message 追加到上下文中
                // 注意：ToolCallID 必须携带！这是维系大模型推理链条的关键
                Message observationMsg = new Message();
                observationMsg.role = Role.USER;
                observationMsg.content = result.output;
                observationMsg.toolCallId = toolCall.id;
                contextHistory.add(observationMsg);
            }

            // 循环回到开头，模型将带着新加入的 Observation 继续它的下一轮思考...
        }
    }

    private static void logPrintf(String format, Object... args) {
        System.err.printf(format, args);
    }

    private static void logPrintln(String msg) {
        System.err.println(msg);
    }
}
