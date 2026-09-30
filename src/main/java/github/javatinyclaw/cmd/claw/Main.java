package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.provider.ClaudeProvider;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.WriteFileTool;

public class Main {

    // ==========================================
    // 3. 组装运行
    // ==========================================
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

        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");
//        LLMProvider llmProvider = ClaudeProvider.newZhipuClaudeProvider("glm-5.3-flash");

        // 2. 初始化真实的工具注册表
        Registry registry = RegistryImpl.newRegistry();

        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        // 【新增挂载】
        registry.register(EditFileTool.newEditFileTool(workDir));

        // 3.实例化引擎，开启 EnableThinking = true (开启慢思考，促使模型一次性统筹规划)
        AgentEngine eng = new AgentEngine(llmProvider, registry, workDir, true);

        // 4.下发一个需要收集多源信息的任务
        String prompt =
                "\n" +
                "    我当前目录下有 a.txt, b.txt, c.txt 三个文件。\n" +
                "    为了节省时间，请你同时一次性读取这三个文件，并将它们的内容综合起来，告诉我它们分别记录了什么领域的信息。\n" +
                "    ";

        // 发起任务指令
        try {
            eng.run(Context.background(), prompt);
        } catch (Exception err) {
            System.err.printf("引擎崩溃: %s%n", err.getMessage());
            System.exit(1);
        }
    }
}
