// internal/provider/claude.go
package github.javatinyclaw.internal.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ClaudeProvider implements LLMProvider {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final String model;

    private ClaudeProvider(AnthropicClient client, String model) {
        this.client = client;
        this.model = model;
    }

    public static ClaudeProvider newZhipuClaudeProvider(String model) {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.equals("")) {
            throw new IllegalStateException("请设置 ZHIPU_API_KEY 环境变量");
        }
        // Java SDK 会再拼 v1/messages。base 保持到域名，最终是 /v1/messages。
        // 不要写成 /v1，否则会变成 /v1/v1/messages。
        String baseURL = "https://tokenhub.tencentmaas.com";

        AnthropicOkHttpClient.Builder clientBuilder = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseURL);

        // WHISTLE_PROXY=127.0.0.1:8899 时走本地 Whistle；未设置则直连。
        String proxy = System.getenv("WHISTLE_PROXY");
        if (proxy != null && !proxy.equals("")) {
            String[] hostPort = proxy.split(":");
            clientBuilder.proxy(new Proxy(
                Proxy.Type.HTTP,
                new InetSocketAddress(hostPort[0], Integer.parseInt(hostPort[1]))
            ));
        }

        return new ClaudeProvider(clientBuilder.build(), model);
    }

    @Override
    public Message generate(Context ctx, List<Message> msgs, List<ToolDefinition> availableTools) throws Exception {
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
            .model(this.model)
            .maxTokens(4096L);
        String systemPrompt = "";

        // 1. 消息翻译
        if (msgs != null) {
            for (Message msg : msgs) {
                if (Role.SYSTEM.equals(msg.role)) {
                    systemPrompt = nullToEmpty(msg.content);
                } else if (Role.USER.equals(msg.role)) {
                    if (msg.toolCallId != null && !msg.toolCallId.equals("")) {
                        List<ContentBlockParam> userBlocks = new ArrayList<ContentBlockParam>();
                        userBlocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(msg.toolCallId)
                            .content(nullToEmpty(msg.content))
                            .isError(false)
                            .build()));
                        paramsBuilder.addUserMessageOfBlockParams(userBlocks);
                    } else {
                        List<ContentBlockParam> userBlocks = new ArrayList<ContentBlockParam>();
                        userBlocks.add(ContentBlockParam.ofText(nullToEmpty(msg.content)));
                        paramsBuilder.addUserMessageOfBlockParams(userBlocks);
                    }
                } else if (Role.ASSISTANT.equals(msg.role)) {
                    List<ContentBlockParam> blocks = new ArrayList<ContentBlockParam>();
                    if (msg.content != null && !msg.content.equals("")) {
                        blocks.add(ContentBlockParam.ofText(msg.content));
                    }

                    // 将历史工具调用转回 Claude 特有的 ToolUseBlockParam
                    if (msg.toolCalls != null) {
                        for (ToolCall tc : msg.toolCalls) {
                            Map<String, Object> inputMap = null;
                            try {
                                if (tc.arguments != null) {
                                    inputMap = OBJECT_MAPPER.readValue(tc.arguments, new TypeReference<Map<String, Object>>() {
                                    });
                                }
                            } catch (Exception ignored) {
                                inputMap = null;
                            }

                            ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
                            if (inputMap != null) {
                                for (Map.Entry<String, Object> entry : inputMap.entrySet()) {
                                    inputBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                                }
                            }

                            blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                                .id(tc.id)
                                .name(tc.name)
                                .input(inputBuilder.build())
                                .build()));
                        }
                    }

                    if (!blocks.isEmpty()) {
                        paramsBuilder.addAssistantMessageOfBlockParams(blocks);
                    }
                }
            }
        }

        // 2. 工具 Schema 翻译
        List<Tool> anthropicTools = new ArrayList<Tool>();
        if (availableTools != null) {
            for (ToolDefinition toolDef : availableTools) {
                // ToolInputSchemaParam 是结构体，需要通过 Properties 字段精准填充
                Map<String, Object> properties = null;
                List<String> required = null;

                if (toolDef.inputSchema instanceof Map) {
                    Map<String, Object> schemaMap = castStringObjectMap(toolDef.inputSchema);
                    Object propertiesObj = schemaMap.get("properties");
                    if (propertiesObj instanceof Map) {
                        properties = castStringObjectMap(propertiesObj);
                    }
                    required = asStringList(schemaMap.get("required"));
                }

                Tool.InputSchema.Properties.Builder propertiesBuilder = Tool.InputSchema.Properties.builder();
                if (properties != null) {
                    for (Map.Entry<String, Object> entry : properties.entrySet()) {
                        propertiesBuilder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
                    }
                }

                Tool.InputSchema.Builder inputSchemaBuilder = Tool.InputSchema.builder()
                    .properties(propertiesBuilder.build());
                if (required != null) {
                    inputSchemaBuilder.required(required);
                }

                Tool.Builder toolBuilder = Tool.builder()
                    .name(toolDef.name)
                    .inputSchema(inputSchemaBuilder.build());
                if (toolDef.description != null) {
                    toolBuilder.description(toolDef.description);
                } else {
                    toolBuilder.description("");
                }
                anthropicTools.add(toolBuilder.build());
            }
        }

        // 3. 构建请求并发送
        if (systemPrompt != null && !systemPrompt.equals("")) {
            paramsBuilder.system(systemPrompt);
        }

        if (!anthropicTools.isEmpty()) {
            for (Tool tool : anthropicTools) {
                paramsBuilder.addTool(tool);
            }
        }

        com.anthropic.models.messages.Message resp;
        try {
            resp = this.client.messages().create(paramsBuilder.build());
        } catch (Exception err) {
            throw new Exception("Claude/Zhipu API 请求失败: " + err.getMessage(), err);
        }

        // 4. 反向解析
        Message resultMsg = new Message();
        resultMsg.role = Role.ASSISTANT;
        resultMsg.content = "";

        for (ContentBlock block : resp.content()) {
            if (block.isText()) {
                resultMsg.content = resultMsg.content + block.asText().text();
            } else if (block.isToolUse()) {
                ToolUseBlock toolUse = block.asToolUse();
                byte[] argsBytes;
                try {
                    argsBytes = OBJECT_MAPPER.writeValueAsBytes(toolUse._input().convert(Object.class));
                } catch (Exception ignored) {
                    argsBytes = new byte[0];
                }
                ToolCall call = new ToolCall();
                call.id = toolUse.id();
                call.name = toolUse.name();
                call.arguments = argsBytes;
                if (resultMsg.toolCalls == null) {
                    resultMsg.toolCalls = new ArrayList<ToolCall>();
                }
                resultMsg.toolCalls.add(call);
            }
        }

        return resultMsg;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List)) {
            return null;
        }
        List<?> raw = (List<?>) value;
        List<String> required = new ArrayList<String>();
        for (Object item : raw) {
            if (!(item instanceof String)) {
                return null;
            }
            required.add((String) item);
        }
        return required;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
