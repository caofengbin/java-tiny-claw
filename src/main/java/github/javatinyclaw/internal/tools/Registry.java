package github.javatinyclaw.internal.tools;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;

import java.util.List;

// Registry 定义了工具的注册与分发接口 -- 增加挂载 Middleware 的方法
public interface Registry {
    // Register 挂载一个新的工具到系统中
    void register(BaseTool tool);

    void use(MiddlewareFunc mw); // 【新增】全局 Middleware 挂载点

    // GetAvailableTools 返回当前系统挂载的所有工具的 Schema，供 Main Loop 交给 Provider
    List<ToolDefinition> getAvailableTools();

    // Execute 实际路由并执行模型请求的工具调用
    ToolResult execute(Context ctx, ToolCall call);
}
