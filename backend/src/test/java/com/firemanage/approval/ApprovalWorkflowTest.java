package com.firemanage.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.firemanage.approval.ApprovalWorkflow.Action.APPROVE;
import static com.firemanage.approval.ApprovalWorkflow.Action.ASSIGN;
import static com.firemanage.approval.ApprovalWorkflow.Status.CLOSED;
import static com.firemanage.approval.ApprovalWorkflow.Status.PENDING_ASSIGNMENT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalWorkflowTest {
    @ParameterizedTest
    @CsvSource({
        "PENDING_ASSIGNMENT, ASSIGN, PENDING_RECTIFICATION",
        "PENDING_RECTIFICATION, SUBMIT_RECTIFICATION, PENDING_REVIEW",
        "PENDING_REVIEW, RETURN, PENDING_RECTIFICATION",
        "PENDING_REVIEW, APPROVE, CLOSED"
    })
    void advancesToTheExpectedStep(ApprovalWorkflow.Status current, ApprovalWorkflow.Action action,
                                   ApprovalWorkflow.Status expected) {
        assertEquals(expected, ApprovalWorkflow.next(current, action));
    }

    @Test
    void cannotApproveBeforeReview() {
        assertThrows(IllegalStateException.class, () -> ApprovalWorkflow.next(PENDING_ASSIGNMENT, APPROVE));
    }

    @Test
    void cannotProcessAClosedApproval() {
        assertThrows(IllegalStateException.class, () -> ApprovalWorkflow.next(CLOSED, ASSIGN));
    }
}
