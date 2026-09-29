package github.javatinyclaw.internal.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// ReadFileTool 实现了读取本地文件内容的工具
public class ReadFileTool implements BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 将引擎的 WorkDir 注入给工具，限制它只能在此目录及其子目录下操作
    private final String workDir;

    public static ReadFileTool newReadFileTool(String workDir) {
        return new ReadFileTool(workDir);
    }

    private ReadFileTool(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "read_file";
    }

    // Definition 向大模型清晰地描述这个工具的用途和参数格式
    @Override
    public ToolDefinition definition() {
        ToolDefinition def = new ToolDefinition();
        def.name = name();
        def.description = "读取指定路径的文件内容。请提供相对工作区的路径。";

        Map<String, Object> path = new HashMap<String, Object>();
        path.put("type", "string");
        path.put("description", "要读取的文件路径，如 cmd/claw/main.go");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("path", path);

        List<String> required = new ArrayList<String>();
        required.add("path");

        Map<String, Object> inputSchema = new HashMap<String, Object>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);
        def.inputSchema = inputSchema;

        return def;
    }

    // readFileArgs 内部定义用于反序列化的结构体
    static class ReadFileArgs {
        public String path;
    }

    @Override
    public String execute(Context ctx, byte[] args) throws Exception {
        // 1. 延迟解析：将大模型传过来的 JSON 参数解析为强类型结构体
        ReadFileArgs input;
        try {
            input = OBJECT_MAPPER.readValue(args, ReadFileArgs.class);
        } catch (Exception err) {
            // 返回 error 会被 Registry 捕获并传给大模型，模型会知道自己 JSON 格式写错了
            throw new Exception("参数解析失败: " + err.getMessage(), err);
        }

        // 2. 拼接绝对路径 (注意：生产环境中需要做路径穿越检测防范，防止 ../../etc/passwd)
        Path fullPath = Paths.get(workDir, input.path);

        // 3. 执行物理 IO 操作
        byte[] content;
        try {
            content = Files.readAllBytes(fullPath);
        } catch (IOException err) {
            throw new Exception("打开文件失败: " + err.getMessage(), err);
        }

        // 4. 【核心防线】长度截断保护
        // 为了防止大模型读取几百 MB 的日志文件导致 Context 瞬间爆炸 (OOM)，
        // 我们在工具内部直接进行物理截断。
        final int maxLen = 8000;
        if (content.length > maxLen) {
            String truncatedMsg = String.format(
                    "%s\n\n...[由于内容过长，已被系统截断至前 %d 字节]...",
                    new String(content, 0, maxLen, StandardCharsets.UTF_8),
                    maxLen);
            return truncatedMsg;
        }

        return new String(content, StandardCharsets.UTF_8);
    }
}
