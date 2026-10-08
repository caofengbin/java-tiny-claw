package github.javatinyclaw.internal.schema;

import java.util.List;

// Message 代表上下文中传递的单条消息
public class Message {
    public String role;    // 对应 json:"role"
    public String content; // 存放纯文本内容

    // 如果模型决定调用工具，此字段将被填充 (支持并行调用多个工具)
    public List<ToolCall> toolCalls;

    // 如果这是对某个工具调用的响应，此字段必须填写，以告知模型上下文的关联性
    public String toolCallId;

    // 【新增】如果这是大模型 (Assistant) 的回复，此字段存放本次调用的 Token 消耗
    public Usage usage;
}
