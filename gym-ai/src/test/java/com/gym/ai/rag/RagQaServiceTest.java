package com.gym.ai.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引用校验的验证。这是四层防幻觉里的 L3——
 * 前两层只是请求模型配合，这一层是「不配合也有兜底」。
 */
class RagQaServiceTest {

    private final RagQaService service =
            new RagQaService(null, null, new ObjectMapper());

    @Test
    @DisplayName("引用编号全部在召回集内时通过")
    void validCitations() {
        List<String> recalled = List.of("动作-1", "动作-2", "动作-3");
        assertTrue(service.citationsValid(List.of("动作-1", "动作-3"), recalled));
    }

    @Test
    @DisplayName("引用了召回集之外的编号必须拒绝")
    void outOfSetCitationRejected() {
        List<String> recalled = List.of("动作-1", "动作-2");
        assertFalse(service.citationsValid(List.of("动作-1", "动作-99"), recalled));
    }

    @Test
    @DisplayName("空引用视为通过：保守回答好过错误引用")
    void emptyCitationAllowed() {
        assertTrue(service.citationsValid(List.of(), List.of("动作-1")));
        assertTrue(service.citationsValid(null, List.of("动作-1")));
    }

    @Test
    @DisplayName("召回集为空且模型给了引用时必须拒绝")
    void emptyRecallWithCitationRejected() {
        assertFalse(service.citationsValid(List.of("动作-1"), List.of()));
    }
}
