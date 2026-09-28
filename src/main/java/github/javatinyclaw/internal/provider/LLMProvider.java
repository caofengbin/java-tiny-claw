package github.javatinyclaw.internal.provider;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.util.List;

// LLMProvider 定义了与大模型通信的统一契约
public interface LLMProvider {
    // Generate 接收当前的上下文历史、可用工具列表，并发起一次大模型推理
    Message generate(Context ctx, List<Message> messages, List<ToolDefinition> availableTools) throws Exception;
}
