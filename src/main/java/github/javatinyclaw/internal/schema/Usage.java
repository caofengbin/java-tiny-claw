package github.javatinyclaw.internal.schema;

// Usage 记录了单次大模型 API 调用的 Token 消耗
public class Usage {
    public int promptTokens;     // 输入的 Token 数量
    public int completionTokens; // 产生的 Token 数量
}
