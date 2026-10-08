package github.javatinyclaw.internal.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.P2MessageReadV1;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;

public class FeishuBot {
    private final Client client;
    private final String appID;
    private final String appSecret;
    private final AgentEngine engine;
    private final Session sess; // 新增session信息
    private FeishuReporter r; // 新增实现Reporter接口的FeishuReporter实例

    public FeishuBot(AgentEngine eng, Session sess) {
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
        this.sess = sess; // 绑定session信息
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

                        // 【新增】：拦截人工审批的特殊口令
                        if (contentStr != null && contentStr.startsWith("approve ")) {
                            String taskID = trimPrefix(contentStr, "approve ").trim();
                            // 唤醒挂起的引擎协程！
                            ApprovalManager.globalApprovalMgr.resolveApproval(taskID, true, "人类管理员已批准操作");
                            System.err.printf("[Feishu] 会话 %s: ✅ 已为您批准任务 %s%n", chatId, taskID);
                            return;
                        }
                        if (contentStr != null && contentStr.startsWith("reject ")) {
                            String taskID = trimPrefix(contentStr, "reject ").trim();
                            // 唤醒挂起的引擎协程，并反馈拒绝理由！
                            ApprovalManager.globalApprovalMgr.resolveApproval(taskID, false, "人类管理员认为该操作存在极高风险，已无情拒绝");
                            System.err.printf("[Feishu] 会话 %s: 🚫 已拒绝任务 %s%n", chatId, taskID);
                            return;
                        }

                        // 如果不是审批命令，则是正常对话，启动一个新的 Agent 任务去处理
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

    // 新增一个方法，返回FeishuBot绑定的Reporter
    public FeishuReporter reporter() {
        return r;
    }

    private void handleAgentRun(String chatId, String prompt) {
        FeishuReporter reporter = new FeishuReporter(client, chatId);
        r = reporter;
        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = prompt;
        sess.append(userMsg); // 将prompt加入会话中
        try {
            engine.run(Context.background(), sess, reporter);
        } catch (Exception err) {
            reporter.sendMsg(String.format("❌ Agent 运行崩溃: %s", err));
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
