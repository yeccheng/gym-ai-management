package com.gym.training.service;

import com.gym.training.entity.PlanDeviation;
import com.gym.training.mapper.PlanDeviationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("计划偏差 → 强度调整")
class PlanDeviationServiceTest {

    @Mock
    private PlanDeviationMapper mapper;

    @InjectMocks
    private PlanDeviationService service;

    @Test
    @DisplayName("连续两次状态差，强度系数降到 0.9")
    void 状态差触发降强度() {
        when(mapper.selectRecent(anyLong(), any())).thenReturn(List.of(
                deviation("BAD_FEEL"),
                deviation("BAD_FEEL")));

        PlanDeviationService.IntensityHint hint = service.intensityHint(1L);

        assertEquals(0.9, hint.factor(), 0.001);
        assertTrue(hint.reason().contains("状态差"));
    }

    @Test
    @DisplayName("无偏差时强度系数保持 1.0")
    void 无偏差不降强度() {
        when(mapper.selectRecent(anyLong(), any())).thenReturn(List.of());
        assertEquals(1.0, service.intensityHint(1L).factor(), 0.001);
    }

    @Test
    @DisplayName("记录偏差时类型规范化为大写")
    void 记录偏差() {
        service.record(1L, 10L, 20L, "bad_feel", "今天状态差");
        ArgumentCaptor<PlanDeviation> cap = ArgumentCaptor.forClass(PlanDeviation.class);
        verify(mapper).insert(cap.capture());
        assertEquals("BAD_FEEL", cap.getValue().getType());
    }

    private static PlanDeviation deviation(String type) {
        PlanDeviation d = new PlanDeviation();
        d.setType(type);
        d.setCreateTime(LocalDateTime.now());
        return d;
    }
}
