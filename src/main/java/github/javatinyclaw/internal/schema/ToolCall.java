package github.javatinyclaw.internal.schema;

// ToolCall 代表模型请求调用某个具体的工具
public class ToolCall {
    public String id;      // 工具调用的唯一 ID
    public String name;    // 想要调用的工具名称 (例如 "bash")
    // Arguments 存放 JSON 参数。使用 byte[] 对齐 Go 的 json.RawMessage，将解析责任交给具体的工具
    public byte[] arguments;
}
