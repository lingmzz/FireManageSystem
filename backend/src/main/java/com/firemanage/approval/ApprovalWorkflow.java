package com.firemanage.approval;

final class ApprovalWorkflow {
    enum Status {
        PENDING_ASSIGNMENT, PENDING_RECTIFICATION, PENDING_REVIEW, IN_WORKFLOW, CLOSED
    }

    enum Action {
        ASSIGN(Status.PENDING_ASSIGNMENT, Status.PENDING_RECTIFICATION),
        SUBMIT_RECTIFICATION(Status.PENDING_RECTIFICATION, Status.PENDING_REVIEW),
        APPROVE(Status.PENDING_REVIEW, Status.CLOSED),
        RETURN(Status.PENDING_REVIEW, Status.PENDING_RECTIFICATION);

        private final Status from;
        private final Status to;

        Action(Status from, Status to) {
            this.from = from;
            this.to = to;
        }
    }

    private ApprovalWorkflow() {}

    static Status next(Status current, Action action) {
        if (action.from != current) {
            throw new IllegalStateException("该操作与当前审批状态不符");
        }
        return action.to;
    }
}
