package cn.xiaofuge.s.app;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 自习室 REST API */
@RestController
public class SController {

    private final SStore store;

    public SController(SStore store) { this.store = store; }

    @GetMapping("/api/rooms")
    public Map<String, Object> rooms() {
        return Map.of("code", 0, "data", store.rooms);
    }

    @GetMapping("/api/seats")
    public Map<String, Object> seats(@RequestParam(required = false) String room,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) String status) {
        return Map.of("code", 0, "data", store.search(room, type, status));
    }

    @PostMapping("/api/reserve")
    public Map<String, Object> reserve(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.reserve(
                String.valueOf(body.getOrDefault("seatId", "")),
                String.valueOf(body.getOrDefault("student", "")),
                String.valueOf(body.getOrDefault("studentId", "")),
                String.valueOf(body.getOrDefault("span", ""))));
    }

    @PostMapping("/api/checkin")
    public Map<String, Object> checkin(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.checkin(String.valueOf(body.getOrDefault("bookingId", ""))));
    }

    @PostMapping("/api/cancel")
    public Map<String, Object> cancel(@RequestBody Map<String, Object> body) {
        return Map.of("code", 0, "data", store.cancel(String.valueOf(body.getOrDefault("bookingId", ""))));
    }

    @GetMapping("/api/bookings")
    public Map<String, Object> bookings(@RequestParam(required = false) String studentId,
                                        @RequestParam(required = false) String status) {
        var list = store.bookings.stream()
                .filter(b -> studentId == null || studentId.isBlank() || b.studentId.equals(studentId))
                .filter(b -> status == null || status.isBlank() || b.status.equals(status))
                .toList();
        return Map.of("code", 0, "data", list);
    }

    @GetMapping("/api/violations")
    public Map<String, Object> violations() {
        return Map.of("code", 0, "data", store.violations);
    }

    @GetMapping("/api/stats")
    public Map<String, Object> stats() {
        return Map.of("code", 0, "data", store.stats());
    }
}
