package github.javatinyclaw.internal.schema;

// ToolResult 代表工具在本地执行完毕后返回的物理结果
public class ToolResult {
    public String toolCallId;
    public String output;   // 工具执行的控制台输出或报错堆栈
    public boolean isError; // 标记是否失败，供后续的驾驭工程进行错误自愈
}
