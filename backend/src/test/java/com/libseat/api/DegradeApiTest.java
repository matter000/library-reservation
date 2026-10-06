package com.libseat.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * PRD §13.4 高峰降级：过载时只有"预约"这一侧排队并返回 4291，查看余座不受影响。
 *
 * <p>★ 用例把在途许可压到 0，而不是真造并发：并发只能证明"变慢"，证明不了降级语义；
 *   而 0 恰好就是这条规则留给运营的"暂停预约"开关 —— 测的就是同一条代码路径。
 *   共享内存库里改的是全局态，所以每一处修改都在 finally 里复原。
 */
class DegradeApiTest extends ApiTestBase {

    private static final int BOOKING_DEGRADED = 4291;

    private void rule(String rkey, String value) throws Exception {
        MvcResult r = exec(put("/api/admin/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(List.of(Map.of("rkey", rkey, "value", value))))
                .header("Authorization", bearer(admin)));
        assertEquals(0, code(r), "规则 " + rkey + " 应可修改");
    }

    private MvcResult book(String token, long seat, String date, int startSlot, int hours) throws Exception {
        return exec(post("/api/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("seatId", seat, "date", date,
                        "startSlot", startSlot, "duration", hours)))
                .header("Authorization", bearer(token)));
    }

    @Test
    @DisplayName("【§13.4】许可压到 0：预约给 4291 与重试提示，平面图照常可读；解除后同一格可约")
    void degradeBlocksOnlyBooking() throws Exception {
        String me = newUser();
        String date = dw(1);
        int start = slot(9);
        long seat = freeSeatOn(floor1, date, start, 2);
        rule("peak.degrade-wait-ms", "0");
        rule("peak.degrade-max-inflight", "0");
        try {
            MvcResult blocked = book(me, seat, date, start, 1);
            String raw = blocked.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(BOOKING_DEGRADED, code(blocked),
                    "过载必须是独立的 4291，而不是 500 也不是 4093：" + raw);
            assertTrue(raw.contains("重试"), "降级要给可执行指令（稍后再试），而不是只说失败：" + raw);

            MvcResult map = exec(get("/api/seats/map")
                    .param("floorId", String.valueOf(floor1))
                    .param("date", date)
                    .param("slot", String.valueOf(start))
                    .header("Authorization", bearer(me)));
            assertEquals(0, code(map),
                    "★ PRD 要的是「优先保证查看余座可用」——预约被打满时读接口必须不受影响："
                            + map.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } finally {
            rule("peak.degrade-max-inflight", "16");
            rule("peak.degrade-wait-ms", "2000");
        }
        assertEquals(0, code(book(me, seat, date, start, 1)),
                "解除降级后同一格应立刻恢复可约（证明刚才拦下它的是开关，不是座位或别的规则）");
    }
}
