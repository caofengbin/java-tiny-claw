package github.javatinyclaw.internal.schema;

// ToolDefinition 描述了一个大模型可以调用的工具元信息 (供模型理解工具有什么用)
public class ToolDefinition {
    public String name;
    public String description;
    public Object inputSchema; // 对应 JSON Schema
}
