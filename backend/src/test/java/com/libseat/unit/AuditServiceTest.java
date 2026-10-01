package com.libseat.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.libseat.repository.AuditLogRepository;
import com.libseat.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 审计服务的容错与文案单测。
 *
 * <p>核心契约：**审计写失败绝不能把已经成功的业务操作回滚掉** ——
 * 治理日志不该有否决业务的权力（V1 曾因事务边界问题留下永久占用，此处反向同理）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditServiceTest {

    @Mock
    private AuditLogRepository repository;

    @Test
    @DisplayName("仓储抛异常时 record 只记日志，不向上抛出")
    void recordSwallowsRepositoryFailure() {
        AuditService service = new AuditService(repository, new ObjectMapper());
        doThrow(new RuntimeException("模拟数据库故障")).when(repository).save(any());

        assertDoesNotThrow(() -> service.record(
                AuditService.ACTION_RULE_UPDATE, "RULE", 1L, Map.of("k", "v")));
    }

    @Test
    @DisplayName("明细序列化失败也不抛出；序列化内容含旧值新值")
    void detailSerializationIsSafe() {
        AuditService service = new AuditService(repository, new ObjectMapper());
        assertDoesNotThrow(() -> service.record(
                AuditService.ACTION_SEAT_DELETE, "SEAT", 7L, "seatNo", "A07", "floorId", 1L));
    }

    @Test
    @DisplayName("动作码文案：已知取映射，未知回落原码")
    void actionTextMapping() {
        assertEquals("修改规则配置", AuditService.actionText(AuditService.ACTION_RULE_UPDATE));
        assertEquals("强制释放预约", AuditService.actionText(AuditService.ACTION_RESERVATION_RELEASE));
        assertEquals("SOMETHING_FUTURE", AuditService.actionText("SOMETHING_FUTURE"));
    }

    @Test
    @DisplayName("序列化器异常时降级为 toString 而非抛出")
    void toJsonFallsBackToString() {
        ObjectMapper broken = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) {
                throw new IllegalStateException("模拟序列化故障");
            }
        };
        AuditService service = new AuditService(repository, broken);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> service.record(
                AuditService.ACTION_PASSWORD_CHANGE, "USER", 2L, Map.of("a", 1)));
    }
}
