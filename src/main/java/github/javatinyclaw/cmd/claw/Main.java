package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.provider.ClaudeProvider;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;
import github.javatinyclaw.internal.tools.Registry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    // ==========================================
    // 2. 伪造的工具注册表 (用于测试 Provider 的工具提取能力)
    // ==========================================
    static class MockRegistry implements Registry {
        @Override
        public List<ToolDefinition> getAvailableTools() {
            List<ToolDefinition> tools = new ArrayList<ToolDefinition>();
            ToolDefinition weather = new ToolDefinition();
            weather.name = "get_weather";
            weather.description = "获取指定城市的当前天气情况。";

            Map<String, Object> city = new HashMap<String, Object>();
            city.put("type", "string");

            Map<String, Object> properties = new HashMap<String, Object>();
            properties.put("city", city);

            List<String> required = new ArrayList<String>();
            required.add("city");

            Map<String, Object> inputSchema = new HashMap<String, Object>();
            inputSchema.put("type", "object");
            inputSchema.put("properties", properties);
            inputSchema.put("required", required);
            weather.inputSchema = inputSchema;

            tools.add(weather);
            return tools;
        }

        @Override
        public ToolResult execute(Context ctx, ToolCall call) {
            System.err.printf("  -> [Mock 工具执行] 获取 %s 的天气中...%n", call.name);
            ToolResult result = new ToolResult();
            result.toolCallId = call.id;
            result.output = "API 返回：今天是晴天，气温 25 度。";
            result.isError = false;
            return result;
        }
    }

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

        // 2. 注入伪造的工具注册表
        Registry registry = new MockRegistry();

        // 3. 实例化并运行引擎，开启 EnableThinking = true (开启慢思考阶段！)
        AgentEngine eng = new AgentEngine(llmProvider, registry, workDir, false);

        // 设定测试任务
        String prompt = "我想去北京跑步，帮我查查天气适合吗？";

        // 发起任务指令
        try {
            eng.run(Context.background(), prompt);
        } catch (Exception err) {
            System.err.printf("引擎崩溃: %s%n", err.getMessage());
            System.exit(1);
        }
    }
}
