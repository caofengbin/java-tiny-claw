package github.javatinyclaw.internal.context;

import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

// PromptComposer 负责根据工作区环境动态生成 System Prompt
public class PromptComposer {
    private final String workDir;
    private final SkillLoader skillLoader;

    public static PromptComposer newPromptComposer(String workDir) {
        return new PromptComposer(workDir);
    }

    private PromptComposer(String workDir) {
        this.workDir = workDir;
        this.skillLoader = SkillLoader.newSkillLoader(workDir);
    }

    // Build 组装并返回一条完整的 RoleSystem 消息
    public Message build() {
        StringBuilder promptBuilder = new StringBuilder();

        // 1. 极简内核 (Minimal Core)
        // 仅确立基本身份与最底线的红线纪律
        promptBuilder.append("# 核心身份\n");
        promptBuilder.append("你名叫 java-tiny-claw，一个由驾驭工程驱动的骨灰级研发助手。\n");
        promptBuilder.append("你具备极简主义哲学，拒绝废话。你能通过系统提供的内置工具，创建、读取、修改和执行工作区中的代码。\n");
        promptBuilder.append("\n");
        promptBuilder.append("# 核心纪律 (CRITICAL)\n");
        promptBuilder.append("1. 如需检查文件是否存在，请使用 bash 的 ls 或 test -f，而不是对目录使用 read_file。\n");
        promptBuilder.append("2. 创建新文件时，务必使用 write_file，并同时提供 path 和 content 参数。\n");
        promptBuilder.append("3. 编辑文件前务必先读取现有文件，以理解上下文。\n");
        promptBuilder.append("4. 无论何时你需要写代码或创建文件，都要直接使用 write_file 工具。\n");
        promptBuilder.append("5. 遇到工具执行报错时，仔细阅读 stderr，尝试自己修正命令并重试。\n");
        promptBuilder.append("6. 始终用中文回复，以便传达你的进展和想法。\n");

        // 2. 外部化状态：加载项目专属规范 (AGENTS.md)
        Path agentsMDPath = Paths.get(workDir, "AGENTS.md");
        try {
            byte[] content = Files.readAllBytes(agentsMDPath);
            promptBuilder.append("\n# 项目专属指南 (来自 AGENTS.md)\n");
            promptBuilder.append("以下是当前工作区特有的架构规范与注意事项，你的行为必须绝对符合以下要求：\n");
            promptBuilder.append("```markdown\n");
            promptBuilder.append(new String(content, StandardCharsets.UTF_8));
            promptBuilder.append("\n```\n");
        } catch (IOException ignored) {
            // 读取失败则跳过，不打日志
        }

        // 3. 动态加载技能外挂 (Skills)
        String skillsContent = skillLoader.loadAll();
        if (!skillsContent.isEmpty()) {
            promptBuilder.append(skillsContent);
        }

        Message message = new Message();
        message.role = Role.SYSTEM;
        message.content = promptBuilder.toString();
        return message;
    }
}
