// internal/observability/trace.go
package github.javatinyclaw.internal.observability;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import github.javatinyclaw.context.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Span 代表链路追踪中的一个时间跨度和操作节点
public class Span {
    // traceKey 是 Context 中存放 Span 的专属 Key
    private static final class TraceKey {
    }

    private static final TraceKey TRACE_KEY = new TraceKey();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final class Start {
        public final Context ctx;
        public final Span span;

        private Start(Context ctx, Span span) {
            this.ctx = ctx;
            this.span = span;
        }
    }

    @JsonProperty("name")
    public String name;

    @JsonProperty("start_time")
    @JsonSerialize(using = Rfc3339Serializer.class)
    public OffsetDateTime startTime;

    @JsonProperty("end_time")
    @JsonSerialize(using = Rfc3339Serializer.class)
    public OffsetDateTime endTime;

    @JsonProperty("duration_ms")
    public long durationMs;

    @JsonProperty("attributes")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public Map<String, Object> attributes; // 存放元数据 (如消耗的 Token, 执行的命令)

    @JsonProperty("children")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public List<Span> children; // 子跨度

    @JsonIgnore
    private final Object mu = new Object(); // 保护 Children 的并发写入

    // startSpan 开启一个新的追踪跨度，并将其级联到 Context 中
    public static Start startSpan(Context ctx, String name) {
        Span span = new Span();
        span.name = name;
        span.startTime = OffsetDateTime.now();
        span.attributes = new HashMap<>();

        // 从 context 中尝试获取父 Span
        Object parentObj = ctx.value(TRACE_KEY);
        if (parentObj instanceof Span parent) {
            synchronized (parent.mu) {
                if (parent.children == null) {
                    parent.children = new ArrayList<>();
                }
                parent.children.add(span);
            }
        }

        // 将当前新创建的 Span 作为最新的父节点，塞入衍生 Context 并返回
        Context newCtx = ctx.withValue(TRACE_KEY, span);
        return new Start(newCtx, span);
    }

    // endSpan 结束跨度，计算耗时
    public void endSpan() {
        endTime = OffsetDateTime.now();
        durationMs = Duration.between(startTime, endTime).toMillis();
    }

    // addAttribute 为当前 Span 记录关键的元数据
    public void addAttribute(String key, Object value) {
        synchronized (mu) {
            attributes.put(key, value);
        }
    }

    // exportTraceToFile 当整个根 Span 结束时，将其序列化并保存为本地 JSON 文件
    public static void exportTraceToFile(Span rootSpan, String workDir, String sessionID) throws Exception {
        Path traceDir = Paths.get(workDir, ".claw", "traces");
        try {
            Files.createDirectories(traceDir);
        } catch (Exception ignored) {
        }

        Path filename = traceDir.resolve(String.format("trace_%s_%d.json", sessionID, OffsetDateTime.now().toEpochSecond()));

        // 美化输出 JSON，便于人类和工具阅读
        byte[] data = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(rootSpan);
        Files.write(filename, data);
    }

    static class Rfc3339Serializer extends JsonSerializer<OffsetDateTime> {
        @Override
        public void serialize(OffsetDateTime value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }
}
