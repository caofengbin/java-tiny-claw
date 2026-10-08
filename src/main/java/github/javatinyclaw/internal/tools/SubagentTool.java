package github.javatinyclaw.internal.tools;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SubagentTool implements BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentRunner runner;

    // 为子智能体准备的专属、受限的“只读”注册表
    private final Registry readOnlyRegistry;
    private final Object reporter; // 暂时用 Object 规避包循环依赖，底层通过断言使用

    public static SubagentTool newSubagentTool(AgentRunner runner, Registry readOnlyRegistry, Object reporter) {
        return new SubagentTool(runner, readOnlyRegistry, reporter);
    }

    private SubagentTool(AgentRunner runner, Registry readOnlyRegistry, Object reporter) {
        this.runner = runner;
        this.readOnlyRegistry = readOnlyRegistry;
        this.reporter = reporter;
    }

    @Override
    public String name() {
        return "spawn_subagent";
    }

    // Definition 向主 Agent 暴露这个工具的强大能力
    @Override
    public ToolDefinition definition() {
        ToolDefinition def = new ToolDefinition();
        def.name = name();
        def.description = "派出一个专门用于深度探索（Exploration）的子智能体。当你需要阅读大量代码、跨文件查找逻辑时请调用此工具。它在探索完毕后，会给你返回一份极度精炼的摘要报告。";

        Map<String, Object> taskPrompt = new HashMap<>();
        taskPrompt.put("type", "string");
        taskPrompt.put("description", "给子智能体下达的明确指令。");

        Map<String, Object> properties = new HashMap<>();
        properties.put("task_prompt", taskPrompt);

        List<String> required = new ArrayList<String>();
        required.add("task_prompt");

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);
        def.inputSchema = inputSchema;

        return def;
    }

    static class SubagentArgs {
        @JsonProperty("task_prompt")
        public String taskPrompt;
    }

    @Override
    public String execute(Context ctx, byte[] args) throws Exception {
        SubagentArgs input;
        try {
            input = OBJECT_MAPPER.readValue(args, SubagentArgs.class);
        } catch (Exception err) {
            throw new Exception("解析参数失败: " + err.getMessage(), err);
        }

        System.err.printf("[Subagent] 🚀 主 Agent 发起委派！正在拉起探路者: [%s]...%n", input.taskPrompt);

        // 【核心降维打击】：拉起一个完全物理隔离的子循环
        // 我们把针对该任务的专项指令传给子智能体，并仅提供 readOnlyRegistry。
        // (子智能体只能读文件或执行只读的 bash，不能搞破坏)
        String summary;
        try {
            summary = runner.runSub(ctx, input.taskPrompt, readOnlyRegistry, reporter);
        } catch (Exception err) {
            return "子智能体执行失败: " + err.getMessage();
        }

        System.err.printf("[Subagent] ✅ 子智能体任务结束。报告返回给主干...%n");

        // 最终，几万字的代码探索，化作了这一段轻量级的 Summary，
        // 就像一次普通的 API 调用一样，返回给了始终保持清醒的主 Agent。
        return String.format("【子智能体探索报告】:\n%s", summary);
    }
}
