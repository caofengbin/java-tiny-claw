package github.javatinyclaw.internal.eval;

// TestCase 定义了一个需要 Agent 去完成并验证的独立任务
public class TestCase {
    public String id;             // 用例唯一标识
    public String name;           // 用例名称
    public String setupScript;    // 【可选】在 Agent 运行前执行的 bash 脚本 (用于初始化靶机代码)
    public String taskPrompt;     // 发送给 Agent 的任务指令
    public String validateScript; // 【核心】在 Agent 运行结束后执行的 bash 校验脚本。exit 0 视为成功，其他视为失败
    public int maxTurns;          // 允许 Agent 尝试的最大轮数 (超时算失败)
}
