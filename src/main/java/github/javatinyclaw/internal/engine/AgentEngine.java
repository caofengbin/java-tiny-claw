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
    public boolean enableThinking; // 【新增】慢思考模式开关

    public AgentEngine(LLMProvider p, Registry r, String workDir, boolean enableThinking) {
        this.provider = p;
        this.registry = r;
        this.workDir = workDir;
        this.enableThinking = enableThinking;
    }

    // Run 启动 Agent 的生命周期
    public void run(Context ctx, String userPrompt) throws Exception {
        logPrintf("[Engine] 引擎启动，锁定工作区: %s%n", workDir);
        logPrintf("[Engine] 慢思考模式 (Thinking Phase): %s%n", enableThinking);

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

            // ====================================================================
            // Phase 1: 慢思考阶段 (Thinking) - 剥夺工具，强制规划
            // ====================================================================
            if (enableThinking) {
                logPrintln("[Engine][Phase 1] 剥夺工具访问权，强制进入慢思考与规划阶段...");

                // 核心机制：传入的 availableTools 为 null！
                // 大模型看不到任何 JSON Schema，被迫只能输出纯文本的思考过程。
                Message thinkResp;
                try {
                    thinkResp = provider.generate(ctx, contextHistory, null);
                } catch (Exception err) {
                    throw new Exception("Thinking 阶段生成失败: " + err.getMessage(), err);
                }

                // 如果模型输出了思考过程，我们将其作为 Assistant 消息追加到上下文中
                if (thinkResp.content != null && !thinkResp.content.equals("")) {
                    // Go 用 fmt.Printf 走 stdout、log 走 stderr。IDE 会先展示整段 stderr 再拼 stdout，
                    // 两条流无法交错，这里改到同一条 stderr，才能看到真实的 Turn 顺序。
                    logPrintf("🧠 [内部思考 Trace]: %s%n", thinkResp.content);
                    contextHistory.add(thinkResp);
                }
            }

            // ====================================================================
            // Phase 2: 行动阶段 (Action) - 恢复工具，顺着规划执行
            // ====================================================================
            logPrintln("[Engine][Phase 2] 恢复工具挂载，等待模型采取行动...");

            // 此时的 contextHistory 中已经包含了上一阶段模型自己的 Thinking Trace。
            // 模型会顺着自己的逻辑，结合恢复的 availableTools 发起精准的工具调用。
            Message actionResp;
            try {
                actionResp = provider.generate(ctx, contextHistory, availableTools);
            } catch (Exception err) {
                throw new Exception("Action 阶段生成失败: " + err.getMessage(), err);
            }

            contextHistory.add(actionResp);

            if (actionResp.content != null && !actionResp.content.equals("")) {
                logPrintf("🤖 [对外回复]: %s%n", actionResp.content);
            }

            // ====================================================================
            // 退出与执行逻辑 (与上一讲保持一致)
            // ====================================================================
            int toolCallCount = actionResp.toolCalls == null ? 0 : actionResp.toolCalls.size();
            if (toolCallCount == 0) {
                logPrintln("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            logPrintf("[Engine] 模型请求调用 %d 个工具...%n", toolCallCount);

            for (ToolCall toolCall : actionResp.toolCalls) {
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
