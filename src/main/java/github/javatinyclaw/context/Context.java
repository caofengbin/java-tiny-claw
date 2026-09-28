package github.javatinyclaw.context;

// Context 对齐 Go 标准库 context.Context。
// 当前运行路径只传递 Context.background()，不实现取消、超时或 value。
public final class Context {
    private static final Context BACKGROUND = new Context();

    private Context() {
    }

    public static Context background() {
        return BACKGROUND;
    }
}
