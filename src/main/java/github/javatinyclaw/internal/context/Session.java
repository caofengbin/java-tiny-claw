package github.javatinyclaw.internal.context;

import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

// Session 代表了一次持续的人机交互过程。它负责维护该会话的完整历史。
public class Session {
    public String id;
    public String workDir; // 该会话绑定的物理工作区
    public Instant createdAt;
    public Instant updatedAt;

    // 【新增】用于统计该 Session 累计消耗的资源
    public int totalPromptTokens;
    public int totalCompletionTokens;
    public double totalCostCNY;

    // 存放此 Session 中所有的用户输入、大模型回复和工具调用结果
    private final List<Message> history = new ArrayList<>();
    private final ReentrantReadWriteLock mu = new ReentrantReadWriteLock(); // 读写锁，防止并发读写历史时发生 Data Race

    // RecordUsage 是一个给外部 Tracker 调用的辅助方法，用于累加账单
    public void recordUsage(int prompt, int completion, double cost) {
        mu.writeLock().lock();
        try {
            totalPromptTokens += prompt;
            totalCompletionTokens += completion;
            totalCostCNY += cost;
        } finally {
            mu.writeLock().unlock();
        }
    }

    public static Session newSession(String id, String workDir) {
        Session session = new Session();
        session.id = id;
        session.workDir = workDir;
        session.createdAt = Instant.now();
        session.updatedAt = Instant.now();
        return session;
    }

    // Append 线程安全地向 Session 中追加消息
    public void append(Message... megs) {
        mu.writeLock().lock();
        // 把解锁登记到函数退出时执行
        try {
            if (megs != null) {
                Collections.addAll(history, megs);
            }
            updatedAt = Instant.now();

            // 【持久化预留点】：在真实的工业级实现中（如 Claude Code），
            // 我们会在这里将 history 以 JSONL 的格式 Append 到 workDir/.claw/sessions/xxx.jsonl 中。
            // saveToDisk()
        } finally {
            mu.writeLock().unlock();
        }
    }

    // GetWorkingMemory 是驾驭工程的核心！
    // 它不返回全量历史，而是从后往前截取最近的 N 条消息，形成 Agent 的“短期工作记忆”。
    public List<Message> getWorkingMemory(int limit) {
        mu.readLock().lock();
        try {
            int total = history.size();
            if (total <= limit || limit <= 0) {
                // 如果历史总量小于限制，或者不设限，全量返回 (需要深拷贝以防外部修改)
                List<Message> res = new ArrayList<>(total);
                for (Message msg : history) {
                    res.add(copyMessage(msg));
                }
                return res;
            }

            // 截取最近的 limit 条消息
            List<Message> res = new ArrayList<>(limit);
            for (int i = total - limit; i < total; i++) {
                res.add(copyMessage(history.get(i)));
            }

            // 【驾驭防线】：大模型 API 强制要求历史消息的连续性！
            // 如果我们截断的第一条消息恰好是一个 ToolResult (RoleUser 且含有 ToolCallID)，
            // 但发出这个请求的 ToolCall 被我们截断抛弃了，大模型 API 会直接报 400 Bad Request。
            // 因此，如果切片首条属于“孤儿”工具响应，我们必须将其强行舍弃，顺延到下一条正常的 User/Assistant 消息。
            while (!res.isEmpty()) {
                Message first = res.getFirst();
                if (Role.USER.equals(first.role) && first.toolCallId != null && !first.toolCallId.isEmpty()) {
                    res.removeFirst();
                } else {
                    break;
                }
            }

            return res;
        } finally {
            mu.readLock().unlock();
        }
    }

    // Go 的 copy 拷贝的是 Message 结构体。Java 里 Message 是引用，这里复制字段，避免调用方改到会话原文。
    private static Message copyMessage(Message src) {
        Message dst = new Message();
        dst.role = src.role;
        dst.content = src.content;
        dst.toolCalls = src.toolCalls;
        dst.toolCallId = src.toolCallId;
        dst.usage = src.usage;
        return dst;
    }
}
