package github.javatinyclaw.cmd.bench;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.eval.BenchmarkRunner;
import github.javatinyclaw.internal.eval.TestCase;

import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        String apiKey = System.getenv("ZHIPU_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("请先导出 ZHIPU_API_KEY 环境变量进行跑分测试");
            System.exit(1);
        }

        // 构建一套微型评测集
        List<TestCase> testcases = new ArrayList<>();

        TestCase test001 = new TestCase();
        test001.id = "test_001_edit";
        test001.name = "测试模糊替换工具的准确性";
        // 准备靶机：生成一个有错误的 json 文件
        test001.setupScript = "echo '{\"name\": \"tiny-claw\", \"version\": \"v1.0.0\"}' > config.json";
        // 考题：要求修改版本号
        test001.taskPrompt = "当前目录下有一个 config.json。请你使用 edit_file 工具，将其中的 version 从 v1.0.0 改为 v2.0.0。不要做其他多余操作。";
        // 判卷脚本：使用 grep 检查文件是否包含 v2.0.0
        test001.validateScript = "grep '\"version\": \"v2.0.0\"' config.json";
        testcases.add(test001);

        TestCase test002 = new TestCase();
        test002.id = "test_002_code_gen";
        test002.name = "测试代码阅读与创建新文件的综合能力";
        // 准备靶机：生成一个简单的乘法函数
        test002.setupScript = "echo 'package math\\n\\nfunc Multiply(a, b int) int {\\n\\treturn a * b\\n}' > math.go";
        // 考题：要求 Agent 根据刚才的代码，自己去写一份单元测试
        test002.taskPrompt = "当前目录下有一个 math.go。请你仔细阅读它，然后在同级目录下，帮我写一个规范的单元测试文件 math_test.go，用来测试 Multiply 函数。请务必包含正常的测试用例。";
        // 判卷脚本：直接运行 go test！如果不通过则直接 0 分。
        test002.validateScript = "go mod init bench && go test -v ./...";
        testcases.add(test002);

        // 启动跑分执行器！
        BenchmarkRunner runner = BenchmarkRunner.newBenchmarkRunner("glm-5.3-flash");
        runner.runSuite(Context.background(), testcases);
    }
}
