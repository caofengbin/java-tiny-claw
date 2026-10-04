package github.javatinyclaw.internal.context;

import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Compactor 负责监控和压缩上下文内存，防止大模型发生 OOM
public class Compactor {
    public int maxChars;       // 触发压缩的最大字符数阈值 (水位线，可参考使用的大模型的token窗口大小)
    public int retainLastMessages; // Working Memory 保护区：最近的 N 条消息

    public static Compactor newCompactor(int maxChars, int retainLastMsgs) {
        Compactor compactor = new Compactor();
        compactor.maxChars = maxChars;
        compactor.retainLastMessages = retainLastMsgs;
        return compactor;
    }

    // Compact 接收准备发送给大模型的消息数组。
    // 如果总长度超标，对远期历史区进行全量掩码 (Masking)，对短期保护区进行超长局部截断 (Truncation)。
    public List<Message> compact(List<Message> msgs) {
        // 腾讯 MaaS 要求发出的 messages 里至少有一条真正的 user。
        // 带 toolCallId 的 user 会被 Provider 发成 role:tool，不能算。
        // 这条校验必须在水位判断之前：没超阈值时也会直接返回。
        msgs = ensureUserMessage(msgs);

        int currentLength = estimateLength(msgs);

        // 如果没有超过水位线，直接返回原数组 (大多数情况下的正常路径)
        if (currentLength < maxChars) {
            return msgs;
        }

        System.err.printf("[Compactor] ⚠️ 内存告警：当前上下文长度 (%d 字符) 超过阈值 (%d)，触发压缩清理...%n", currentLength, maxChars);

        List<Message> compacted = new ArrayList<>();
        int msgCount = msgs.size();

        // 计算受保护的 Working Memory 起始索引
        int protectStartIndex = msgCount - retainLastMessages;
        if (protectStartIndex < 0) {
            protectStartIndex = 0;
        }

        for (int i = 0; i < msgCount; i++) {
            Message msg = msgs.get(i);
            // 1. 系统提示词 (System Prompt) 绝对不能动，直接保留
            if (Role.SYSTEM.equals(msg.role)) {
                compacted.add(msg);
                continue;
            }

            // 我们必须拷贝一份新消息，因为在并发环境中直接修改原引用可能导致底层数据结构被污染
            Message newMsg = copyMessage(msg);

            boolean isInWorkingMemory = i >= protectStartIndex;

            // 【核心驾驭逻辑】: 双重降级防线
            if (Role.USER.equals(msg.role) && msg.toolCallId != null && !msg.toolCallId.isEmpty()) {
                // 对于工具的返回结果 (Observation/ToolResult)
                if (!isInWorkingMemory) {
                    // 【第一道防线：远期历史】如果是早期对话，执行无情替换 (Full Masking)
                    int contentLen = byteLen(msg.content);
                    if (contentLen > 200) {
                        newMsg.content = String.format("...[为了节省内存，早期的工具输出已被系统强制清理。原始长度: %d 字节]...", contentLen);
                    }
                } else {
                    // 【第二道防线：短期记忆】即使处于近期保护区，只要单条内容过大，也必须截断防 OOM (Head-Tail Truncation)
                    // 我们保留前 500 字符和后 500 字符（掐头去尾法，大模型通常只需要看开头报错和结尾总结）
                    final int maxKeep = 1000;
                    byte[] contentBytes = utf8Bytes(msg.content);
                    if (contentBytes.length > maxKeep) {
                        String head = new String(contentBytes, 0, 500, StandardCharsets.UTF_8);
                        String tail = new String(contentBytes, contentBytes.length - 500, 500, StandardCharsets.UTF_8);
                        newMsg.content = String.format("%s\n\n...[内容过长，中间 %d 字节已被系统截断]...\n\n%s", head, contentBytes.length - maxKeep, tail);
                    }
                }
            } else if (Role.ASSISTANT.equals(msg.role) && msg.content != null && !msg.content.isEmpty()) {
                // 对于大模型的冗长推理废话 (Thinking Trace)
                if (!isInWorkingMemory && byteLen(msg.content) > 200) {
                    newMsg.content = "...[早期的推理思考过程已折叠]...";
                }
            }

            // 注意：我们绝不会去动 msg.ToolCalls，因为这是模型行动的证据，是维系逻辑链的关键！
            compacted.add(newMsg);
        }

        int newLength = estimateLength(compacted);
        System.err.printf("[Compactor] ✅ 压缩完成。上下文长度从 %d 降至 %d 字符。%n", currentLength, newLength);

        return compacted;
    }

    // ensureUserMessage 在开头连续的 system 消息之后补一条 user。
    // 原始用户指令若已被 Working Memory 裁掉，这里恢复不了原文。
    private List<Message> ensureUserMessage(List<Message> msgs) {
        if (msgs == null) {
            msgs = new ArrayList<>();
        }
        for (Message msg : msgs) {
            if (Role.USER.equals(msg.role) && (msg.toolCallId == null || msg.toolCallId.isEmpty())) {
                return msgs;
            }
        }

        int insertAt = 0;
        while (insertAt < msgs.size() && Role.SYSTEM.equals(msgs.get(insertAt).role)) {
            insertAt++;
        }

        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = "请根据已有对话、PLAN.md 和 TODO.md，继续执行尚未完成的任务。";

        List<Message> out = new ArrayList<>(msgs.size() + 1);
        out.addAll(msgs.subList(0, insertAt));
        out.add(userMsg);
        out.addAll(msgs.subList(insertAt, msgs.size()));

        System.err.printf("[Compactor] 上下文中缺少 user 消息，已在系统提示后补入一条以适配腾讯 MaaS%n");
        return out;
    }

    // estimateLength 粗略计算当前上下文的总字符长度
    private int estimateLength(List<Message> msgs) {
        int length = 0;
        for (Message msg : msgs) {
            length += byteLen(msg.content);
            if (msg.toolCalls == null) {
                continue;
            }
            for (ToolCall tc : msg.toolCalls) {
                length += byteLen(tc.name) + (tc.arguments == null ? 0 : tc.arguments.length);
            }
        }
        return length;
    }

    // Go 的 copy 拷贝的是 Message 结构体。Java 里 Message 是引用，这里复制字段，避免改到原消息。
    private static Message copyMessage(Message src) {
        Message dst = new Message();
        dst.role = src.role;
        dst.content = src.content;
        dst.toolCalls = src.toolCalls;
        dst.toolCallId = src.toolCallId;
        return dst;
    }

    // Go 的 len(string) 是 UTF-8 字节数。
    private static int byteLen(String s) {
        return utf8Bytes(s).length;
    }

    private static byte[] utf8Bytes(String s) {
        if (s == null || s.isEmpty()) {
            return new byte[0];
        }
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
