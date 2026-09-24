package cn.xiaofuge.s.app;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** 图书馆自习室数据中心：阅览区/座位、预约签到、违约记录、时段统计 */
@Component
public class SStore {

    static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 区域规则：预约票数上限 / 违约阈值（分钟内不签到算违约） */
    static final Map<String, int[]> AREA_RULE = Map.of(
            "静音区", new int[]{30, 15},   // 30 座，15 分钟内签到
            "研讨区", new int[]{16, 20},
            "电子阅览区", new int[]{24, 15},
            "通宵区", new int[]{12, 10});

    public static class Room {
        public String id; public String name; public int total; public String openTime; public String rule;
    }

    public static class Seat {
        public String id; public String roomId; public String no; public String type; // 普通座/电源座/窗边座/卡座
        public String status; // 空闲/已预约/使用中/维护中
        public String holder; public String until; // 预约到几点
    }

    public static class Booking {
        public String id; public String seatId; public String student; public String studentId;
        public String date; public String start; public String end; public String status; // 待签到/使用中/已完成/已违约/已取消
        public String createdAt; public String checkinAt; public String note;
    }

    public static class Violation {
        public String studentId; public String student; public String reason; public String at;
    }

    public final List<Room> rooms = new ArrayList<>();
    public final List<Seat> seats = new ArrayList<>();
    public final List<Booking> bookings = new ArrayList<>();
    public final List<Violation> violations = new ArrayList<>();
    private int seatSeq = 1;
    private int bookingSeq = 3001;

    public SStore() { seed(); }

    private void seed() {
        rooms.add(room("R01", "静音区", "07:00-22:30", "禁音禁食，15 分钟内扫码签到"));
        rooms.add(room("R02", "研讨区", "08:00-21:30", "可小组讨论，需 3 人以上同约"));
        rooms.add(room("R03", "电子阅览区", "07:30-22:00", "仅限电脑/平板学习，15 分钟内签到"));
        rooms.add(room("R04", "通宵区", "22:00-次日 07:00", "凭当日其他区记录申请，10 分钟内签到"));

        for (int i = 1; i <= 12; i++) seats.add(seat("静音区", "A" + String.format("%02d", i), i % 3 == 0 ? "电源座" : "普通座", "空闲"));
        for (int i = 1; i <= 8; i++) seats.add(seat("研讨区", "B" + String.format("%02d", i), i <= 2 ? "卡座" : "普通座", "空闲"));
        for (int i = 1; i <= 10; i++) seats.add(seat("电子阅览区", "C" + String.format("%02d", i), "电源座", "空闲"));
        for (int i = 1; i <= 6; i++) seats.add(seat("通宵区", "D" + String.format("%02d", i), i == 1 ? "维护中" : "窗边座", i == 1 ? "维护中" : "空闲"));

        // 预置占用
        occupy("静音区", "A01", "陈同学", "20213001", "今天 18:00", "使用中", "09-24 08:12");
        occupy("静音区", "A02", "林同学", "20213002", "今天 18:00", "使用中", "09-24 08:20");
        occupy("电子阅览区", "C03", "黄同学", "20213003", "今天 21:00", "使用中", "09-24 09:00");
        bookings.add(bk("B3001", "静音区-A05", "周同学", "20213004", "今天 14:00-18:00", "已违约", "09-24 07:40", "", "超 15 分钟未签到，记违约 1 次"));
        violations.add(v("20213004", "周同学", "预约后未按时签到", "09-24 14:16"));
        bookings.add(bk("B3002", "研讨区-B04", "吴同学", "20213005", "今天 19:00-21:00", "待签到", "09-24 10:00", "", ""));
        bookings.add(bk("B3003", "通宵区-D02", "郑同学", "20213006", "昨晚 22:00-今晨 07:00", "已完成", "09-23 20:00", "09-23 21:50", ""));
    }

    private Room room(String id, String name, String open, String rule) {
        Room x = new Room(); x.id = id; x.name = name; x.openTime = open; x.rule = rule;
        x.total = AREA_RULE.get(name)[0]; return x;
    }

    private Seat seat(String roomIdName, String no, String type, String status) {
        Seat x = new Seat(); x.id = roomIdName + "-" + no; x.roomId = roomIdName; x.no = no; x.type = type;
        x.status = status; return x;
    }

    private void occupy(String roomName, String no, String holder, String sid, String until, String status, String at) {
        Seat s = seats.stream().filter(x -> x.id.equals(roomName + "-" + no)).findFirst().orElse(null);
        if (s != null) { s.status = status; s.holder = holder; s.until = until; }
        bookings.add(bk("B" + bookingSeq++, roomName + "-" + no, holder, sid, until, status, at, at, ""));
    }

    private Booking bk(String id, String seatId, String student, String studentId, String span, String status, String at, String checkin, String note) {
        Booking x = new Booking(); x.id = id; x.seatId = seatId; x.student = student; x.studentId = studentId;
        String[] parts = span.split(" ");
        x.date = "今天"; x.start = span; x.end = ""; x.status = status; x.createdAt = at;
        x.checkinAt = checkin; x.note = note; return x;
    }

    private Violation v(String sid, String student, String reason, String at) {
        Violation x = new Violation(); x.studentId = sid; x.student = student; x.reason = reason; x.at = at; return x;
    }

    /** 座位查询：按区域/类型/状态过滤 */
    public Map<String, Object> search(String roomName, String type, String status) {
        List<Seat> list = seats.stream()
                .filter(s -> roomName == null || roomName.isBlank() || s.roomId.contains(roomName))
                .filter(s -> type == null || type.isBlank() || s.type.contains(type))
                .filter(s -> status == null || status.isBlank() || s.status.equals(status))
                .collect(Collectors.toList());
        Map<String, Long> byRoom = seats.stream().collect(Collectors.groupingBy(s -> s.roomId, Collectors.counting()));
        Map<String, Long> freeByRoom = seats.stream().filter(s -> "空闲".equals(s.status))
                .collect(Collectors.groupingBy(s -> s.roomId, Collectors.counting()));
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("total", seats.size()); r.put("free", seats.stream().filter(s -> "空闲".equals(s.status)).count());
        r.put("byRoom", byRoom); r.put("freeByRoom", freeByRoom);
        r.put("seats", list.stream().limit(30).collect(Collectors.toList()));
        return r;
    }

    /** 预约：校验违约资格（本周违约≥2 拉黑 3 天）+ 座位空闲 */
    public synchronized Map<String, Object> reserve(String seatId, String student, String studentId, String span) {
        Seat s = seats.stream().filter(x -> x.id.equals(seatId)).findFirst().orElse(null);
        if (s == null) return Map.of("ok", false, "msg", "座位 " + seatId + " 不存在，请用「区域-编号」格式如 静音区-A03");
        if ("维护中".equals(s.status)) return Map.of("ok", false, "msg", "座位 " + seatId + " 维护中，暂不可预约");
        if (!"空闲".equals(s.status)) return Map.of("ok", false, "msg", "座位 " + seatId + " 当前「" + s.status + "」，请换一座位");
        long weekViol = violations.stream().filter(v -> v.studentId.equals(studentId)).count();
        if (weekViol >= 2) return Map.of("ok", false, "msg", "您本周已有 " + weekViol + " 次违约，账户暂停预约 3 天");
        int ruleMin = AREA_RULE.getOrDefault(s.roomId, new int[]{30, 15})[1];
        Booking x = new Booking();
        x.id = "B" + bookingSeq++;
        x.seatId = seatId; x.student = student; x.studentId = studentId;
        x.date = "今天"; x.start = span; x.end = ""; x.status = "待签到";
        x.createdAt = LocalDateTime.now().format(HM);
        bookings.add(0, x);
        s.status = "已预约"; s.holder = student;
        s.until = span;
        return Map.of("ok", true, "bookingId", x.id, "seat", seatId, "student", student,
                "checkinRule", "请在预约开始后 " + ruleMin + " 分钟内扫码签到，超时记违约 1 次");
    }

    /** 签到 */
    public synchronized Map<String, Object> checkin(String bookingId) {
        Booking b = bookings.stream().filter(x -> x.id.equals(bookingId)).findFirst().orElse(null);
        if (b == null) return Map.of("ok", false, "msg", "预约单 " + bookingId + " 不存在");
        if (!"待签到".equals(b.status)) return Map.of("ok", false, "msg", "预约单 " + bookingId + " 状态为「" + b.status + "」，无需签到");
        b.status = "使用中"; b.checkinAt = LocalDateTime.now().format(HM);
        Seat s = seats.stream().filter(x -> x.id.equals(b.seatId)).findFirst().orElse(null);
        if (s != null) { s.status = "使用中"; }
        return Map.of("ok", true, "bookingId", b.id, "status", b.status, "checkinAt", b.checkinAt,
                "msg", "签到成功，祝学习愉快");
    }

    /** 取消预约 */
    public synchronized Map<String, Object> cancel(String bookingId) {
        Booking b = bookings.stream().filter(x -> x.id.equals(bookingId)).findFirst().orElse(null);
        if (b == null) return Map.of("ok", false, "msg", "预约单 " + bookingId + " 不存在");
        if (!"待签到".equals(b.status)) return Map.of("ok", false, "msg", "预约单 " + bookingId + " 状态为「" + b.status + "」，不能取消");
        b.status = "已取消";
        Seat s = seats.stream().filter(x -> x.id.equals(b.seatId)).findFirst().orElse(null);
        if (s != null) { s.status = "空闲"; s.holder = ""; s.until = ""; }
        return Map.of("ok", true, "bookingId", b.id, "msg", "已取消，座位释放回公共池");
    }

    /** 时段热度与建议 */
    public Map<String, Object> stats() {
        Map<String, Long> byStatus = bookings.stream().collect(Collectors.groupingBy(x -> x.status, Collectors.counting()));
        Map<String, Object> rooms2 = new LinkedHashMap<String, Object>();
        for (Room rm : rooms) {
            long total = seats.stream().filter(x -> x.roomId.equals(rm.name)).count();
            long free = seats.stream().filter(x -> x.roomId.equals(rm.name) && "空闲".equals(x.status)).count();
            long used = total - free;
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("total", total); m.put("free", free); m.put("used", used);
            m.put("usageRate", total > 0 ? Math.round(used * 1000.0 / total) / 10.0 : 0);
            m.put("openTime", rm.openTime); m.put("rule", rm.rule);
            rooms2.put(rm.name, m);
        }
        long viol = violations.size();
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("totalSeats", seats.size());
        r.put("freeSeats", seats.stream().filter(x -> "空闲".equals(x.status)).count());
        r.put("byStatus", byStatus);
        r.put("violations", viol);
        r.put("rooms", rooms2);
        r.put("advice", "静音区余座紧张（使用率超 80% 时建议错峰），通宵区需凭当日记录申请；违约 2 次暂停预约 3 天");
        return r;
    }
}
