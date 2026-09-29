// internal/provider/openai.go
package github.javatinyclaw.internal.provider;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.schema.ToolCall;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.net.InetSocketAddress;
import java.net.Proxy;

public class OpenAIProvider implements LLMProvider {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenAIClient client;
    private final String model;

    private OpenAIProvider(OpenAIClient client, String model) {
        this.client = client;
        this.model = model;
    }

    // NewZhipuOpenAIProvider 构造函数：基于 OpenAI V3 SDK，指向智谱底座
    public static OpenAIProvider newZhipuOpenAIProvider(String model) {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.equals("")) {
            throw new IllegalStateException("请设置 ZHIPU_API_KEY 环境变量");
        }
        // 核心：将官方 SDK 的地址替换为智谱的兼容端点
        // Go 的 WithBaseURL 会给 /v1 补上结尾斜杠，再拼 chat/completions，
        // 实际请求是 https://tokenhub.tencentmaas.com/v1/chat/completions
        String baseURL = "https://tokenhub.tencentmaas.com/v1";

        OpenAIOkHttpClient.Builder clientBuilder = OpenAIOkHttpClient.builder()
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

        return new OpenAIProvider(clientBuilder.build(), model);
    }

    @Override
    public Message generate(Context ctx, List<Message> msgs, List<ToolDefinition> availableTools) throws Exception {
        ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
            .model(this.model);

        // 1. 翻译上下文消息
        if (msgs != null) {
            for (Message msg : msgs) {
                if (Role.SYSTEM.equals(msg.role)) {
                    paramsBuilder.addSystemMessage(nullToEmpty(msg.content));
                } else if (Role.USER.equals(msg.role)) {
                    if (msg.toolCallId != null && !msg.toolCallId.equals("")) {
                        // 注意：v3 新版参数顺序是 (content, toolCallID)
                        paramsBuilder.addMessage(ChatCompletionToolMessageParam.builder()
                            .content(nullToEmpty(msg.content))
                            .toolCallId(msg.toolCallId)
                            .build());
                    } else {
                        paramsBuilder.addUserMessage(nullToEmpty(msg.content));
                    }
                } else if (Role.ASSISTANT.equals(msg.role)) {
                    ChatCompletionAssistantMessageParam.Builder astParam = ChatCompletionAssistantMessageParam.builder();

                    if (msg.content != null && !msg.content.equals("")) {
                        astParam.content(msg.content);
                    }

                    // 【重要】如果历史包含 ToolCalls，必须原样放回，以维系大模型的逻辑链
                    if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                        for (ToolCall tc : msg.toolCalls) {
                            String arguments = tc.arguments == null ? "" : new String(tc.arguments, StandardCharsets.UTF_8);
                            astParam.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                                .id(tc.id)
                                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(tc.name)
                                    .arguments(arguments)
                                    .build())
                                .build());
                        }
                    }

                    paramsBuilder.addMessage(astParam.build());
                }
            }
        }

        // 2. 翻译工具定义 (v3 新 API 特性适配)
        List<ChatCompletionFunctionTool> openaiTools = new ArrayList<ChatCompletionFunctionTool>();
        if (availableTools != null) {
            for (ToolDefinition toolDef : availableTools) {
                FunctionParameters params = toFunctionParameters(toolDef.inputSchema);

                FunctionDefinition.Builder functionBuilder = FunctionDefinition.builder()
                    .name(toolDef.name)
                    .parameters(params);
                if (toolDef.description != null) {
                    functionBuilder.description(toolDef.description);
                } else {
                    functionBuilder.description("");
                }

                openaiTools.add(ChatCompletionFunctionTool.builder()
                    .function(functionBuilder.build())
                    .build());
            }
        }

        // 【慢思考机制支撑】仅当 availableTools 存在时才挂载 Tools
        if (!openaiTools.isEmpty()) {
            for (ChatCompletionFunctionTool tool : openaiTools) {
                paramsBuilder.addTool(tool);
            }
        }

        // 3. 构建请求并发送
        ChatCompletion resp;
        try {
            resp = this.client.chat().completions().create(paramsBuilder.build());
        } catch (Exception err) {
            throw new Exception("OpenAI/Zhipu API 请求失败: " + err.getMessage(), err);
        }
        if (resp.choices() == null || resp.choices().isEmpty()) {
            throw new Exception("API 返回了空的 Choices");
        }

        // 4. 将 API Response 反向翻译为内部 schema.Message
        ChatCompletionMessage choice = resp.choices().get(0).message();
        Message resultMsg = new Message();
        resultMsg.role = Role.ASSISTANT;
        resultMsg.content = choice.content().orElse("");

        List<ChatCompletionMessageToolCall> toolCalls = choice.toolCalls().orElse(new ArrayList<ChatCompletionMessageToolCall>());
        for (ChatCompletionMessageToolCall tc : toolCalls) {
            if (tc.isFunction()) {
                ChatCompletionMessageFunctionToolCall functionCall = tc.asFunction();
                ToolCall call = new ToolCall();
                call.id = functionCall.id();
                call.name = functionCall.function().name();
                call.arguments = functionCall.function().arguments().getBytes(StandardCharsets.UTF_8);
                if (resultMsg.toolCalls == null) {
                    resultMsg.toolCalls = new ArrayList<ToolCall>();
                }
                resultMsg.toolCalls.add(call);
            }
        }

        return resultMsg;
    }

    private static FunctionParameters toFunctionParameters(Object inputSchema) {
        Map<String, Object> schemaMap = null;

        // 尝试直接断言，如果不成功则通过 JSON 往返序列化来保证类型匹配
        if (inputSchema instanceof Map) {
            schemaMap = castStringObjectMap(inputSchema);
        } else {
            // fallback：JSON 往返序列化
            try {
                byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(inputSchema);
                schemaMap = OBJECT_MAPPER.readValue(bytes, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception ignored) {
                schemaMap = null;
            }
        }

        FunctionParameters.Builder builder = FunctionParameters.builder();
        if (schemaMap != null) {
            for (Map.Entry<String, Object> entry : schemaMap.entrySet()) {
                builder.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
            }
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjectMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
