package github.javatinyclaw.internal.engine;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.PromptComposer;
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
    private LLMProvider provider;
    private Registry registry;

    // WorkDir (工作区): 借鉴 OpenClaw 的理念，Agent 必须有一个明确的物理边界
    public String workDir;
    public boolean enableThinking; // 【新增】慢思考模式开关
    private PromptComposer composer; // 【新增】引擎持有 Composer 实例

    public AgentEngine(LLMProvider p, Registry r, String workDir, boolean enableThinking) {
        this.provider = p;
        this.registry = r;
        this.workDir = workDir;
        this.enableThinking = enableThinking;
        this.composer = PromptComposer.newPromptComposer(workDir); // 初始化组装器
    }

    // Run 启动 Agent 的生命周期
    // Run 方法新增了 Reporter 参数
    public void run(Context ctx, String userPrompt, Reporter reporter) throws Exception {
        logPrintf("[Engine] 引擎启动，锁定工作区: %s%n", workDir);
        logPrintf("[Engine] 慢思考模式 (Thinking Phase): %s%n", enableThinking);

        // 【核心修改】动态组装 System Prompt，彻底替换掉以前硬编码的面条提示词！
        Message systemMsg = composer.build();

        // 1. 初始化会话的 Context (上下文内存)
        // 在真实的场景中，这里会由动态 Prompt 组装器加载 AGENTS.md。目前我们先硬编码。
        List<Message> contextHistory = new ArrayList<Message>();
        contextHistory.add(systemMsg); // 注入动态组装的内核、AGENTS.md 与 Skills

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
                if (reporter != null) {
                    // 【触发 Reporter】: 开始慢思考
                    reporter.onThinking(ctx);
                }

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

            if (actionResp.content != null && !actionResp.content.equals("") && reporter != null) {
                // 【触发 Reporter】: 输出阶段性总结或最终回复
                reporter.onMessage(ctx, actionResp.content);
            }

            // ====================================================================
            // 退出与执行逻辑 (与上一讲保持一致)
            // ====================================================================
            int toolCallCount = actionResp.toolCalls == null ? 0 : actionResp.toolCalls.size();
            if (toolCallCount == 0) {
                logPrintln("[Engine] 模型未请求调用工具，任务宣告完成。");
                break;
            }

            logPrintf("[Engine] 模型请求并发调用 %d 个工具...%n", toolCallCount);

            // 【核心改造开始】: 从串行 (Sequential) 演进为并行 (Parallel)

            // 1. 预分配一个固定长度的数组，用于安全地存放各个并发工具的执行结果（Observation）
            // 长度与 ToolCalls 的数量完全一致
            Message[] observationMsgs = new Message[toolCallCount];

            // 2. 声明 CountDownLatch 用于阻塞等待所有虚拟线程完成，对应 Go 的 sync.WaitGroup
            CountDownLatch wg = new CountDownLatch(toolCallCount);

            // 3. 遍历模型请求的所有工具，为每一个工具单独 Fork 出一个虚拟线程
            for (int i = 0; i < toolCallCount; i++) {
                // 将索引和 toolCall 拷贝为最终变量，防止闭包捕获循环变量
                final int idx = i;
                final ToolCall call = actionResp.toolCalls.get(i);

                Thread.startVirtualThread(() -> {
                    try {
                        if (reporter != null) {
                            // 【触发 Reporter】: 报告即将在底层执行的工具
                            String args = call.arguments == null ? "" : new String(call.arguments, StandardCharsets.UTF_8);
                            reporter.onToolCall(ctx, call.name, args);
                            logPrintf("  -> [Go-%d] 🛠️ 触发并行执行: %s%n", idx, call.name);
                        }

                        // 调用底层 Registry 执行工具（物理操作）
                        ToolResult result = registry.execute(ctx, call);

                        if (reporter != null) {
                            // 为了防止大文件读取导致飞书消息过长被截断，我们仅汇报工具执行状态
                            // 注意：传递给大模型的 observationMsgs 依然是完整数据，只是人类看到的 Reporter 是缩略版
                            String displayOutput = result.output;
                            if (displayOutput != null) {
                                byte[] outputBytes = displayOutput.getBytes(StandardCharsets.UTF_8);
                                if (outputBytes.length > 200) {
                                    displayOutput = new String(outputBytes, 0, 200, StandardCharsets.UTF_8) + "... (已截断)";
                                }
                            }
                            // 【触发 Reporter】: 汇报工具物理执行的结果
                            reporter.onToolResult(ctx, call.name, displayOutput, result.isError);
                        }

                        // 将执行结果封装为一条用户消息 (Role.USER)
                        Message obsMsg = new Message();
                        obsMsg.role = Role.USER;
                        obsMsg.content = result.output;
                        obsMsg.toolCallId = call.id;

                        // 【线程安全】: 由于每个虚拟线程操作的是预分配数组的不同索引，
                        // 这里不需要加锁，性能极高！
                        observationMsgs[idx] = obsMsg;
                    } finally {
                        wg.countDown(); // 虚拟线程结束时计数器减一
                    }
                });
            }

            // 4. Join 阻塞等待：主循环挂起，直到所有的并发虚拟线程全部执行完毕
            wg.await();
            logPrintln("[Engine] 所有并发工具执行完毕，开始聚合观察结果 (Observation)...");

            // 5. 聚合装填：将并行的结果，按照原本的顺序，一次性追加到上下文时间线中
            for (Message obs : observationMsgs) {
                contextHistory.add(obs);
            }

            // 循环回到开头，模型将带着这一批新的 Observation 继续它的下一轮思考...
        }
    }

    private static void logPrintf(String format, Object... args) {
        System.err.printf(format, args);
    }

    private static void logPrintln(String msg) {
        System.err.println(msg);
    }
}
