package github.javatinyclaw.internal.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.Reporter;

import java.util.Map;

// FeishuBot 封装了飞书机器人的配置与核心业务流
public class FeishuBot {
    private final Client client;
    private final String appID;
    private final String appSecret;
    private final AgentEngine engine; // 持有核心引擎引用

    public FeishuBot(AgentEngine eng) {
        String appID = System.getenv("FEISHU_APP_ID");
        String appSecret = System.getenv("FEISHU_APP_SECRET");
        if (appID == null) {
            appID = "";
        }
        if (appSecret == null) {
            appSecret = "";
        }

        if (appID.equals("") || appSecret.equals("")) {
            System.err.println("请设置 FEISHU_APP_ID 和 FEISHU_APP_SECRET");
            System.exit(1);
        }

        // 实例化飞书官方客户端
        Client client = Client.newBuilder(appID, appSecret).build();

        this.client = client;
        this.appID = appID;
        this.appSecret = appSecret;
        this.engine = eng;
    }

    // GetEventDispatcher 用于注册到 HTTP 服务器，处理来自飞书的 POST 事件
    public EventDispatcher getEventDispatcher() {
        String encryptKey = System.getenv("FEISHU_ENCRYPT_KEY");
        String verifyToken = System.getenv("FEISHU_VERIFY_TOKEN");
        if (encryptKey == null) {
            encryptKey = "";
        }
        if (verifyToken == null) {
            verifyToken = "";
        }

        // 使用官方 SDK 构建调度器，监听 "接收消息" 事件
        EventDispatcher handler = EventDispatcher.newBuilder(verifyToken, encryptKey)
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        // 由于飞书消息体是 JSON，我们需要粗略地提取其中的文本内容。
                        // 这里简单处理：去掉开头结尾的特殊转义字符和引用的机器人名字。
                        String contentStr = event.getEvent().getMessage().getContent();
                        contentStr = trimPrefix(contentStr, "{\"text\":\"");
                        contentStr = trimSuffix(contentStr, "\"}");

                        String chatId = event.getEvent().getMessage().getChatId();
                        System.err.printf("[Feishu] 收到会话 %s 消息: %s%n", chatId, contentStr);

                        // 【驾驭并发】：收到消息后，绝不能阻塞 HTTP 回调。
                        // 我们要为每个请求开启一个独立的虚拟线程跑 Agent 任务！
                        String prompt = contentStr;
                        String id = chatId;
                        Thread.startVirtualThread(() -> handleAgentRun(id, prompt));
                    }
                })
                .onP2MessageReadV1(new ImService.P2MessageReadV1Handler() {
                    @Override
                    public void handle(P2MessageReadV1 event) {
                        // 消息已读事件，静默忽略（避免日志干扰）
                    }
                })
                .build();

        return handler;
    }

    // handleAgentRun 是连接飞书与底层引擎的桥梁
    private void handleAgentRun(String chatId, String prompt) {
        // 为当前聊天窗口实例化一个专属的 Reporter
        FeishuReporter reporter = new FeishuReporter(client, chatId);

        // 启动引擎！
        try {
            engine.run(Context.background(), prompt, reporter);
        } catch (Exception err) {
            reporter.sendMsg(String.format("❌ Agent 运行崩溃: %s", err.getMessage() == null ? err.toString() : err.getMessage()));
        }
    }

    // strings.TrimPrefix：仅当前缀匹配时去掉，否则原样返回
    private static String trimPrefix(String s, String prefix) {
        if (s.startsWith(prefix)) {
            return s.substring(prefix.length());
        }
        return s;
    }

    // strings.TrimSuffix：仅当后缀匹配时去掉，否则原样返回
    private static String trimSuffix(String s, String suffix) {
        if (s.endsWith(suffix)) {
            return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }
}

// ==========================================
// FeishuReporter: 将引擎的输出格式化后发给飞书
// ==========================================
class FeishuReporter implements Reporter {
    private final Client client;
    private final String chatId;

    FeishuReporter(Client client, String chatId) {
        this.client = client;
        this.chatId = chatId;
    }

    // sendMsg 封装了调用飞书 OpenAPI 发送卡片/文本的操作
    void sendMsg(String text) {
        try {
            // Build text message content
            String contentStr = Jsons.DEFAULT.toJson(Map.of("text", text));

            CreateMessageReq msgReq = CreateMessageReq.newBuilder()
                    .receiveIdType("chat_id")
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(chatId)
                            .msgType("text")
                            .content(contentStr)
                            .build())
                    .build();

            client.im().message().create(msgReq);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onThinking(Context ctx) {
        // 仅发一个轻量级提示，避免飞书刷屏
        sendMsg("🤔 模型正在慢思考 (Thinking)...");
    }

    @Override
    public void onToolCall(Context ctx, String toolName, String args) {
        sendMsg(String.format("🛠️ **正在执行工具**：`%s`\n参数：`%s`", toolName, args));
    }

    @Override
    public void onToolResult(Context ctx, String toolName, String result, boolean isError) {
        if (isError) {
            sendMsg(String.format("⚠️ **执行报错** (%s)：\n%s", toolName, result));
        } else {
            // 成功时仅汇报成功，不刷全量日志
            sendMsg(String.format("✅ **执行成功** (%s)", toolName));
        }
    }

    @Override
    public void onMessage(Context ctx, String content) {
        // 将模型最终的纯文本回答发给用户
        sendMsg(content);
    }
}
