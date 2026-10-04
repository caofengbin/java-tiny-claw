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

        String workDir = System.getProperty("user.dir");
        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");

        // 2. 初始化真实的工具注册表
        Registry registry = RegistryImpl.newRegistry();
        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));

        // 3.实例化引擎.引擎本身变成无状态的，它不绑定 WorkDir（仅适用于本讲演示）
        AgentEngine eng = new AgentEngine(llmProvider, registry, false);
        // 4.【注入新实现的终端输出器】
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        String sessionID = "test_oom_protection_001";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);

        // 发起一个会导致读取大文件的恶意任务
        String prompt = """
                    请帮我执行以下三个步骤：
                    1. 使用 bash 执行 echo "开始排查日志"
                    2. 使用 read_file 工具读取当前目录下的巨大文件 mock_log.txt
                    3. 使用 bash 执行 date 命令获取当前时间，并告诉我任务全部完成。
                    要求工具按顺序依次执行，不要并发调用
                    \
                """;
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
