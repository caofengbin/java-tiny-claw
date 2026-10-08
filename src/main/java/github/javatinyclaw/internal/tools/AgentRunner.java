package github.javatinyclaw.internal.tools;

import github.javatinyclaw.context.Context;

// AgentRunner 是一个打破循环依赖的抽象接口。
// 因为 SubagentTool 存在于 tools 包，而完整的 AgentEngine 存在于 engine 包。
// 为了让 Tool 能拉起 Engine，我们定义一个接口供外部注入。
public interface AgentRunner {
    // RunSub 启动一个匿名的、一次性的子智能体任务，并返回其最终梳理出的纯文本总结
    String runSub(Context ctx, String taskPrompt, Registry readOnlyRegistry, Object reporter) throws Exception;
}
