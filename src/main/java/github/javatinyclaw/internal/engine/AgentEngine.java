package github.javatinyclaw.internal.engine;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.PromptComposer;
import github.javatinyclaw.internal.context.Session;
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
import java.util.concurrent.CountDownLatch;

// AgentEngine 是微型 OS 的核心驱动
public class AgentEngine {
    private final LLMProvider provider;
    private final Registry registry;
    public boolean enableThinking;

    // 【注意】：我们移除了 Engine 层级的 WorkDir，因为 WorkDir 现在应该跟随 Session 走！
    public AgentEngine(LLMProvider p, Registry r, boolean enableThinking) {
        this.provider = p;
        this.registry = r;
        this.enableThinking = enableThinking;
    }

    // 【核心改造】: 移除 userPrompt 参数，改为接收一个具体的 Session 实例
    public void run(Context ctx, Session session, Reporter reporter) throws Exception {
        logPrintf("[Engine] 唤醒会话 [%s]，锁定工作区: %s%n", session.id, session.workDir);

        // 根据当前 Session 的工作区，动态组装最新的 System Prompt
        PromptComposer composer = PromptComposer.newPromptComposer(session.workDir);
        Message systemMsg = composer.build();

        while (true) {
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 1. 【上下文组装】: System Prompt + 截取最近的 6 条消息作为 Working Memory
            // 在实际业务中，由于工具返回结果可能很长，短期工作记忆往往设为 6-10 条足以维系连贯对话
            List<Message> workingMemory = session.getWorkingMemory(6);

            List<Message> contextHistory = new ArrayList<Message>();
            contextHistory.add(systemMsg);
            contextHistory.addAll(workingMemory);

            // 2. ================= Phase 1: Thinking =================
            if (enableThinking) {
                if (reporter != null) {
                    reporter.onThinking(ctx);
                }

                Message thinkResp;
                try {
                    thinkResp = provider.generate(ctx, contextHistory, null);
                } catch (Exception err) {
                    throw new Exception("Thinking 阶段失败: " + err.getMessage(), err);
                }
                if (thinkResp.content != null && !thinkResp.content.isEmpty()) {
                    // 将思考过程持久化到 Session 中！
                    session.append(thinkResp);
                    // 把它追加到当前这一轮的临时上下文中，供 Action 阶段使用
                    contextHistory.add(thinkResp);
                }
            }

            // 3. ================= Phase 2: Action =================
            Message actionResp;
            try {
                actionResp = provider.generate(ctx, contextHistory, availableTools);
            } catch (Exception err) {
                throw new Exception("Action 阶段失败: " + err.getMessage(), err);
            }

            // 将大模型的行动响应持久化到 Session 中
            session.append(actionResp);
            contextHistory.add(actionResp);

            if (actionResp.content != null && !actionResp.content.isEmpty() && reporter != null) {
                reporter.onMessage(ctx, actionResp.content);
            }

            int toolCallCount = actionResp.toolCalls == null ? 0 : actionResp.toolCalls.size();
            if (toolCallCount == 0) {
                // 如果没有工具调用，说明本次任务已完成，打破 ReAct 循环，挂起等待人类的下一条指令
                break;
            }

            // 4. ================= 并发执行底层工具 =================
            Message[] observationMses = new Message[toolCallCount];
            CountDownLatch wg = new CountDownLatch(toolCallCount);

            for (int i = 0; i < toolCallCount; i++) {
                final int idx = i;
                final ToolCall call = actionResp.toolCalls.get(i);

                Thread.startVirtualThread(() -> {
                    try {
                        if (reporter != null) {
                            String args = call.arguments == null ? "" : new String(call.arguments, StandardCharsets.UTF_8);
                            reporter.onToolCall(ctx, call.name, args);
                        }

                        ToolResult result = registry.execute(ctx, call);

                        if (reporter != null) {
                            String displayOutput = result.output;
                            if (displayOutput != null) {
                                byte[] outputBytes = displayOutput.getBytes(StandardCharsets.UTF_8);
                                if (outputBytes.length > 200) {
                                    displayOutput = new String(outputBytes, 0, 200, StandardCharsets.UTF_8) + "... (已截断)";
                                }
                            }
                            reporter.onToolResult(ctx, call.name, displayOutput, result.isError);
                        }

                        Message obsMsg = new Message();
                        obsMsg.role = Role.USER;
                        obsMsg.content = result.output;
                        obsMsg.toolCallId = call.id;
                        observationMses[idx] = obsMsg;
                    } finally {
                        wg.countDown();
                    }
                });
            }

            wg.await();

            // 将所有的工具执行结果（Observation）持久化到 Session 中，开启下一轮的复盘与推理
            session.append(observationMses);
        }
    }

    private static void logPrintf(String format, Object... args) {
        System.err.printf(format, args);
    }
}
