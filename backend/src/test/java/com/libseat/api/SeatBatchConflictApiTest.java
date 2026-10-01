package com.libseat.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.libseat.repository.SeatRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * V2 S1 / T-V2-06：批量生成的坐标冲突修复。
 *
 * <p>V1 只校验 {@code seat_no} 唯一，于是「换个前缀再批量一次」会把新座位叠在已有座位上
 * （回归实测 A01 / Z01 / Z488 同处 1 行 1 列）。本用例锁死的正是这条被漏掉的校验。
 */
class SeatBatchConflictApiTest extends ApiTestBase {

    @Autowired
    private SeatRepository seatRepository;

    private Map<String, Object> batchRequest(Long floorId, String prefix, int startNo, int rows, int cols) {
        Map<String, Object> req = new HashMap<>();
        req.put("floorId", floorId);
        req.put("prefix", prefix);
        req.put("startNo", startNo);
        req.put("rows", rows);
        req.put("cols", cols);
        req.put("status", "AVAILABLE");
        return req;
    }

    private MvcResult postBatch(String path, Map<String, Object> body) throws Exception {
        return exec(post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body))
                .header("Authorization", bearer(admin)));
    }

    @Test
    @DisplayName("【验收1】换个前缀批量生成到已占用的格子上 → 全部按坐标跳过，不叠座")
    void positionConflictSkipped() throws Exception {
        long before = seatRepository.count();
        // 1F 的 1 行 1~3 列已被 A01~A03 占用；Z 前缀保证编号不冲突，只有坐标冲突
        MvcResult result = postBatch("/api/admin/seats/batch", batchRequest(floor1, "Z", 901, 1, 3));
        assertEquals(0, code(result), "批量接口应正常返回：" + body(result));
        JsonNode data = data(result);

        assertEquals(0, data.path("created").asInt(),
                "★ created 必须为 0：换前缀绕开编号校验后，坐标校验必须兜住");
        assertEquals(3, data.path("skipped").asInt(), "3 个格子全部冲突");
        List<String> positions = new ArrayList<>();
        data.path("skippedPositions").forEach(node -> positions.add(node.asText()));
        assertEquals(List.of("(1,1)", "(1,2)", "(1,3)"), positions,
                "应逐个列出冲突坐标，否则管理员看不出为什么没生成");
        assertEquals(before, seatRepository.count(), "不得写入任何座位");
    }

    @Test
    @DisplayName("【预览】batch-preview 与正式生成判定一致，且预览不落库")
    void previewMatchesRealRun() throws Exception {
        long before = seatRepository.count();
        Map<String, Object> req = batchRequest(floor1, "P", 801, 2, 2);
        JsonNode preview = data(postBatch("/api/admin/seats/batch-preview", req));

        assertEquals(Boolean.TRUE, preview.path("previewOnly").asBoolean(), "预览必须标记 previewOnly");
        assertEquals(before, seatRepository.count(), "预览不得写库");

        JsonNode real = data(postBatch("/api/admin/seats/batch", req));
        assertEquals(preview.path("created").asInt(), real.path("created").asInt(),
                "预览与正式生成的可生成数必须一致，否则预览没有意义");
        assertEquals(preview.path("skipped").asInt(), real.path("skipped").asInt());
        assertEquals(preview.path("skippedPositions").toString(), real.path("skippedPositions").toString());
    }

    @Test
    @DisplayName("【空位】指定起始行后在未占用行上正常生成，并归属所选分区")
    void createsOnFreeRows() throws Exception {
        Long zoneId = zoneIdOf(floor1, "1F-LOUNGE");
        // 1F 种子占满 1~6 行，第 8 行整行空闲
        Map<String, Object> req = batchRequest(floor1, "R", 601, 1, 2);
        req.put("rowFrom", 8);
        req.put("colFrom", 1);
        req.put("zoneId", zoneId);

        JsonNode first = data(postBatch("/api/admin/seats/batch", req));
        assertEquals(2, first.path("created").asInt(),
                "★ 空行上应生成 2 座；若为 0 说明坐标起点没生效：" + first);
        assertEquals(0, first.path("skipped").asInt());

        JsonNode sameNames = data(postBatch("/api/admin/seats/batch", req));
        assertEquals(0, sameNames.path("created").asInt(), "重复执行应一颗不落地跳过");
        assertEquals(2, sameNames.path("skipped").asInt());

        // ★ 关键一步：换前缀绕开编号校验，同坐标仍必须被拦 —— 这正是 V1 漏掉的那条路径
        Map<String, Object> renamed = batchRequest(floor1, "S", 601, 1, 2);
        renamed.put("rowFrom", 8);
        renamed.put("colFrom", 1);
        renamed.put("zoneId", zoneId);
        JsonNode second = data(postBatch("/api/admin/seats/batch", renamed));
        assertEquals(0, second.path("created").asInt(),
                "★ 换个前缀就绕过编号校验后，坐标校验必须兜住；若 created=2 说明又能叠座了");
        List<String> positions = new ArrayList<>();
        second.path("skippedPositions").forEach(node -> positions.add(node.asText()));
        assertEquals(List.of("(8,1)", "(8,2)"), positions, "跳过原因要落到坐标上才可排查");

        JsonNode adminZones = data(exec(get("/api/zones").param("floorId", String.valueOf(floor1))
                .header("Authorization", bearer(admin))));
        JsonNode lounge = null;
        for (JsonNode node : adminZones) {
            if (zoneId.equals(node.path("zoneId").asLong())) {
                lounge = node;
            }
        }
        assertTrue(lounge != null && lounge.path("totalSeats").asLong() >= 10,
                "休闲区应吸收了新生成的座位，实际 "
                        + (lounge == null ? "分区未找到" : lounge.path("totalSeats").asLong()));
    }

    @Test
    @DisplayName("【无孤儿】批量生成的座位也必须有 zone_id")
    void batchSeatsGetZoneId() throws Exception {
        assertEquals(0L, seatRepository.countByZoneIdIsNull(),
                "★ 任何路径写入的座位都要有分区归属，否则分区规则对它无效");
    }

    private Long zoneIdOf(Long floorId, String code) throws Exception {
        JsonNode list = data(exec(get("/api/zones").param("floorId", String.valueOf(floorId))
                .header("Authorization", bearer(admin))));
        for (JsonNode node : list) {
            if (code.equals(node.path("code").asText())) {
                return node.path("zoneId").asLong();
            }
        }
        throw new AssertionError("未找到分区 " + code);
    }
}
