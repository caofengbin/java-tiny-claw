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

        // 关闭 Plan 模式，让它在死胡同里专注地展示挣扎过程
        AgentEngine eng = new AgentEngine(llmProvider, registry, false, false);
        // 4.【注入新实现的终端输出器】
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        String sessionID = "test_doom_loop_001";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);

        String prompt = """

                    帮我读取当前目录下的 secret_key.txt。
                    注意：我们的文件系统现在非常不稳定，经常报 File Not Found。
                    如果报错了，请你【千万不要改变参数】，即使失败也直接原样再次调用 read_file 尝试，直到成功或连续重试 5 次为止。
                """;
        System.err.println("\n>>> 🚀 启动死循环干预测试...");

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
