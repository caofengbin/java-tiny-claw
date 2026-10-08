// internal/observability/tracker.go
package github.javatinyclaw.internal.observability;

import github.javatinyclaw.context.Context;
import github.javatinyclaw.internal.context.Session;
import github.javatinyclaw.internal.provider.LLMProvider;
import github.javatinyclaw.internal.schema.Message;
import github.javatinyclaw.internal.schema.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

// PricingModel 定义了不同大模型的计费标准 (单位: 美元/1M Tokens)
// 为了演示，这里硬编码了当前市面上几个主流模型的官方大致定价。
class Pricing {
    public double inputPrice;
    public double outputPrice;

    Pricing(double inputPrice, double outputPrice) {
        this.inputPrice = inputPrice;
        this.outputPrice = outputPrice;
    }
}

// CostTracker 是一个包装了真实 LLMProvider 的装饰器中间件
public class CostTracker implements LLMProvider {
    public static final Map<String, Pricing> PricingModel = new HashMap<>();

    static {
        PricingModel.put("glm-4.5-air", new Pricing(0.15, 0.15)); // 这里假定的大模型价格(每百万Token，tk)
        PricingModel.put("glm-5.3-flash", new Pricing(0.15, 0.15)); // 智谱GLM-5.3-Flash--走腾讯云TokenHub提供集成
    }

    private final LLMProvider nextProvider;
    private final String modelName;
    private final Session session; // 当前所属的会话 (用于累加总成本)

    // NewCostTracker 构造函数：接收一个现有的 Provider，返回一个被监控的 Provider
    public static CostTracker newCostTracker(LLMProvider next, String modelName, Session session) {
        return new CostTracker(next, modelName, session);
    }

    private CostTracker(LLMProvider next, String modelName, Session session) {
        this.nextProvider = next;
        this.modelName = modelName;
        this.session = session;
    }

    // Generate 实现了 LLMProvider 接口！这意味着它可以被无缝注入到 Main Loop 中。
    @Override
    public Message generate(Context ctx, List<Message> msgs, List<ToolDefinition> availableTools) throws Exception {
        // 1. 记录请求发起的时刻
        long startTime = System.nanoTime();

        // 2. 调用真实的底层大模型去执行耗时的网络请求
        Message respMsg;
        try {
            respMsg = nextProvider.generate(ctx, msgs, availableTools);
        } catch (Exception err) {
            // 3. 计算耗时
            long latency = System.nanoTime() - startTime;
            // 如果报错了，只打印报错时间，不计费
            System.err.printf("[Tracker] ❌ API 调用失败，耗时: %s%n", formatDuration(latency));
            throw err;
        }

        // 3. 计算耗时
        long latency = System.nanoTime() - startTime;

        // 4. 解析 Token 并计算成本
        if (respMsg.usage != null) {
            int promptTokens = respMsg.usage.promptTokens;
            int completionTokens = respMsg.usage.completionTokens;

            double cost = 0.0;
            Pricing price = PricingModel.get(modelName);
            if (price != null) {
                // 计算美元花费 = (输入Tokens * 输入单价 + 输出Tokens * 输出单价) / 1000000
                cost = (promptTokens * price.inputPrice + completionTokens * price.outputPrice) / 1000000.0;
            }

            // 5. 打印精美的仪表盘日志
            System.err.printf("[Tracker] 📊 API 调用完成 | 耗时: %s | 输入: %d tk | 输出: %d tk | 花费: ¥%.6f%n",
                formatDuration(latency), promptTokens, completionTokens, cost);

            // 6. 将账单累加到当前的 Session 中，供人类后续随时查询
            if (session != null) {
                session.recordUsage(promptTokens, completionTokens, cost);
                System.err.printf("[Tracker] 💰 当前会话 (%s) 累计花费: ¥%.6f%n", session.id, session.totalCostCNY);
            }
        } else {
            System.err.printf("[Tracker] ⚠️ API 调用完成，但未返回 Usage 数据 | 耗时: %s%n", formatDuration(latency));
        }

        return respMsg;
    }

    // 对齐 Go time.Duration 的 %v 格式（如 1.234s、200ms）
    private static String formatDuration(long nanos) {
        boolean neg = nanos < 0;
        long u = neg ? -nanos : nanos;
        String sign = neg ? "-" : "";

        if (u < 1_000_000_000L) {
            if (u == 0) {
                return "0s";
            }
            if (u < 1_000L) {
                return sign + u + "ns";
            }
            if (u < 1_000_000L) {
                return sign + formatFrac(u, 3) + "µs";
            }
            return sign + formatFrac(u, 6) + "ms";
        }

        long hours = u / 3_600_000_000_000L;
        u = u % 3_600_000_000_000L;
        long minutes = u / 60_000_000_000L;
        u = u % 60_000_000_000L;
        String seconds = formatFrac(u, 9);

        StringBuilder sb = new StringBuilder();
        sb.append(sign);
        if (hours > 0) {
            sb.append(hours).append('h');
            sb.append(minutes).append('m');
            sb.append(seconds).append('s');
        } else if (minutes > 0) {
            sb.append(minutes).append('m');
            sb.append(seconds).append('s');
        } else {
            sb.append(seconds).append('s');
        }
        return sb.toString();
    }

    private static String formatFrac(long v, int prec) {
        long scale = 1;
        for (int i = 0; i < prec; i++) {
            scale *= 10;
        }
        long intPart = v / scale;
        long frac = v % scale;
        if (frac == 0) {
            return Long.toString(intPart);
        }
        String fracStr = String.format("%0" + prec + "d", frac);
        int end = fracStr.length();
        while (end > 0 && fracStr.charAt(end - 1) == '0') {
            end--;
        }
        return intPart + "." + fracStr.substring(0, end);
    }
}
