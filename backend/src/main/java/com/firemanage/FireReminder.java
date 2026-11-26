package com.firemanage;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class FireReminder {
    private FireReminder() {}

    enum Notice {
        SIX_MONTHS(2, "6个月内到期"),
        THREE_MONTHS(3, "3个月内到期"),
        EXPIRED(9, "已过期"),
        INSPECTION(5, "年检提示"),
        UNINSPECTED(6, "待年检");

        final int code;
        final String label;

        Notice(int code, String label) {
            this.code = code;
            this.label = label;
        }
    }

    static List<Notice> forDevice(int state, LocalDate expiry, LocalDate inspection, LocalDate today) {
        if (state == 0) {
            return List.of();
        }
        var notices = new ArrayList<Notice>(2);
        if (expiry != null) {
            if (expiry.isBefore(today)) {
                notices.add(Notice.EXPIRED);
            } else if (!expiry.isAfter(today.plusMonths(3))) {
                notices.add(Notice.THREE_MONTHS);
            } else if (!expiry.isAfter(today.plusMonths(6))) {
                notices.add(Notice.SIX_MONTHS);
            }
        }
        if (state == 1) {
            if (inspection == null) {
                notices.add(Notice.UNINSPECTED);
            } else if (inspection.getYear() != today.getYear()) {
                notices.add(Notice.INSPECTION);
            }
        }
        return notices;
    }
}
