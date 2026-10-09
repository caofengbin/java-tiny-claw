package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.context.SessionManager;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.TerminalReporter;
import github.javatinyclaw.internal.observability.CostTracker;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.WriteFileTool;

public class Main {

    public static void main(String[] args) {
        // 确保已设置 ZHIPU_API_KEY
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量");
            System.exit(1);
        }

        String workDir = System.getProperty("user.dir") + "/workspace";
        String modelName = "glm-5.3-flash";

        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider realProvider = OpenAIProvider.newZhipuOpenAIProvider(modelName);

        String sessionID = "test_observability_001";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);

        // 2. 核心拼装：用 Tracker 将真实的大脑包裹起来
        LLMProvider trackedProvider = CostTracker.newCostTracker(realProvider, modelName, sess);

        // 为主智能体准备全功能注册表
        Registry mainRegistry = RegistryImpl.newRegistry();
        mainRegistry.register(ReadFileTool.newReadFileTool(workDir));
        mainRegistry.register(WriteFileTool.newWriteFileTool(workDir));
        mainRegistry.register(BashTool.newBashTool(workDir));
        mainRegistry.register(EditFileTool.newEditFileTool(workDir));

        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        // 初始化主引擎
        AgentEngine eng = new AgentEngine(trackedProvider, mainRegistry, false, false);

        // 触发一个跨工具类型的并发任务
        String prompt = """
            
            为了加快执行速度，请你在一轮回复中，【同时并行】完成以下两件事：
            1. 使用 bash 工具执行 'sleep 2 && echo "系统环境检查完毕"'
            2. 使用 write_file 工具，在当前目录下创建一个 'trace_test.md'，内容写上 "测试并发的写入"。
            请确保你是分别调用两个不同的工具，不要试图把它们合并成一个命令！
            """;

        System.err.println("\n>>> 🚀 启动带 Tracing 链路追踪的测试...");
        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = prompt;
        sess.append(userMsg);

        try {
            eng.run(Context.background(), sess, reporter);
        } catch (Exception err) {
            System.err.printf("引擎运行崩溃: %s%n", err);
            System.exit(1);
        }

        System.err.printf("%n================ 财务报表 ================%n");
        System.err.printf("会话 ID: %s%n", sess.id);
        System.err.printf("总消耗 Input Tokens: %d%n", sess.totalPromptTokens);
        System.err.printf("总消耗 Output Tokens: %d%n", sess.totalCompletionTokens);
        System.err.printf("总计费用 (CNY): ¥%.6f%n", sess.totalCostCNY);
        System.err.printf("==========================================%n");
    }
}
