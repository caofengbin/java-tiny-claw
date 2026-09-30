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

// EditFileTool 对现有文件进行局部的字符串替换
public class EditFileTool implements BaseTool {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String workDir;

    public static EditFileTool newEditFileTool(String workDir) {
        return new EditFileTool(workDir);
    }

    private EditFileTool(String workDir) {
        this.workDir = workDir;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public ToolDefinition definition() {
        ToolDefinition def = new ToolDefinition();
        def.name = name();
        def.description = "对现有文件进行局部的字符串替换。这比重写整个文件更安全、更快速。请提供足够的 old_text 上下文以确保匹配的唯一性。";

        Map<String, Object> path = new HashMap<>();
        path.put("type", "string");
        path.put("description", "要修改的文件路径");

        Map<String, Object> oldText = new HashMap<>();
        oldText.put("type", "string");
        oldText.put("description", "文件中原有的文本。必须包含足够的上下文，以确保在文件中的唯一性。");

        Map<String, Object> newText = new HashMap<String, Object>();
        newText.put("type", "string");
        newText.put("description", "要替换成的新文本");

        Map<String, Object> properties = new HashMap<String, Object>();
        properties.put("path", path);
        properties.put("old_text", oldText);
        properties.put("new_text", newText);

        List<String> required = new ArrayList<String>();
        required.add("path");
        required.add("old_text");
        required.add("new_text");

        Map<String, Object> inputSchema = new HashMap<>();
        inputSchema.put("type", "object");
        inputSchema.put("properties", properties);
        inputSchema.put("required", required);
        def.inputSchema = inputSchema;

        return def;
    }

    static class EditFileArgs {
        public String path;
        public String old_text;
        public String new_text;
    }

    @Override
    public String execute(Context ctx, byte[] args) throws Exception {
        EditFileArgs input;
        try {
            input = OBJECT_MAPPER.readValue(args, EditFileArgs.class);
        } catch (Exception err) {
            throw new Exception("参数解析失败: " + err.getMessage(), err);
        }

        Path fullPath = Paths.get(workDir, input.path);

        String originalContent;
        try {
            originalContent = new String(Files.readAllBytes(fullPath), StandardCharsets.UTF_8);
        } catch (IOException err) {
            throw new Exception("读取文件失败，请确认路径是否正确: " + err.getMessage(), err);
        }

        String newContent = fuzzyReplace(originalContent, input.old_text, input.new_text);

        try {
            Files.write(fullPath, newContent.getBytes(StandardCharsets.UTF_8));
        } catch (IOException err) {
            throw new Exception("写回文件失败: " + err.getMessage(), err);
        }

        return String.format("✅ 成功修改文件: %s", input.path);
    }

    // fuzzyReplace 实现了四级容错降级替换算法
    static String fuzzyReplace(String originalContent, String oldText, String newText) throws Exception {
        // L1: 精确匹配
        int count = count(originalContent, oldText);
        if (count == 1) {
            return replaceFirst(originalContent, oldText, newText);
        }
        if (count > 1) {
            throw new Exception(String.format("old_text 匹配到了 %d 处，请提供更多的上下文代码以确保唯一性", count));
        }

        // L2: 换行符归一化
        String normalizedContent = originalContent.replace("\r\n", "\n");
        String normalizedOld = oldText.replace("\r\n", "\n");

        count = count(normalizedContent, normalizedOld);
        if (count == 1) {
            return replaceFirst(normalizedContent, normalizedOld, newText);
        }

        // L3: Trim Space 匹配
        String trimmedOld = trimSpace(normalizedOld);
        if (!trimmedOld.isEmpty()) {
            count = count(normalizedContent, trimmedOld);
            if (count == 1) {
                return replaceFirst(normalizedContent, trimmedOld, newText);
            }
        }

        // L4: 逐行去缩进匹配
        return lineByLineReplace(normalizedContent, normalizedOld, newText);
    }

    static String lineByLineReplace(String content, String oldText, String newText) throws Exception {
        String[] contentLines = content.split("\n", -1);
        String[] oldLines = trimSpace(oldText).split("\n", -1);

        if (oldLines.length == 0 || contentLines.length < oldLines.length) {
            throw new Exception("找不到该代码片段");
        }

        for (int i = 0; i < oldLines.length; i++) {
            oldLines[i] = trimSpace(oldLines[i]);
        }

        int matchCount = 0;
        int matchStartIndex = -1;
        int matchEndIndex = -1;

        for (int i = 0; i <= contentLines.length - oldLines.length; i++) {
            boolean isMatch = true;
            for (int j = 0; j < oldLines.length; j++) {
                if (!trimSpace(contentLines[i + j]).equals(oldLines[j])) {
                    isMatch = false;
                    break;
                }
            }

            if (isMatch) {
                matchCount++;
                matchStartIndex = i;
                matchEndIndex = i + oldLines.length;
            }
        }

        if (matchCount == 0) {
            throw new Exception("在文件中未找到 old_text，请检查内容和缩进");
        }
        if (matchCount > 1) {
            throw new Exception(String.format("模糊匹配到了 %d 处代码，请提供更多上下文以定位", matchCount));
        }

        List<String> newContentLines = new ArrayList<String>();
        for (int i = 0; i < matchStartIndex; i++) {
            newContentLines.add(contentLines[i]);
        }
        newContentLines.add(newText);
        for (int i = matchEndIndex; i < contentLines.length; i++) {
            newContentLines.add(contentLines[i]);
        }

        return String.join("\n", newContentLines);
    }

    // 对齐 Go strings.Count：空子串返回码点数 + 1，否则计非重叠出现次数
    private static int count(String s, String substr) {
        if (substr.isEmpty()) {
            return s.codePointCount(0, s.length()) + 1;
        }
        int n = 0;
        int from = 0;
        while (true) {
            int i = s.indexOf(substr, from);
            if (i < 0) {
                return n;
            }
            n++;
            from = i + substr.length();
        }
    }

    // 对齐 Go strings.Replace(..., 1)
    private static String replaceFirst(String s, String oldText, String newText) {
        if (oldText.isEmpty()) {
            return newText + s;
        }
        int i = s.indexOf(oldText);
        if (i < 0) {
            return s;
        }
        return s.substring(0, i) + newText + s.substring(i + oldText.length());
    }

    // 对齐 Go strings.TrimSpace / unicode.IsSpace
    private static String trimSpace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isSpace(s.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(start, end);
    }

    private static boolean isSpace(char c) {
        switch (c) {
            case '\t':
            case '\n':
            case '\u000B':
            case '\f':
            case '\r':
            case ' ':
            case '\u0085':
            case '\u00A0':
                return true;
            default:
                return Character.isWhitespace(c);
        }
    }
}
