package github.javatinyclaw.internal.tools;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// RegistryImpl 是 Registry 接口的默认实现
public class RegistryImpl implements Registry {
    // 使用 map 以工具的 Name 作为 Key 进行快速 O(1) 路由查找
    private final Map<String, BaseTool> tools = new HashMap<>();
    // 【新增】保存挂载的中间件链
    private final List<MiddlewareFunc> middlewares = new ArrayList<>();

    public static Registry newRegistry() {
        return new RegistryImpl();
    }

    @Override
    public void use(MiddlewareFunc mw) {
        middlewares.add(mw);
    }

    @Override
    public void register(BaseTool tool) {
        String name = tool.name();
        if (tools.containsKey(name)) {
            System.err.printf("[Warning] 工具 '%s' 已经被注册，将被覆盖。%n", name);
        }
        tools.put(name, tool);
        System.err.printf("[Registry] 成功挂载工具: %s%n", name);
    }

    @Override
    public List<ToolDefinition> getAvailableTools() {
        List<ToolDefinition> defs = new ArrayList<ToolDefinition>();
        for (BaseTool tool : tools.values()) {
            defs.add(tool.definition());
        }
        return defs;
    }

    @Override
    public ToolResult execute(Context ctx, ToolCall call) {
        // 1. 路由查找：如果在注册表中找不到该工具，这是模型产生了幻觉，直接向模型抛出错误
        BaseTool tool = tools.get(call.name);
        if (tool == null) {
            String errMsg = String.format("Error: 系统中不存在名为 '%s' 的工具。", call.name);
            ToolResult result = new ToolResult();
            result.toolCallId = call.id;
            result.output = errMsg;
            result.isError = true; // 标记为错误，模型看到后会尝试纠正
            return result;
        }

        // 2. 【核心防御】在执行底层逻辑前，依次运行所有的 Middleware
        for (MiddlewareFunc mw : middlewares) {
            MiddlewareResult decision = mw.apply(ctx, call);
            if (!decision.allowed) {
                System.err.printf("[Registry] ⚠️ 工具 %s 被 Middleware 拦截: %s%n", call.name, decision.rejectReason);
                ToolResult result = new ToolResult();
                result.toolCallId = call.id;
                result.output = String.format("执行被系统拦截。原因: %s", decision.rejectReason);
                result.isError = true; // 必须返回 Error，强制大模型阅读拒绝理由
                return result;
            }
        }

        // 3. 执行工具逻辑 (如果所有 Middleware 都放行了)
        String output;
        try {
            output = tool.execute(ctx, call.arguments);
        } catch (Exception err) {
            // 4. 封装结果：将执行结果或底层物理错误封装后返回给 Main Loop
            String errMsg = String.format("Error executing %s: %s", call.name, err.getMessage());
            ToolResult result = new ToolResult();
            result.toolCallId = call.id;
            result.output = errMsg;
            result.isError = true;
            return result;
        }

        ToolResult result = new ToolResult();
        result.toolCallId = call.id;
        result.output = output;
        result.isError = false;
        return result;
    }
}
