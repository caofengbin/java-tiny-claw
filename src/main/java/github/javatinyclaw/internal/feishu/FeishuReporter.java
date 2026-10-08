package github.javatinyclaw.internal.feishu;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.engine.Reporter;

import java.util.Map;

public class FeishuReporter implements Reporter {
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
