package com.libseat.unit;

import com.libseat.common.BizConstants;
import com.libseat.common.BusinessException;
import com.libseat.common.RuleKeys;
import com.libseat.entity.RuleConfig;
import com.libseat.repository.RuleConfigRepository;
import com.libseat.service.RuleConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规则配置中心的兜底与校验单测。
 *
 * <p>重点验证两件事：① 配置缺失或值非法时必须回落到 BizConstants 默认值而不是抛异常
 * （配置写错不该让预约链路 500）；② 管理端写入必须被类型校验拦住且不落库。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleConfigServiceTest {

    @Mock
    private RuleConfigRepository repository;

    private RuleConfigService service;

    private RuleConfig rule(String key, String value, String type) {
        return RuleConfig.builder()
                .scope("GLOBAL").scopeId("").rkey(key).rvalue(value)
                .valueType(type).rgroup("BOOK").editable(true)
                .build();
    }

    @BeforeEach
    void setUp() {
        service = new RuleConfigService(repository);
    }

    @Test
    @DisplayName("配置表为空 → 全部回落 BizConstants 默认值")
    void emptyConfigFallsBackToConstants() {
        when(repository.findAllByOrderByScopeAscScopeIdAscRkeyAsc()).thenReturn(List.of());
        service.refresh();

        assertEquals(BizConstants.CHECK_IN_BEFORE_MIN, service.checkInBeforeMin());
        assertEquals(BizConstants.CHECK_IN_AFTER_MIN, service.checkInAfterMin());
        assertEquals(BizConstants.ADVANCE_DAYS, service.advanceDays());
        assertEquals(BizConstants.DEFAULT_DURATION, service.defaultDuration());
        assertEquals(BizConstants.ALLOWED_DURATIONS, service.allowedDurations());
    }

    @Test
    @DisplayName("值非法（非整数 / 列表含脏项）→ 回落兜底，不抛异常")
    void malformedValuesFallBack() {
        when(repository.findAllByOrderByScopeAscScopeIdAscRkeyAsc()).thenReturn(List.of(
                rule(RuleKeys.BOOK_ADVANCE_DAYS, "abc", "INT"),
                rule(RuleKeys.BOOK_ALLOWED_DURATIONS, "1,two,3", "INT_LIST"),
                rule(RuleKeys.BOOK_CHECK_IN_BEFORE_MIN, "", "INT")));
        service.refresh();

        assertEquals(BizConstants.ADVANCE_DAYS, service.advanceDays());
        assertEquals(BizConstants.ALLOWED_DURATIONS, service.allowedDurations());
        assertEquals(BizConstants.CHECK_IN_BEFORE_MIN, service.checkInBeforeMin());
    }

    @Test
    @DisplayName("正常配置被读取；布尔与整数列表解析正确")
    void validValuesAreRead() {
        when(repository.findAllByOrderByScopeAscScopeIdAscRkeyAsc()).thenReturn(List.of(
                rule(RuleKeys.BOOK_ADVANCE_DAYS, "3", "INT"),
                rule(RuleKeys.BOOK_ALLOWED_DURATIONS, "1, 2 ,4", "INT_LIST")));
        service.refresh();

        assertEquals(3, service.advanceDays());
        assertEquals(List.of(1, 2, 4), service.allowedDurations());
        assertTrue(service.getBool("never.set", true));
    }

    @Test
    @DisplayName("未知键与类型不符都必须 4001 且不落库")
    void updateRejectsUnknownKeyAndBadType() {
        when(repository.findByScopeAndScopeIdAndRkey("GLOBAL", "", RuleKeys.BOOK_ADVANCE_DAYS))
                .thenReturn(Optional.of(rule(RuleKeys.BOOK_ADVANCE_DAYS, "7", "INT")));

        assertThrows(BusinessException.class,
                () -> service.update("GLOBAL", "", "not.exists", "1", 1L));
        assertThrows(BusinessException.class,
                () -> service.update("GLOBAL", "", RuleKeys.BOOK_ADVANCE_DAYS, "abc", 1L));
        assertThrows(BusinessException.class,
                () -> service.update("GLOBAL", "", RuleKeys.BOOK_ADVANCE_DAYS, "  ", 1L));
        verify(repository, never()).save(any());
    }
}
