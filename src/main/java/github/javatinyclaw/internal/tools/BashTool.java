package github.javatinyclaw.internal.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// BashTool 在当前工作区执行任意 bash 命令
public class BashTool implements BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 工作区约束
    private final String workDir;

    public static BashTool newBashTool(String workDir) {
        return new BashTool(workDir);
    }

    private BashTool(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition def = new ToolDefinition();
        def.name = name();
        def.description = "在当前工作区执行任意的 bash 命令。支持链式命令(如 &&)。返回标准输出(stdout)和标准错误(stderr)。";

        Map<String, Object> command = new HashMap<String, Object>();
        command.put("type", "string");
        command.put("description", "要执行的 bash 命令，例如: ls -la 或 go test ./...");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("command", command);

        List<String> required = new ArrayList<String>();
        required.add("command");

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);
        def.inputSchema = inputSchema;

        return def;
    }

    static class BashArgs {
        public String command;
    }

    @Override
    public String execute(Context ctx, byte[] args) throws Exception {
        BashArgs input;
        try {
            input = OBJECT_MAPPER.readValue(args, BashArgs.class);
        } catch (Exception err) {
            throw new Exception("参数解析失败: " + err.getMessage(), err);
        }

        // 【驾驭底线 1】：Time Budgeting (时间预算与超时控制)
        // 给予 bash 命令一个最大执行时间，防止大模型卡死进程 (比如运行了 top 或持续监听的 Web 服务)
        // 【驾驭底线 2】：绑定执行的工作区目录
        // 确保命令默认在用户指定的 WorkDir 下执行，而不是引擎启动时的绝对路径。
        // 在 macOS/Linux 下，我们通过将指令包裹在 `bash -c` 中执行，以支持环境变量、管道和逻辑与(&&)等复杂 Shell 语法。
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", input.command);
        pb.directory(new File(workDir));
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (IOException err) {
            // 【驾驭底线 3】：错误原样回传 (Self-Correction 自愈机制)
            return String.format("执行报错: %s\n输出:\n", err.getMessage());
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        boolean timedOut = false;
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[4096];
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);

            while (true) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    timedOut = true;
                    process.destroyForcibly();
                    process.waitFor();
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    break;
                }

                int available = in.available();
                if (available > 0) {
                    int n = in.read(buf, 0, Math.min(buf.length, available));
                    if (n > 0) {
                        baos.write(buf, 0, n);
                    }
                    continue;
                }

                long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                boolean finished = process.waitFor(Math.min(remainingMs, 50), TimeUnit.MILLISECONDS);
                if (finished) {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    break;
                }
            }
        } catch (IOException err) {
            return String.format("执行报错: %s\n输出:\n%s", err.getMessage(), new String(baos.toByteArray(), StandardCharsets.UTF_8));
        }

        byte[] out = baos.toByteArray();
        String outputStr = new String(out, StandardCharsets.UTF_8);

        // 如果命令执行超时，返回警告信息让模型知晓
        if (timedOut) {
            return outputStr + "\n[警告: 命令执行超时(30s)，已被系统强制终止。如果是启动常驻服务，请尝试将其转入后台。]";
        }

        // 【驾驭底线 3】：错误原样回传 (Self-Correction 自愈机制)
        // 当 bash 报错时，我们绝对不能返回 error 阻断程序！
        // 我们必须把 err 和 outputStr 拼接成字符串返回，利用大模型的自纠错能力自己分析报错！
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            return String.format("执行报错: exit status %d\n输出:\n%s", exitCode, outputStr);
        }

        // 如果没有终端输出（比如仅仅执行了 mkdir），给模型一个明确的执行成功的反馈
        if (outputStr.isEmpty()) {
            return "命令执行成功，无终端输出。";
        }

        // 【驾驭底线 4】：长度截断保护 (防 OOM)
        final int maxLen = 8000;
        if (out.length > maxLen) {
            return String.format("%s\n\n...[终端输出过长，已截断至前 %d 字节]...",
                    new String(out, 0, maxLen, StandardCharsets.UTF_8),
                    maxLen);
        }

        return outputStr;
    }
}
