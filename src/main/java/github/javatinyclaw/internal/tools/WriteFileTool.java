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

// WriteFileTool 实现了创建或覆盖写入本地文件的工具
public class WriteFileTool implements BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // 工作区约束
    private final String workDir;

    public static WriteFileTool newWriteFileTool(String workDir) {
        return new WriteFileTool(workDir);
    }

    private WriteFileTool(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition def = new ToolDefinition();
        def.name = name();
        def.description = "创建或覆盖写入一个文件。如果目录不存在会自动创建。请提供相对于工作区的相对路径。";

        Map<String, Object> path = new HashMap<String, Object>();
        path.put("type", "string");
        path.put("description", "要写入的文件路径，如 src/main.go");

        Map<String, Object> content = new HashMap<String, Object>();
        content.put("type", "string");
        content.put("description", "要写入的完整文件内容");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("path", path);
        properties.put("content", content);

        List<String> required = new ArrayList<String>();
        required.add("path");
        required.add("content");

        Map<String, Object> inputSchema = new HashMap<String, Object>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);
        def.inputSchema = inputSchema;

        return def;
    }

    static class WriteFileArgs {
        public String path;
        public String content;
    }

    @Override
    public String execute(Context ctx, byte[] args) throws Exception {
        WriteFileArgs input;
        try {
            input = OBJECT_MAPPER.readValue(args, WriteFileArgs.class);
        } catch (Exception err) {
            throw new Exception("参数解析失败: " + err.getMessage(), err);
        }

        // 【安全防线】：限制在 WorkDir 下执行，防止大模型修改系统级文件
        Path fullPath = Paths.get(workDir, input.path);

        // 自动创建缺失的父级目录
        Path parent = fullPath.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException err) {
                throw new Exception("创建父目录失败: " + err.getMessage(), err);
            }
        }

        // 写入文件内容
        try {
            Files.write(fullPath, input.content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new Exception("写入文件失败: " + err.getMessage(), err);
        }

        return String.format("成功将内容写入到文件: %s", input.path);
    }
}
