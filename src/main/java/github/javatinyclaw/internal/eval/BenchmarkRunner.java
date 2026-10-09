package github.javatinyclaw.internal.eval;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.engine.AgentEngine;
import github.javatinyclaw.internal.observability.CostTracker;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.provider.OpenAIProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.Role;
import github.javatinyclaw.internal.tools.BashTool;
import github.javatinyclaw.internal.tools.EditFileTool;
import github.javatinyclaw.internal.tools.ReadFileTool;
import github.javatinyclaw.internal.tools.Registry;
import github.javatinyclaw.internal.tools.RegistryImpl;
import github.javatinyclaw.internal.tools.WriteFileTool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {
    private final String modelName;

    public static BenchmarkRunner newBenchmarkRunner(String model) {
        return new BenchmarkRunner(model);
    }

    private BenchmarkRunner(String model) {
        this.modelName = model;
    }

    // RunSuite 执行一组评测集，并返回跑分报告
    public void runSuite(Context ctx, List<TestCase> testcases) {
        System.err.println("==================================================");
        System.err.printf("🚀 启动自动化 Harness Benchmark 评估... | 模型: %s\n", modelName);
        System.err.println("==================================================");

        List<TestResult> results = new ArrayList<>();
        int passedCount = 0;
        double totalCost = 0.0;

        for (TestCase tc : testcases) {
            System.err.printf("\n>>> ⏳ 正在执行用例 [%s]: %s\n", tc.id, tc.name);

            TestResult res = runSingleTest(ctx, tc);
            results.add(res);

            if (res.passed) {
                passedCount++;
                System.err.printf(">>> ✅ 用例 [%s] 测试通过! | 耗时: %dms | 花费: $%.6f\n", tc.id, res.durationMs, res.totalCostCNY);
            } else {
                System.err.printf(">>> ❌ 用例 [%s] 测试失败! | 错误: %s\n", tc.id, res.errorMsg);
            }
            totalCost += res.totalCostCNY;
        }

        // 打印终极报表
        System.err.println("\n================ 🏆 跑分终极报告 ================");
        System.err.printf("总用例数: %d | 成功数: %d | 成功率: %.2f%%\n", testcases.size(), passedCount, (double) passedCount / (double) testcases.size() * 100);
        System.err.printf("总消耗成本: $%.6f\n", totalCost);
        System.err.println("==================================================");
    }

    TestResult runSingleTest(Context ctx, TestCase tc) {
        Instant startTime = Instant.now();

        // 1. 为每个用例创建一个绝对干净的沙箱目录 (物理隔离)
        String workDir = System.getProperty("user.dir");
        workDir += String.format("/workspace/%s_%d", tc.id, Instant.now().getEpochSecond());
        new File(workDir).mkdirs();

        // 2. (可选) 执行 Setup 脚本准备靶机代码
        if (tc.setupScript != null && !tc.setupScript.isEmpty()) {
            try {
                ProcessBuilder cmd = new ProcessBuilder("bash", "-c", tc.setupScript);
                cmd.directory(new File(workDir));
                cmd.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                cmd.redirectError(ProcessBuilder.Redirect.DISCARD);
                int exitCode = cmd.start().waitFor();
                if (exitCode != 0) {
                    TestResult result = new TestResult();
                    result.testCaseId = tc.id;
                    result.passed = false;
                    result.errorMsg = "靶机 Setup 失败";
                    return result;
                }
            } catch (Exception err) {
                TestResult result = new TestResult();
                result.testCaseId = tc.id;
                result.passed = false;
                result.errorMsg = "靶机 Setup 失败";
                return result;
            }
        }

        // 3. 组装具备打点能力 (Tracker) 的引擎
        LLMProvider realProvider = OpenAIProvider.newZhipuOpenAIProvider(modelName); // 使用真实的 GLM API
        Session session = Session.newSession(tc.id, workDir);                         // 为本次跑分单独建一个 Session 记账
        LLMProvider trackedProvider = CostTracker.newCostTracker(realProvider, modelName, session);

        Registry registry = RegistryImpl.newRegistry();
        registry.register(ReadFileTool.newReadFileTool(workDir));
        registry.register(WriteFileTool.newWriteFileTool(workDir));
        registry.register(BashTool.newBashTool(workDir));
        registry.register(EditFileTool.newEditFileTool(workDir));

        AgentEngine eng = new AgentEngine(trackedProvider, registry, false, false);

        // 4. 让 Agent 开始干活
        Message userMsg = new Message();
        userMsg.role = Role.USER;
        userMsg.content = tc.taskPrompt;
        session.append(userMsg);
        // 我们传入一个空的 reporter 屏蔽普通日志，防止刷屏
        try {
            eng.run(ctx, session, null);
        } catch (Exception err) {
            TestResult result = new TestResult();
            result.testCaseId = tc.id;
            result.passed = false;
            result.errorMsg = String.format("Agent 崩溃: %s", err);
            return result;
        }

        // 5. 【核心断言】Agent 跑完了，我们来验收成果！
        byte[] out;
        int exitCode;
        try {
            ProcessBuilder cmd = new ProcessBuilder("bash", "-c", tc.validateScript);
            cmd.directory(new File(workDir));
            cmd.redirectErrorStream(true);
            Process process = cmd.start();
            out = process.getInputStream().readAllBytes();
            exitCode = process.waitFor();
        } catch (Exception err) {
            out = err.toString().getBytes(StandardCharsets.UTF_8);
            exitCode = 1;
        }

        long duration = Instant.now().toEpochMilli() - startTime.toEpochMilli();

        if (exitCode != 0) {
            TestResult result = new TestResult();
            result.testCaseId = tc.id;
            result.passed = false;
            result.totalCostCNY = session.totalCostCNY;
            result.durationMs = duration;
            result.errorMsg = String.format("验证脚本执行失败: %s", new String(out, StandardCharsets.UTF_8));
            return result;
        }

        TestResult result = new TestResult();
        result.testCaseId = tc.id;
        result.passed = true;
        result.totalCostCNY = session.totalCostCNY;
        result.durationMs = duration;
        return result;
    }
}
