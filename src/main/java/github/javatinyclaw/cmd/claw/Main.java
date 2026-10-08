package github.javatinyclaw.cmd.claw;

import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.core.response.EventResp;
import com.lark.oapi.event.EventDispatcher;
import com.sun.net.httpserver.HttpServer;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.context.SessionManager;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.feishu.ApprovalManager;
import github.javatinyclaw.internal.feishu.ApprovalResult;
import github.javatinyclaw.internal.feishu.FeishuBot;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.MiddlewareResult;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.WriteFileTool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Main {

    public static void main(String[] args) {
        // 确保已设置 ZHIPU_API_KEY
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量");
            System.exit(1);
        }

        String workDir = System.getProperty("user.dir") + "/workspace";
        // 1. 初始化真实的 Provider大脑 (指向智谱 GLM-4.5)
        // 这里你可以任意切换 NewZhipuClaudeProvider 或 NewZhipuOpenAIProvider，效果完全一致！
        LLMProvider llmProvider = OpenAIProvider.newZhipuOpenAIProvider("glm-5.3-flash");

        // 挂载 4 大基础工具
        Registry registry = RegistryImpl.newRegistry();
        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        registry.register(EditFileTool.newEditFileTool(workDir));

        // 关闭 Plan 模式，让它在死胡同里专注地展示挣扎过程
        AgentEngine eng = new AgentEngine(llmProvider, registry, false, false);

        // 假设一个bot绑定一个session
        String sessionID = "test_command_intercept_001";
        Session sess = SessionManager.globalSessionMgr.getOrCreate(sessionID, workDir);
        Message emptyUser = new Message();
        emptyUser.role = Role.USER;
        emptyUser.content = "";
        sess.append(emptyUser);

        FeishuBot bot = new FeishuBot(eng, sess);
        EventDispatcher dispatcher = bot.getEventDispatcher();

        // 【核心注入】注册安全拦截 Middleware
        registry.use((ctx, call) -> {
            String argsStr = call.arguments == null ? "" : new String(call.arguments, StandardCharsets.UTF_8);

            // 检查是否命中高危特征库
            if (ApprovalManager.isDangerousCommand(call.name, argsStr)) {
                String taskID = call.id; // 使用大模型生成的唯一 ToolCallID 作为 TaskID

                // 挂起当前协程，发送消息给飞书，死死等待人类的审批！
                ApprovalResult result = ApprovalManager.globalApprovalMgr.waitForApproval(taskID, call.name, argsStr, bot.reporter());

                if (!result.allowed) {
                    return new MiddlewareResult(false, result.reason); // 拒绝，将理由传回给大模型
                }
                return new MiddlewareResult(true, ""); // 同意，放行底层工具
            }

            // 没命中黑名单，直接 YOLO 放行
            return new MiddlewareResult(true, "");
        });

        // 3. 注册路由并启动 HTTP 服务
        String port = ":48080";
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(48080), 0);
            server.createContext("/webhook/event", exchange -> {
                try {
                    EventReq eventReq = new EventReq();
                    Map<String, List<String>> headers = new HashMap<>();
                    for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
                        headers.put(entry.getKey(), new ArrayList<>(entry.getValue()));
                    }
                    eventReq.setHeaders(headers);
                    eventReq.setBody(exchange.getRequestBody().readAllBytes());
                    eventReq.setHttpPath(exchange.getRequestURI().toString());

                    EventResp eventResp = dispatcher.handle(eventReq);

                    if (eventResp.getHeaders() != null) {
                        for (Map.Entry<String, List<String>> entry : eventResp.getHeaders().entrySet()) {
                            exchange.getResponseHeaders().put(entry.getKey(), entry.getValue());
                        }
                    }
                    byte[] body = eventResp.getBody();
                    if (body == null) {
                        body = new byte[0];
                    }
                    exchange.sendResponseHeaders(eventResp.getStatusCode(), body.length);
                    if (body.length > 0) {
                        exchange.getResponseBody().write(body);
                    }
                } catch (Exception err) {
                    byte[] errBody = err.getMessage() == null ? new byte[0] : err.getMessage().getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(500, errBody.length);
                    if (errBody.length > 0) {
                        exchange.getResponseBody().write(errBody);
                    }
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            System.err.printf("🚀 go-tiny-claw 飞书服务端已启动，正在监听 %s 端口%n", port);
            Thread.currentThread().join();
        } catch (Exception err) {
            System.err.printf("服务器启动失败: %s%n", err);
            System.exit(1);
        }
    }
}
