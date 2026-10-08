package github.javatinyclaw.internal.engine;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Compactor;
import github.javatinyclaw.internal.context.PromptComposer;
import github.javatinyclaw.internal.context.RecoveryManager;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;
import github.javatinyclaw.internal.tools.AgentRunner;
import github.javatinyclaw.internal.tools.Registry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

// AgentEngine 是微型 OS 的核心驱动
public class AgentEngine implements AgentRunner {
    private final LLMProvider provider;
    private final Registry registry;
    public boolean enableThinking;
    public boolean planMode; // 【新增】计划模式开关
    private final Compactor compactor; // 【新增】压缩器实例
    private final RecoveryManager recovery; // 【新增】自愈管理器
    private final ReminderInjector injector; // 【新增】提醒注入器

    // 【注意】：我们移除了 Engine 层级的 WorkDir，因为 WorkDir 现在应该跟随 Session 走！
    public AgentEngine(LLMProvider p, Registry r, boolean enableThinking, boolean planMode) {
        this.provider = p;
        this.registry = r;
        this.enableThinking = enableThinking;
        this.planMode = planMode;
        // 【初始化压缩器】：为了便于今天的极端测试，我们将水位线阈值设积极（例如 3000 字符），
        // 并保护最近的 6 条消息（大约两轮 Turn 的交互）
        this.compactor = Compactor.newCompactor(20000, 6);
        this.recovery = RecoveryManager.newRecoveryManager(); // 初始化 Recovery
        this.injector = ReminderInjector.newReminderInjector(); // 【初始化注入器】
    }

    // 【核心改造】: 移除 userPrompt 参数，改为接收一个具体的 Session 实例
    public void run(Context ctx, Session session, Reporter reporter) throws Exception {
        logPrintf("[Engine] 唤醒会话 [%s]，锁定工作区: %s%n", session.id, session.workDir);

        // 根据当前 Session 的工作区，动态组装最新的 System Prompt
        PromptComposer composer = PromptComposer.newPromptComposer(session.workDir, planMode);
        Message systemMsg = composer.build();

        while (true) {
            List<ToolDefinition> availableTools = registry.getAvailableTools();

            // 1. 从 Session 提取出近期的 Working Memory (例如最近 20 条，给压缩器留下充足的判断空间)
            List<Message> workingMemory = session.getWorkingMemory(20);

            List<Message> contextHistory = new ArrayList<>();
            contextHistory.add(systemMsg);
            contextHistory.addAll(workingMemory);

            // 2. 【核心注入点】: 在向 Provider 发起推理前，过一遍内存压缩器！
            // 无论你带出了多少上下文，如果字符总数超标，早期日志将被掩码化，超大日志将被掐头去尾
            List<Message> compactedContext = compactor.compact(contextHistory);

            // 3. 后续的 Provider.Generate 全面使用被保护过的新鲜上下文 (compactedContext)
            // ================= Phase 1: Thinking =================
            if (enableThinking) {
                if (reporter != null) {
                    reporter.onThinking(ctx);
                }

                Message thinkResp;
                try {
                    thinkResp = provider.generate(ctx, compactedContext, null);
                } catch (Exception err) {
                    throw new Exception("Thinking 阶段失败: " + err.getMessage(), err);
                }
                if (thinkResp.content != null && !thinkResp.content.isEmpty()) {
                    // 将思考过程持久化到 Session 中！
                    session.append(thinkResp);
                    // 把它追加到当前这一轮的临时上下文中，供 Action 阶段使用
                    compactedContext.add(thinkResp);
                }
            }

            // 3. ================= Phase 2: Action =================
            Message actionResp;
            try {
                actionResp = provider.generate(ctx, compactedContext, availableTools);
            } catch (Exception err) {
                throw new Exception("Action 阶段失败: " + err.getMessage(), err);
            }

            // 【驾驭精髓】：注意，写入 Session（硬盘/全量内存）的永远是全量的真实响应，不受 Compact 影响！
            // Compact 只作用于本轮发给大模型的那个临时 Context。
            session.append(actionResp);
            compactedContext.add(actionResp);

            if (actionResp.content != null && !actionResp.content.isEmpty() && reporter != null) {
                reporter.onMessage(ctx, actionResp.content);
            }

            int toolCallCount = actionResp.toolCalls == null ? 0 : actionResp.toolCalls.size();
            if (toolCallCount == 0) {
                // 如果没有工具调用，说明本次任务已完成，打破 ReAct 循环，挂起等待人类的下一条指令
                break;
            }

            // 4. ================= 执行工具并记录 Observation,并注入自愈模板 =================
            Message[] observationMses = new Message[toolCallCount];
            CountDownLatch wg = new CountDownLatch(toolCallCount);

            // 用于收集本轮执行的最后一个工具，供 Reminder 探测器分析
            // (在真实的工业级架构中，如果并发调用了多个工具，我们可以逐个分析或仅分析报错的那个。这里简化为取第一个)
            ToolCall[] lastToolCall = new ToolCall[] { new ToolCall() };
            ToolResult[] lastToolResult = new ToolResult[] { new ToolResult() };

            for (int i = 0; i < toolCallCount; i++) {
                final int idx = i;
                final ToolCall call = actionResp.toolCalls.get(i);

                Thread.startVirtualThread(() -> {
                    try {
                        if (reporter != null) {
                            String args = call.arguments == null ? "" : new String(call.arguments, StandardCharsets.UTF_8);
                            reporter.onToolCall(ctx, call.name, args);
                        }

                        // 底层物理执行工具
                        ToolResult result = registry.execute(ctx, call);

                        // 【核心拦截与注入】
                        String finalOutput = result.output;
                        if (result.isError) {
                            // 发生错误，交由 RecoveryManager 诊断并注入“锦囊妙计”
                            finalOutput = recovery.analyzeAndInject(call.name, result.output);
                            logPrintf("  -> [Java-%d] ❌ 注入救援指南: %s%n", idx, finalOutput);
                        } else {
                            int outputBytes = result.output == null ? 0 : result.output.getBytes(StandardCharsets.UTF_8).length;
                            logPrintf("  -> [Java-%d] ✅ 工具执行成功 (返回 %d 字节)%n", idx, outputBytes);
                        }

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

                        // 将注入过 Recovery Hint 的最终结果写入上下文历史
                        Message obsMsg = new Message();
                        obsMsg.role = Role.USER;
                        obsMsg.content = finalOutput;
                        obsMsg.toolCallId = call.id;
                        observationMses[idx] = obsMsg;

                        // 捕获状态供外部探测器使用
                        if (idx == 0) {
                            lastToolCall[0] = call;
                            lastToolResult[0] = result;
                        }
                    } finally {
                        wg.countDown();
                    }
                });
            }

            wg.await();

            // 1. 先将普通的工具执行结果存入 Session
            session.append(observationMses);

            // 2. 【核心防线】：在准备进入下一轮之前，进行死循环探测！
            Message reminderMsg = injector.checkAndInject(lastToolCall[0], lastToolResult[0]);
            if (reminderMsg != null) {
                // 如果触发了干预规则，将这条严厉的提醒作为 User 消息，强制追加到 Session 的最末尾！
                // 大模型在下一轮被唤醒时，第一眼就会看到这句话，从而打破局部执念。
                session.append(reminderMsg);
            }
        }
    }

    // RunSub 是专为 Subagent 拉起的一次性受限循环。
    // 它不依赖外部 Session，打完就跑。
    // Reporter：为了让用户在终端看到子智能体的工作轨迹，我们将主线程的 Reporter 透传进来，并打上特殊标记。
    @Override
    public String runSub(Context ctx, String taskPrompt, Registry readOnlyRegistry, Object reporter) throws Exception {
        // 【核心优化】：子智能体极其容易偷懒。我们必须在 System Prompt 中严厉警告它必须使用工具！
        List<Message> contextHistory = new ArrayList<>();

        Message systemMsg = new Message();
        systemMsg.role = Role.SYSTEM;
        systemMsg.content = """
你是一个专门负责深度探索的探路者 (Explorer Subagent)。
你的任务是根据主架构师的指令，在当前工作区内仔细阅读代码、查阅日志，搜集足够的信息。

【核心纪律】
1. 你必须、且只能依靠内置工具（如 bash 的 find/grep，或 read_file）去寻找答案。绝对不允许凭空捏造或猜测！
2. 如果你没有找到确切的答案，你必须继续使用工具深入搜索。
3. 当且仅当你找到了确切的线索后，停止调用工具，直接输出一段纯文本作为你的终极汇报。主架构师会根据你的汇报来做下一步决策。""";
        contextHistory.add(systemMsg);

        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = taskPrompt;
        contextHistory.add(userMsg);

        // 限制子智能体最多只能跑 10 个 Turn，防止它自己卡死
        final int maxSubTurns = 10;
        int turnCount = 0;

        while (true) {
            turnCount++;
            if (turnCount > maxSubTurns) {
                throw new Exception(String.format("子智能体探索过于深入，超过 %d 轮被强制召回，请主 Agent 给它更明确的指令", maxSubTurns));
            }

            // 【驾驭底线】：子智能体仅能获取传入的只读工具注册表
            List<ToolDefinition> availableTools = readOnlyRegistry.getAvailableTools();

            List<Message> compactedContext = compactor.compact(contextHistory);

            // 子任务要求急速响应，强制关闭主体的慢思考，直接预测行动
            Message actionResp;
            try {
                actionResp = provider.generate(ctx, compactedContext, availableTools);
            } catch (Exception err) {
                throw new Exception("子智能体推理失败: " + err.getMessage(), err);
            }

            contextHistory.add(actionResp);

            // 【核心退出条件】：子智能体一旦不调用工具了，说明它做好了总结汇报
            int toolCallCount = actionResp.toolCalls == null ? 0 : actionResp.toolCalls.size();
            if (toolCallCount == 0) {
                // 直接将它的这段汇报内容剥离出来返回给上层
                return actionResp.content;
            }

            // 执行只读工具的并发循环
            Message[] observationMsgs = new Message[toolCallCount];
            CountDownLatch wg = new CountDownLatch(toolCallCount);

            for (int i = 0; i < toolCallCount; i++) {
                final int idx = i;
                final ToolCall call = actionResp.toolCalls.get(i);

                Thread.startVirtualThread(() -> {
                    try {
                        // 【可视化的关键】：让终端用户看到 Subagent 正在干嘛
                        Reporter r = null;
                        if (reporter != null) {
                            r = (Reporter) reporter;
                            String args = call.arguments == null ? "" : new String(call.arguments, StandardCharsets.UTF_8);
                            r.onToolCall(ctx, String.format("[Subagent] %s", call.name), args);
                        }

                        ToolResult result = readOnlyRegistry.execute(ctx, call);

                        String finalOutput = result.output;
                        if (result.isError) {
                            finalOutput = recovery.analyzeAndInject(call.name, result.output);
                        }

                        if (reporter != null) {
                            String display = finalOutput;
                            if (display != null) {
                                byte[] displayBytes = display.getBytes(StandardCharsets.UTF_8);
                                if (displayBytes.length > 200) {
                                    display = new String(displayBytes, 0, 200, StandardCharsets.UTF_8) + "... (已截断)";
                                }
                            }
                            r.onToolResult(ctx, String.format("[Subagent] %s", call.name), display, result.isError);
                        }

                        Message obsMsg = new Message();
                        obsMsg.role = Role.USER;
                        obsMsg.content = finalOutput;
                        obsMsg.toolCallId = call.id;
                        observationMsgs[idx] = obsMsg;
                    } finally {
                        wg.countDown();
                    }
                });
            }

            wg.await();
            Collections.addAll(contextHistory, observationMsgs);
        }
    }

    private static void logPrintf(String format, Object... args) {
        System.err.printf(format, args);
    }
}
