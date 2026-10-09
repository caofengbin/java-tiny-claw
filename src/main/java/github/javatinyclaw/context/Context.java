package github.javatinyclaw.context;

// Context 对齐 Go 标准库 context.Context。
// 当前实现支持 background 与 value 级联，不实现取消、超时。
public final class Context {
    private static final Context BACKGROUND = new Context();

    private final Context parent;
    private final Object key;
    private final Object val;

    private Context() {
        this.parent = null;
        this.key = null;
        this.val = null;
    }

    private Context(Context parent, Object key, Object val) {
        this.parent = parent;
        this.key = key;
        this.val = val;
    }

    public static Context background() {
        return BACKGROUND;
    }

    public Context withValue(Object key, Object value) {
        return new Context(this, key, value);
    }

    public Object value(Object key) {
        Context current = this;
        while (current != null) {
            if (current.key == key || (current.key != null && current.key.equals(key))) {
                return current.val;
            }
            current = current.parent;
        }
        return null;
    }
}
