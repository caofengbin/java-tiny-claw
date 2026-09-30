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

        // 3. 实例化并运行引擎，开启 EnableThinking = true (开启慢思考阶段！)
        AgentEngine eng = new AgentEngine(llmProvider, registry, workDir, false);

        // 发起一个需要局部修改的指令
        String prompt =
                "\n" +
                "    我当前目录下有一个 server.go 文件。\n" +
                "    请帮我把里面 \"TODO: 增加鉴权逻辑\" 下面的那个 if 语句，整个替换为：\n" +
                "    if user == nil {\n" +
                "        fmt.Println(\"Forbidden!\")\n" +
                "        return\n" +
                "    }\n" +
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
