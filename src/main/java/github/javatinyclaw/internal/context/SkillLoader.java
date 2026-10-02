package github.javatinyclaw.internal.context;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// SkillLoader 负责从本地文件系统中加载并解析符合规范的技能模板
public class SkillLoader {
    private final String workDir;

    public static SkillLoader newSkillLoader(String workDir) {
        return new SkillLoader(workDir);
    }

    private SkillLoader(String workDir) {
        this.workDir = workDir;
    }

    // LoadAll 扫描 .claw/skills 目录，解析所有 SKILL.md，并格式化为字符串准备注入 Context
    public String loadAll() {
        Path skillBaseDir = Paths.get(workDir, ".claw", "skills");

        // 如果目录不存在，说明当前工作区没有配置技能，静默返回
        // 对齐 os.Stat + os.IsNotExist：只有确认不存在才提前返回，其它错误留给遍历
        if (Files.notExists(skillBaseDir)) {
            return "";
        }

        StringBuilder skillsBuilder = new StringBuilder();
        skillsBuilder.append("\n### 可用专业技能 (Agent Skills)\n");
        skillsBuilder.append("以下是你拥有的标准化外挂技能，请在符合 description 描述的场景下严格遵循其正文指令：\n\n");

        // 遍历查找 SKILL.md。对齐 filepath.WalkDir：前序、同级文件名字典序、不跟随符号链接
        IOException err = walkDir(skillBaseDir, skillsBuilder);

        // strings.Builder.Len() 是 UTF-8 字节数，不能用 String.length() 的字符数
        if (err != null || utf8Len(skillsBuilder) < 100) {
            return "";
        }

        return skillsBuilder.toString();
    }

    // parseSkillMD 极简解析带有 YAML Frontmatter 的 Markdown 内容
    static Skill parseSkillMD(String content) {
        Skill skill = new Skill();
        skill.name = "Unknown Skill";
        skill.description = "No description provided.";
        skill.body = content; // 默认将全量内容作为 body

        // 简单解析 YAML Frontmatter (以 --- 包裹)
        if (content.startsWith("---\n") || content.startsWith("---\r\n")) {
            // 对齐 strings.SplitN(content, "---", 3)，正文里的 --- 留在最后一段
            String[] parts = content.split("---", 3);
            if (parts.length == 3) {
                String frontmatter = parts[1];
                skill.body = parts[2].trim();

                // 逐行提取 metadata。split 的 limit 为 -1，保留末尾空行
                String[] lines = frontmatter.split("\n", -1);
                for (String line : lines) {
                    line = line.trim();
                    if (line.startsWith("name:")) {
                        skill.name = line.substring("name:".length()).trim();
                    } else if (line.startsWith("description:")) {
                        skill.description = line.substring("description:".length()).trim();
                    }
                }
            }
        }

        return skill;
    }

    // 对齐 filepath.WalkDir：先访问当前节点，目录再按文件名排序后深入，不跟随符号链接
    private static IOException walkDir(Path path, StringBuilder skillsBuilder) {
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException err) {
            return err;
        }

        boolean isDir = attrs.isDirectory();
        Path fileName = path.getFileName();
        // 仅处理名为 SKILL.md 的文件
        if (!isDir && fileName != null && fileName.toString().equals("SKILL.md")) {
            try {
                byte[] content = Files.readAllBytes(path);
                Skill skill = parseSkillMD(new String(content, StandardCharsets.UTF_8));

                // 将解析后的技能按结构注入
                skillsBuilder.append(String.format("#### 技能名称: %s\n", skill.name));
                skillsBuilder.append(String.format("**触发条件**: %s\n\n", skill.description));
                skillsBuilder.append("**执行指南**:\n");
                skillsBuilder.append(skill.body);
                skillsBuilder.append("\n\n---\n");
            } catch (IOException ignored) {
                // 读失败则跳过该文件，与 Go 的 if err == nil 一致
            }
        }

        if (!isDir) {
            return null;
        }

        List<Path> children = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (IOException err) {
            return err;
        }

        children.sort(Comparator.comparing(child -> child.getFileName().toString()));
        for (Path child : children) {
            IOException err = walkDir(child, skillsBuilder);
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    private static int utf8Len(StringBuilder builder) {
        return builder.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
