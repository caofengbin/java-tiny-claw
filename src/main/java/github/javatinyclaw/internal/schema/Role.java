package github.javatinyclaw.internal.schema;

// Role 定义消息的角色，这是与大模型沟通的基石
public final class Role {
    public static final String SYSTEM = "system";       // 系统提示词：确立 Agent 的性格与红线
    public static final String USER = "user";           // 用户输入 / 工具执行的返回结果 (Observation)
    public static final String ASSISTANT = "assistant"; // 模型的输出：包含推理(Reasoning)或工具调用(ToolCall)

    private Role() {
    }
}
