package com.firemanage;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalFlowTest {
    @Test
    void existingRoutesCanBePublishedAsExamples() {
        Map<String, Long> roles = new HashMap<>();
        roles.put("DISPATCHER", 1L);
        roles.put("RECTIFIER", 2L);
        roles.put("REVIEWER", 3L);
        roles.put("FIRE_STATION", 4L);
        roles.put("FIRE_DEPARTMENT", 5L);
        roles.put("FIRE_SEGMENT", 6L);
        for (ApprovalFlow.Type type : ApprovalFlow.Type.values()) {
            assertDoesNotThrow(() -> ApprovalFlow.validate(type, ApprovalFlow.example(type, roles)));
        }
    }

    @Test
    void rejectsAPassCycleAtTheStepThatClosesIt() {
        ApprovalFlow.Draft draft = new ApprovalFlow.Draft("first", Collections.unmodifiableList(Arrays.asList(step("first", "second", null), step("second", "first", null))));
        assertField("step.second.passTo", draft);
    }

    @Test
    void rejectsStepsSkippedByThePassPath() {
        ApprovalFlow.Draft draft = new ApprovalFlow.Draft("first", Collections.unmodifiableList(Arrays.asList(step("first", null, null), step("second", null, "first"))));
        assertField("step.second.passTo", draft);
    }

    @Test
    void returnMustReopenAnEarlierStepInTheActualPassOrder() {
        ApprovalFlow.Draft draft = new ApprovalFlow.Draft("second", Collections.unmodifiableList(Arrays.asList(step("first", null, "second"), step("second", "first", null))));
        assertDoesNotThrow(() -> ApprovalFlow.validate(ApprovalFlow.Type.WORKBENCH, draft));
        ApprovalFlow.Draft forward = new ApprovalFlow.Draft("second", Collections.unmodifiableList(Arrays.asList(step("first", null, null), step("second", "first", "first"))));
        assertField("step.second.returnTo", forward);
    }

    private static ApprovalFlow.Step step(String key, String next, String back) {
        return new ApprovalFlow.Step(key, key, 1L, ApprovalFlow.UnitScope.NONE, false, "通过", false, next, back);
    }

    private static void assertField(String field, ApprovalFlow.Draft draft) {
        ResponseStatusException failure = assertThrows(ResponseStatusException.class,
                () -> ApprovalFlow.validate(ApprovalFlow.Type.WORKBENCH, draft));
        ResponseEntity<Map<String, Object>> error = new ApiErrors().status(failure);
        assertEquals(400, error.getStatusCodeValue());
        assertTrue(((Map<?, ?>) error.getBody().get("fieldErrors")).containsKey(field));
    }
}
