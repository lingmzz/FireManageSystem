package com.firemanage;

final class FireClosure {
    private FireClosure() {}

    enum Status { PENDING_RECTIFICATION, PENDING_DEPARTMENT_REVIEW, PENDING_SECTION_REVIEW, IN_WORKFLOW, CLOSED }
    enum Action { REPORT_CLOSE, CONFIRM_CLOSE, FINAL_CLOSE, RETURN }

    static Status next(Status current, Action action, boolean stationIssue) {
        Status target;
        switch (action) {
            case REPORT_CLOSE:
                target = current == Status.PENDING_RECTIFICATION
                        ? (stationIssue ? Status.PENDING_DEPARTMENT_REVIEW : Status.PENDING_SECTION_REVIEW) : null;
                break;
            case CONFIRM_CLOSE:
                target = current == Status.PENDING_DEPARTMENT_REVIEW ? Status.PENDING_SECTION_REVIEW : null;
                break;
            case FINAL_CLOSE:
                target = current == Status.PENDING_SECTION_REVIEW ? Status.CLOSED : null;
                break;
            case RETURN:
                target = current == Status.PENDING_DEPARTMENT_REVIEW || current == Status.PENDING_SECTION_REVIEW
                        ? Status.PENDING_RECTIFICATION : null;
                break;
            default:
                throw new AssertionError(action);
        }
        if (target == null) {
            throw new IllegalStateException("该操作与当前销号状态不符");
        }
        return target;
    }
}
