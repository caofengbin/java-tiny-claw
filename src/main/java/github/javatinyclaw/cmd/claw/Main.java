package github.javatinyclaw.cmd.claw;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.context.SessionManager;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.TerminalReporter;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;

import java.util.concurrent.CountDownLatch;

public class Main {

    public static void main(String[] args) {
        // 确保已设置 ZHIPU_API_KEY
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量");
            System.exit(1);
        }

        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");

        // 2. 初始化真实的工具注册表
        Registry registry = RegistryImpl.newRegistry();
        registry.register(ReadFileTool.newReadFileTool("/tmp/project_front"));

        // 3.实例化引擎.引擎本身变成无状态的，它不绑定 WorkDir（仅适用于本讲演示）
        AgentEngine eng = new AgentEngine(llmProvider, registry, false);
        // 4.【注入新实现的终端输出器】
        TerminalReporter reporter = TerminalReporter.newTerminalReporter();

        CountDownLatch wg = new CountDownLatch(2);

        // ================= 模拟并发场景 1：飞书前端群 =================
        Thread.startVirtualThread(() -> {
            try {
                Session sessionA = SessionManager.globalSessionMgr.getOrCreate("chat_front_001", "/tmp/project_front");

                // 回合 1：获取机密
                System.err.println("\n>>> 🙋‍♂️ [Session A / Turn 1]: 帮我看看 README.md 里记录了什么密钥？");
                sessionA.append(userMessage("帮我看看 README.md 里记录了什么密钥？"));
                try {
                    eng.run(Context.background(), sessionA, reporter);
                } catch (Exception ignored) {
                }

                // 故意制造大量“废话”对话，刷掉记忆 (假设 Working Memory Limit=6)
                for (int i = 0; i < 6; i++) {
                    sessionA.append(userMessage("这只是一句闲聊占位符。"));
                    sessionA.append(assistantMessage("好的，收到闲聊。"));
                }

                // 回合 2：验证记忆截断 (此时第一轮的密钥已经被挤出 Working Memory 了！)
                System.err.println("\n>>> 🙋‍♂️ [Session A / Turn 2]: 请直接告诉我，刚才第一轮你查到的那个密钥是什么？");
                sessionA.append(userMessage("请直接告诉我，刚才第一轮你查到的那个密钥是什么？不准调用工具！"));
                try {
                    eng.run(Context.background(), sessionA, reporter);
                } catch (Exception ignored) {
                }
            } finally {
                wg.countDown();
            }
        });

        // ================= 模拟并发场景 2：飞书后端群 =================
        Thread.startVirtualThread(() -> {
            try {
                // 稍微错开一点时间发起请求
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }

                Session sessionB = SessionManager.globalSessionMgr.getOrCreate("chat_back_002", "/tmp/project_back");

                System.err.println("\n>>> 🙋‍♂️ [Session B]: 别人查到了一个密钥，你这里能看到吗？");
                sessionB.append(userMessage("别人查到了一个密钥，你这里能看到吗？不准调用工具！"));
                try {
                    eng.run(Context.background(), sessionB, reporter);
                } catch (Exception ignored) {
                }
            } finally {
                wg.countDown();
            }
        });

        try {
            wg.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Message userMessage(String content) {
        Message message = new Message();
        message.role = Role.USER;
        message.content = content;
        return message;
    }

    private static Message assistantMessage(String content) {
        Message message = new Message();
        message.role = Role.ASSISTANT;
        message.content = content;
        return message;
    }
}
