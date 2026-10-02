package github.javatinyclaw.cmd.claw;

import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.response.EventResp;
import com.lark.oapi.event.EventDispatcher;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.feishu.FeishuBot;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.WriteFileTool;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Main {

    // ==========================================
    // 3. 组装运行
    // ==========================================
    public static void main(String[] args) {
        // 确保已设置 ZHIPU_API_KEY
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.equals("")) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量");
            System.exit(1);
        }

        // 获取当前执行目录作为 WorkDir 物理边界
        String workDir = System.getProperty("user.dir");
        if (workDir == null) {
            workDir = "";
        }

        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");
//        LLMProvider llmProvider = ClaudeProvider.newZhipuClaudeProvider("glm-5.3-flash");

        // 2. 初始化真实的工具注册表
        Registry registry = RegistryImpl.newRegistry();

        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        // 【新增挂载】
        registry.register(EditFileTool.newEditFileTool(workDir));

        // 3.实例化引擎，开启 EnableThinking = true (开启慢思考，促使模型一次性统筹规划)
        AgentEngine eng = new AgentEngine(llmProvider, registry, workDir, false);

        // 2. 初始化飞书 Bot 调度器
        FeishuBot bot = new FeishuBot(eng);
        EventDispatcher dispatcher = bot.getEventDispatcher();

        // 3. 注册路由并启动 HTTP 服务
        String port = ":48080";
        System.err.printf("🚀 java-tiny-claw 飞书服务端已启动，正在监听 %s 端口%n", port);

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(48080), 0);
            server.createContext("/webhook/event", exchange -> handleEvent(exchange, dispatcher));
            server.start();
            Thread.currentThread().join();
        } catch (Exception err) {
            System.err.printf("服务器启动失败: %s%n", err.getMessage());
            System.exit(1);
        }
    }

    // 对齐 oapi-sdk-go httpserverext.NewEventHandlerFunc / doProcess
    private static void handleEvent(HttpExchange exchange, EventDispatcher dispatcher) {
        try {
            byte[] rawBody;
            try {
                rawBody = exchange.getRequestBody().readAllBytes();
            } catch (IOException err) {
                byte[] msg = err.getMessage() == null
                        ? new byte[0]
                        : err.getMessage().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, msg.length == 0 ? -1 : msg.length);
                if (msg.length > 0) {
                    exchange.getResponseBody().write(msg);
                }
                return;
            }

            EventReq eventReq = new EventReq();
            Map<String, List<String>> headers = new HashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((name, values) -> {
                headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<String>(values));
            });
            eventReq.setHeaders(headers);
            eventReq.setBody(rawBody);
            eventReq.setHttpPath(exchange.getRequestURI().toString());

            EventResp eventResp = dispatcher.handle(eventReq);
            writeResp(exchange, eventResp);
        } catch (Throwable err) {
            System.err.printf("write resp result error:%s%n", err.getMessage());
        } finally {
            exchange.close();
        }
    }

    private static void writeResp(HttpExchange exchange, EventResp eventResp) throws IOException {
        if (eventResp.getHeaders() != null) {
            eventResp.getHeaders().forEach((name, values) -> {
                if (values == null) {
                    return;
                }
                for (String value : values) {
                    exchange.getResponseHeaders().add(name, value);
                }
            });
        }
        byte[] body = eventResp.getBody();
        if (body != null && body.length > 0) {
            exchange.sendResponseHeaders(eventResp.getStatusCode(), body.length);
            exchange.getResponseBody().write(body);
        } else {
            exchange.sendResponseHeaders(eventResp.getStatusCode(), -1);
        }
    }
}
