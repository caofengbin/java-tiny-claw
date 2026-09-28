package github.javatinyclaw.internal.tools;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;
import github.javatinyclaw.internal.schema.ToolResult;

import java.util.List;

// Registry 定义了工具的注册与分发执行接口
public interface Registry {
    // GetAvailableTools 返回当前系统挂载的所有可用工具的 Schema
    List<ToolDefinition> getAvailableTools();

    // Execute 实际执行模型请求的工具，并返回结果
    ToolResult execute(Context ctx, ToolCall call);
}
