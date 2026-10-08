package github.javatinyclaw.internal.tools;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolCall;

// MiddlewareFunc 定义了中间件的签名。
// 它接收当前的 ToolCall，并返回一个是否允许执行的布尔值 (allowed)，以及拦截时的原因 (rejectReason)。
@FunctionalInterface
public interface MiddlewareFunc {
    MiddlewareResult apply(Context ctx, ToolCall call);
}
