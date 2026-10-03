package github.javatinyclaw.internal.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.context.SessionManager;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.engine.Reporter;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;

import java.util.Map;

public class FeishuBot {
    private final Client client;
    private final String appID;
    private final String appSecret;
    private final AgentEngine engine;

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

        Client client = Client.newBuilder(appID, appSecret).build();

        this.client = client;
        this.appID = appID;
        this.appSecret = appSecret;
        this.engine = eng;
    }

    public EventDispatcher getEventDispatcher() {
        String encryptKey = System.getenv("FEISHU_ENCRYPT_KEY");
        String verifyToken = System.getenv("FEISHU_VERIFY_TOKEN");
        if (encryptKey == null) {
            encryptKey = "";
        }
        if (verifyToken == null) {
            verifyToken = "";
        }

        EventDispatcher handler = EventDispatcher.newBuilder(verifyToken, encryptKey)
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) {
                        String contentStr = event.getEvent().getMessage().getContent();
                        contentStr = trimPrefix(contentStr, "{\"text\":\"");
                        contentStr = trimSuffix(contentStr, "\"}");

                        String chatId = event.getEvent().getMessage().getChatId();
                        System.err.printf("[Feishu] 收到会话 %s 消息: %s%n", chatId, contentStr);

                        String prompt = contentStr;
                        String id = chatId;
                        Thread.startVirtualThread(() -> handleAgentRun(id, prompt));
                    }
                })
                .onP2MessageReadV1(new ImService.P2MessageReadV1Handler() {
                    @Override
                    public void handle(P2MessageReadV1 event) {
                        // 消息已读事件，静默忽略
                    }
                })
                .build();

        return handler;
    }

    private void handleAgentRun(String chatId, String prompt) {
        FeishuReporter reporter = new FeishuReporter(client, chatId);

        // Go 的 bot.go 仍把 prompt 直接交给 Run。Java 引擎只接收 Session，这里用 chatId 取会话后再跑。
        String workDir = System.getProperty("user.dir");
        if (workDir == null) {
            workDir = "";
        }
        Session session = SessionManager.globalSessionMgr.getOrCreate(chatId, workDir);
        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = prompt;
        session.append(userMsg);

        try {
            engine.run(Context.background(), session, reporter);
        } catch (Exception err) {
            String detail = err.getMessage() == null ? err.toString() : err.getMessage();
            reporter.sendMsg(String.format("❌ Agent 运行崩溃: %s", detail));
        }
    }

    // strings.TrimPrefix：仅当前缀匹配时去掉，否则原样返回
    private static String trimPrefix(String s, String prefix) {
        if (s != null && s.startsWith(prefix)) {
            return s.substring(prefix.length());
        }
        return s;
    }

    // strings.TrimSuffix：仅当后缀匹配时去掉，否则原样返回
    private static String trimSuffix(String s, String suffix) {
        if (s != null && s.endsWith(suffix)) {
            return s.substring(0, s.length() - suffix.length());
        }
        return s;
    }
}

class FeishuReporter implements Reporter {
    private final Client client;
    private final String chatId;

    FeishuReporter(Client client, String chatId) {
        this.client = client;
        this.chatId = chatId;
    }

    void sendMsg(String text) {
        try {
            // Build text message content
            String contentStr = Jsons.DEFAULT.toJson(Map.of("text", text));

            CreateMessageReq msgReq = CreateMessageReq.newBuilder()
                    .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
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
            sendMsg(String.format("✅ **执行成功** (%s)", toolName));
        }
    }

    @Override
    public void onMessage(Context ctx, String content) {
        sendMsg(content);
    }
}
