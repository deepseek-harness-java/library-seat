package cn.xiaofuge.s.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** AI 自习室管家插件：把 library-seat REST API 注册为 DSH Agent 工具 */
public class StudyPlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "study-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public StudyPlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new SeatSearchTool(),
                new ReserveTool(),
                new CheckinTool(),
                new MyBookingTool(),
                new StatsTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("study-capabilities", 20, """
                ## AI 自习室管家（图书馆自习区 · 2026-09-24）
                - 问有没有座 → seat_search（可按 room：静音区/研讨区/电子阅览区/通宵区，type：普通座/电源座/窗边座/卡座，status 过滤）
                - 想预约 → reserve（seatId 格式「区域-编号」如 静音区-A03 + student + studentId + span 时段；
                  预约前必须复述座位/时段请用户确认；维护中与被占座位会被拦截；本周违约 2 次暂停预约 3 天）
                - 到馆后 → checkin（bookingId；各区域有签到时限：静音/电子阅览 15 分钟、研讨 20 分钟、通宵 10 分钟，超时记违约）
                - 查我的预约/违约 → my_booking（studentId；列出预约记录与违约原因）
                - 问人流/余座 → stats（各区使用率、签到规则、错峰建议）
                - 回答要求：
                  1) 预约成功必报预约单号（B 前缀）与签到时限
                  2) 座位被占/维护中要主动推荐同区域替代座位
                  3) 违约规则（超时未签到记违约、2 次暂停 3 天）必须主动提示
                  4) 数据来自工具返回，禁止编造座位与余量
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: study tool call.");
            }
            return null;
        });
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("STUDY_APP_BASE_URL", "http://127.0.0.1:18100")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private class SeatSearchTool extends AbstractTool {
        @Override public String name() { return "seat_search"; }
        @Override public String description() {
            return "座位查询：按区域（静音区/研讨区/电子阅览区/通宵区）、类型（普通座/电源座/窗边座/卡座）、状态（空闲/已预约/使用中/维护中）过滤，"
                    + "返回各区总数与空闲数。何时必须调用：问有没有座、推荐座位。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("room", stringSchema("可选：区域名关键字"))
                    .prop("type", stringSchema("可选：座位类型"))
                    .prop("status", stringSchema("可选：空闲 / 已预约 / 使用中 / 维护中"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String qs = "";
            String room = str(args, "room"), type = str(args, "type"), status = str(args, "status");
            if (!room.isBlank()) qs += "room=" + java.net.URLEncoder.encode(room, StandardCharsets.UTF_8) + "&";
            if (!type.isBlank()) qs += "type=" + java.net.URLEncoder.encode(type, StandardCharsets.UTF_8) + "&";
            if (!status.isBlank()) qs += "status=" + java.net.URLEncoder.encode(status, StandardCharsets.UTF_8);
            return ok(get("/api/seats" + (qs.isBlank() ? "" : "?" + qs), args));
        }
    }

    private class ReserveTool extends AbstractTool {
        @Override public String name() { return "reserve"; }
        @Override public String description() {
            return "座位预约：seatId（区域-编号 如 静音区-A03）+ student + studentId + span（时段 如 今天 19:00-21:00）。"
                    + "必须先复述座位与时段经用户确认后才能调用。返回预约单号与签到时限。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("seatId", stringSchema("座位 ID，格式 区域-编号，如 静音区-A03"))
                    .prop("student", stringSchema("学生姓名"))
                    .prop("studentId", stringSchema("学号"))
                    .prop("span", stringSchema("预约时段，如 今天 19:00-21:00"))
                    .required("seatId", "student", "studentId", "span")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"seatId\":\"" + json(str(args, "seatId"))
                    + "\",\"student\":\"" + json(str(args, "student"))
                    + "\",\"studentId\":\"" + json(str(args, "studentId"))
                    + "\",\"span\":\"" + json(str(args, "span")) + "\"}";
            return ok(post("/api/reserve", body, args));
        }
    }

    private class CheckinTool extends AbstractTool {
        @Override public String name() { return "checkin"; }
        @Override public String description() {
            return "到馆签到：bookingId 必填。仅「待签到」可签，签到后座位转「使用中」。超时限未签到会记违约。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("bookingId", stringSchema("预约单号，如 B3005"))
                    .required("bookingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"bookingId\":\"" + json(str(args, "bookingId")) + "\"}";
            return ok(post("/api/checkin", body, args));
        }
    }

    private class MyBookingTool extends AbstractTool {
        @Override public String name() { return "my_booking"; }
        @Override public String description() {
            return "我的预约与违约记录：studentId 必填。列出预约历史（状态）与违约原因。"
                    + "何时必须调用：查我的预约、取消预约前、问违约。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("studentId", stringSchema("学号"))
                    .prop("status", stringSchema("可选：待签到 / 使用中 / 已完成 / 已违约 / 已取消"))
                    .required("studentId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String qs = "studentId=" + java.net.URLEncoder.encode(str(args, "studentId"), StandardCharsets.UTF_8);
            String status = str(args, "status");
            if (!status.isBlank()) qs += "&status=" + java.net.URLEncoder.encode(status, StandardCharsets.UTF_8);
            return ok(get("/api/bookings?" + qs, args));
        }
    }

    private class StatsTool extends AbstractTool {
        @Override public String name() { return "stats"; }
        @Override public String description() {
            return "自习区统计：各区总座/空闲/使用率/开放时间/规则、预约状态分布、违约次数、错峰建议。"
                    + "何时必须调用：问人流、问哪区人少、问规则。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/stats", args));
        }
    }
}
