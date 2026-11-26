package com.firemanage;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.firemanage.FireClosure.Action.*;
import static com.firemanage.FireClosure.Status.*;
import static com.firemanage.FireReminder.Notice.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FireRequirementsTest {
    private final LocalDate today = LocalDate.of(2026, 11, 26);

    @Test
    void expiryBandUsesTheClosestDeadlineAndIncludesStock() {
        assertEquals(List.of(SIX_MONTHS), FireReminder.forDevice(2, today.plusMonths(6), null, today));
        assertEquals(List.of(THREE_MONTHS), FireReminder.forDevice(2, today.plusMonths(3), null, today));
        assertEquals(List.of(THREE_MONTHS), FireReminder.forDevice(2, today, null, today));
        assertEquals(List.of(EXPIRED), FireReminder.forDevice(2, today.minusDays(1), null, today));
        assertEquals(List.of(), FireReminder.forDevice(2, today.plusMonths(6).plusDays(1), null, today));
    }

    @Test
    void annualInspectionOnlyAppliesToEquipmentInUse() {
        assertEquals(List.of(), FireReminder.forDevice(0, today.minusDays(1), null, today));
        assertEquals(List.of(), FireReminder.forDevice(2, null, null, today));
        assertEquals(List.of(UNINSPECTED), FireReminder.forDevice(1, null, null, today));
        assertEquals(List.of(INSPECTION), FireReminder.forDevice(1, null, today.minusYears(1), today));
        assertEquals(List.of(EXPIRED), FireReminder.forDevice(1, today.minusDays(1), today, today));
    }

    @Test
    void stationIssueRequiresDepartmentConfirmationBeforeFinalClosure() {
        assertEquals(PENDING_DEPARTMENT_REVIEW, FireClosure.next(PENDING_RECTIFICATION, REPORT_CLOSE, true));
        assertThrows(IllegalStateException.class, () -> FireClosure.next(PENDING_DEPARTMENT_REVIEW, FINAL_CLOSE, true));
        assertEquals(PENDING_SECTION_REVIEW, FireClosure.next(PENDING_DEPARTMENT_REVIEW, CONFIRM_CLOSE, true));
        assertEquals(CLOSED, FireClosure.next(PENDING_SECTION_REVIEW, FINAL_CLOSE, true));
    }

    @Test
    void departmentIssueGoesToTheSectionAndCanBeReturned() {
        assertEquals(PENDING_SECTION_REVIEW, FireClosure.next(PENDING_RECTIFICATION, REPORT_CLOSE, false));
        assertEquals(PENDING_RECTIFICATION, FireClosure.next(PENDING_SECTION_REVIEW, RETURN, false));
        assertThrows(IllegalStateException.class, () -> FireClosure.next(CLOSED, RETURN, false));
    }
}
