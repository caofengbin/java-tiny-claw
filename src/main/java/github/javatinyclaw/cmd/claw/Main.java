package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.context.SessionManager;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.TerminalReporter;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.SubagentTool;
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
        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        // 【防御沙箱】为子智能体准备受限的只读注册表
        Registry readOnlyRegistry = RegistryImpl.newRegistry();
        readOnlyRegistry.register(ReadFileTool.newReadFileTool(workDir));
        readOnlyRegistry.register(BashTool.newBashTool(workDir)); // 允许简单的 grep 等搜索操作

        // 为主智能体准备全功能注册表
        Registry mainRegistry = RegistryImpl.newRegistry();
        mainRegistry.register(ReadFileTool.newReadFileTool(workDir));
        mainRegistry.register(WriteFileTool.newWriteFileTool(workDir));
        mainRegistry.register(BashTool.newBashTool(workDir));
        mainRegistry.register(EditFileTool.newEditFileTool(workDir));

        // 初始化主引擎
        AgentEngine eng = new AgentEngine(llmProvider, mainRegistry, false, false);

        // 【核心装配】：将带有 Engine 引用和只读 Registry 的 Subagent 工具注册进主线
        mainRegistry.register(SubagentTool.newSubagentTool(eng, readOnlyRegistry, reporter));

        String sessionID = "test_subagent_001";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);

        String prompt = "\n    我需要你在这个遗留项目里，找到那个“核心密码”。\n    为了防止污染主上下文，请你务必派出子智能体（spawn_subagent）去执行探索任务。\n    你可以让子智能体使用 bash 去查找当前目录（及其所有子目录）下名为 config.txt 的文件。\n    子智能体拿到密码向你汇报后，请你亲自使用 write_file 工具，将密码写在根目录的 answer.txt 里。\n    ";

        System.err.println("\n>>> 🚀 启动多智能体协同测试...");
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
    }
}
