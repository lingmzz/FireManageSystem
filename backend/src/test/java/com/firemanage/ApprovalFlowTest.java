package com.firemanage;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalFlowTest {
    @Test
    void existingRoutesCanBePublishedAsExamples() {
        var roles = Map.of("DISPATCHER", 1L, "RECTIFIER", 2L, "REVIEWER", 3L,
                "FIRE_STATION", 4L, "FIRE_DEPARTMENT", 5L, "FIRE_SEGMENT", 6L);
        for (var type : ApprovalFlow.Type.values()) {
            assertDoesNotThrow(() -> ApprovalFlow.validate(type, ApprovalFlow.example(type, roles)));
        }
    }

    @Test
    void rejectsAPassCycleAtTheStepThatClosesIt() {
        var draft = new ApprovalFlow.Draft("first", List.of(step("first", "second", null), step("second", "first", null)));
        assertField("step.second.passTo", draft);
    }

    @Test
    void rejectsStepsSkippedByThePassPath() {
        var draft = new ApprovalFlow.Draft("first", List.of(step("first", null, null), step("second", null, "first")));
        assertField("step.second.passTo", draft);
    }

    @Test
    void returnMustReopenAnEarlierStepInTheActualPassOrder() {
        var draft = new ApprovalFlow.Draft("second", List.of(step("first", null, "second"), step("second", "first", null)));
        assertDoesNotThrow(() -> ApprovalFlow.validate(ApprovalFlow.Type.WORKBENCH, draft));
        var forward = new ApprovalFlow.Draft("second", List.of(step("first", null, null), step("second", "first", "first")));
        assertField("step.second.returnTo", forward);
    }

    private static ApprovalFlow.Step step(String key, String next, String back) {
        return new ApprovalFlow.Step(key, key, 1L, ApprovalFlow.UnitScope.NONE, false, "通过", false, next, back);
    }

    private static void assertField(String field, ApprovalFlow.Draft draft) {
        var failure = assertThrows(ResponseStatusException.class, () -> ApprovalFlow.validate(ApprovalFlow.Type.WORKBENCH, draft));
        assertEquals(400, failure.getStatusCode().value());
        assertTrue(((Map<?, ?>) failure.getBody().getProperties().get("fieldErrors")).containsKey(field));
    }
}
