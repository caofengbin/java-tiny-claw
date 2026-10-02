package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.TerminalReporter;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
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
        if (apiKey == null || apiKey.equals("")) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量");
            System.exit(1);
        }

        // 获取当前执行目录作为 WorkDir 物理边界
        String workDir = System.getProperty("user.dir");
        if (workDir == null) {
            workDir = "";
        }
        // 这里需要改下到单独的目录
        workDir += "/workspace";

        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");
//        LLMProvider llmProvider = ClaudeProvider.newZhipuClaudeProvider("glm-5.3-flash");

        // 2. 初始化真实的工具注册表
        Registry registry = RegistryImpl.newRegistry();

        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        registry.register(EditFileTool.newEditFileTool(workDir));

        // 3.实例化引擎，开启 EnableThinking = true (开启慢思考，促使模型一次性统筹规划)
        AgentEngine eng = new AgentEngine(llmProvider, registry, workDir, true);

        // 【注入新实现的终端输出器】
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        String prompt = "\n"
                + "    我需要在当前目录下新建一个 ping.go，提供一个简单的 http ping 接口。\n"
                + "    写完之后，帮我把代码用 git 提交一下。\n"
                + "    ";

        try {
            eng.run(Context.background(), prompt, reporter);
        } catch (Exception err) {
            System.err.printf("引擎运行崩溃: %s%n", err.getMessage());
            System.exit(1);
        }
    }
}
