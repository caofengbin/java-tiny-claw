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
import github.javatinyclaw.internal.tools.WriteFileTool;

public class Main {

    public static void main(String[] args) {
        // 通过命令行参数接收用户的 prompt
        String prompt = "";
        for (int i = 0; i < args.length; i++) {
            if ("-prompt".equals(args[i]) && i + 1 < args.length) {
                prompt = args[i + 1];
                break;
            }
        }

        if (prompt.isEmpty()) {
            System.out.println("用法: java -jar target/java-tiny-claw-1.0.0.jar -prompt \"你的任务指令\"");
            System.exit(1);
        }

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

        // 挂载 4 大基础工具
        Registry registry = RegistryImpl.newRegistry();
        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        registry.register(EditFileTool.newEditFileTool(workDir));

        // 3.实例化引擎.引擎本身变成无状态的，它不绑定 WorkDir（仅适用于本讲演示）
        AgentEngine eng = new AgentEngine(llmProvider, registry, false, true);
        // 4.【注入新实现的终端输出器】
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        // 我们使用一个固定的 SessionID，以便在多次运行之间共享基于内存的“短期工作记忆”。
        // (在真实的 CLI 中，如果进程重启，Session 的内存历史其实是丢失的。
        // 但这正是我们要演示的重点：即便短期内存丢失，只要 TODO.md 还在，任务就能继续！)
        String sessionID = "task_web_server_01";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);

        System.err.printf("%n>>> 🚀 收到指令: %s%n", prompt);

        // 将用户的 Prompt 压入 Session
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
