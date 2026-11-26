package com.firemanage;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fire/dashboard")
class FireDashboardController {
    private static final Map<String, String> DEVICE_OP_LABELS = deviceOpLabels();
    private static final Map<String, String> HAZARD_ACTION_LABELS = hazardActionLabels();

    private final JdbcClient jdbc;
    private final FireAccess fire;

    FireDashboardController(JdbcClient jdbc, FireAccess fire) {
        this.jdbc = jdbc;
        this.fire = fire;
    }

    @GetMapping
    @Transactional(readOnly = true)
    Dashboard overview(Authentication auth, @RequestParam(required = false) Long unitId) {
        FireAccess.Scope scope = fire.scope(auth);
        scope.actor().require(Permission.FIRE_DASHBOARD);

        LocalDate today = LocalDate.now(FireDeviceController.LOCAL_ZONE);
        LocalDateTime now = LocalDateTime.now(FireDeviceController.LOCAL_ZONE);
        List<FireAccess.Unit> visibleUnits = scope.visibleUnits();
        List<Long> scopedUnitIds = scope.visibleIds(unitId);
        Set<Long> scopedSet = new HashSet<>(scopedUnitIds);
        List<FireAccess.Unit> scopedUnits = visibleUnits.stream()
                .filter(u -> scopedSet.contains(u.id()))
                .collect(Collectors.toList());

        Map<Long, FireAccess.Unit> unitMap = scope.directory();
        int departmentCount = (int) scopedUnits.stream().filter(u -> u.kind() == FireAccess.Kind.DEPARTMENT).count();
        int stationCount = (int) scopedUnits.stream().filter(u -> u.kind() == FireAccess.Kind.STATION).count();

        JdbcClient.IdList unitsFilter = new JdbcClient.IdList(scopedUnitIds);

        boolean canDevices = scope.actor().has(Permission.FIRE_DEVICE_READ);
        boolean canHazards = scope.actor().has(Permission.FIRE_HAZARD_READ);
        boolean canDrills = scope.actor().has(Permission.FIRE_DRILL_READ);
        boolean canMaintainers = scope.actor().has(Permission.FIRE_MAINT_READ);

        Map<Long, UnitCounter> unitCounters = new LinkedHashMap<>();
        for (FireAccess.Unit u : scopedUnits) {
            unitCounters.put(u.id(), new UnitCounter(u, u.parentId() == null ? null : unitMap.get(u.parentId())));
        }

        // 1. Devices & Reminders
        int deviceTotal = 0;
        int deviceInUse = 0;
        int deviceStock = 0;
        int deviceScrapped = 0;
        int expiredCount = 0;
        int within3MonthsCount = 0;
        int within6MonthsCount = 0;
        int uninspectedCount = 0;
        int inspectionDueCount = 0;
        int healthyDevices = 0;

        EnumMap<FireDeviceController.Category, Integer> categoryCounts = new EnumMap<>(FireDeviceController.Category.class);
        for (FireDeviceController.Category c : FireDeviceController.Category.values()) {
            categoryCounts.put(c, 0);
        }

        List<UrgentDevice> urgentDevices = new ArrayList<>();
        if (canDevices) {
            List<DeviceRow> devices = jdbc.sql("SELECT d.id, d.unit_id, u.name AS unit_name, d.code, d.category, d.item_name,\n"
                    + "       d.use_site, d.storage_location, d.inspected_on, d.expires_on, d.xfqc_bs AS state, d.created_at\n"
                    + "FROM fm_fire_device d JOIN fm_fire_unit u ON u.id = d.unit_id\n"
                    + "WHERE d.deleted = 0 AND " + unitsFilter.matches("d.unit_id") + "\n")
                    .params(unitsFilter.parameters()).query(DeviceRow.class).list();

            for (DeviceRow row : devices) {
                if (row.state() == FireDeviceController.SCRAPPED) {
                    deviceScrapped++;
                    continue;
                }
                deviceTotal++;
                if (row.state() == FireDeviceController.IN_USE) deviceInUse++;
                else if (row.state() == FireDeviceController.STOCK) deviceStock++;

                categoryCounts.put(row.category(), categoryCounts.get(row.category()) + 1);
                List<FireReminder.Notice> notices = FireReminder.forDevice(row.state(), row.expiresOn(), row.inspectedOn(), today);

                boolean hasUrgent = false;
                boolean hasHealthIssue = false;
                UnitCounter counter = unitCounters.get(row.unitId());
                if (counter != null) counter.deviceTotal++;

                for (FireReminder.Notice n : notices) {
                    switch (n) {
                        case EXPIRED:
                            expiredCount++;
                            hasUrgent = true;
                            hasHealthIssue = true;
                            if (counter != null) counter.expiredCount++;
                            break;
                        case THREE_MONTHS:
                            within3MonthsCount++;
                            hasUrgent = true;
                            if (counter != null) counter.expiringCount++;
                            break;
                        case SIX_MONTHS:
                            within6MonthsCount++;
                            break;
                        case UNINSPECTED:
                            uninspectedCount++;
                            hasUrgent = true;
                            hasHealthIssue = true;
                            if (counter != null) counter.inspectionAlertCount++;
                            break;
                        case INSPECTION:
                            inspectionDueCount++;
                            hasUrgent = true;
                            hasHealthIssue = true;
                            if (counter != null) counter.inspectionAlertCount++;
                            break;
                    }
                }
                if (!hasHealthIssue) {
                    healthyDevices++;
                }
                if (hasUrgent) {
                    Long daysRemaining = row.expiresOn() == null ? null : ChronoUnit.DAYS.between(today, row.expiresOn());
                    urgentDevices.add(new UrgentDevice(
                            row.id(), row.unitId(), row.unitName(), row.code(), row.category().name(),
                            row.itemName(), row.useSite(), row.storageLocation(),
                            row.expiresOn(), row.inspectedOn(),
                            notices.stream().map(n -> n.code).collect(Collectors.toList()),
                            daysRemaining, urgencyRank(notices)
                    ));
                }
            }
            urgentDevices.sort(Comparator.comparingInt(UrgentDevice::urgencyRank)
                    .thenComparing(d -> d.daysRemaining() == null ? Long.MAX_VALUE : d.daysRemaining())
                    .thenComparingLong(UrgentDevice::id));
            if (urgentDevices.size() > 6) {
                urgentDevices = new ArrayList<>(urgentDevices.subList(0, 6));
            }
        }

        double healthRate = deviceTotal == 0 ? 100.0 : Math.round((healthyDevices * 1000.0) / deviceTotal) / 10.0;
        List<CategoryStat> categories = new ArrayList<>();
        for (FireDeviceController.Category c : FireDeviceController.Category.values()) {
            int count = categoryCounts.get(c);
            double ratio = deviceTotal == 0 ? 0.0 : Math.round((count * 1000.0) / deviceTotal) / 10.0;
            categories.add(new CategoryStat(c.name(), c.label, count, ratio));
        }

        // 2. Hazards & Closure Funnel
        int hazardTotal = 0;
        int hazardOpen = 0;
        int hazardClosed = 0;
        int hazardPendingRect = 0;
        int hazardPendingDept = 0;
        int hazardPendingSect = 0;
        int hazardInWorkflow = 0;

        List<YearMonth> lastSixMonths = lastSixMonths(today);
        Map<YearMonth, Integer> monthlyHazardsCreated = new LinkedHashMap<>();
        Map<YearMonth, Integer> monthlyHazardsClosed = new LinkedHashMap<>();
        Map<YearMonth, Integer> monthlyDrills = new LinkedHashMap<>();
        for (YearMonth ym : lastSixMonths) {
            monthlyHazardsCreated.put(ym, 0);
            monthlyHazardsClosed.put(ym, 0);
            monthlyDrills.put(ym, 0);
        }

        if (canHazards) {
            List<HazardRow> hazards = jdbc.sql("SELECT a.id, au.unit_id, a.status, a.created_at, a.updated_at\n"
                    + "FROM fm_approval a JOIN fm_fire_approval_unit au ON au.approval_id = a.id\n"
                    + "WHERE " + unitsFilter.matches("au.unit_id") + "\n")
                    .params(unitsFilter.parameters()).query(HazardRow.class).list();

            for (HazardRow row : hazards) {
                hazardTotal++;
                if (row.createdAt() != null) {
                    YearMonth createdMonth = YearMonth.from(row.createdAt());
                    if (monthlyHazardsCreated.containsKey(createdMonth)) {
                        monthlyHazardsCreated.put(createdMonth, monthlyHazardsCreated.get(createdMonth) + 1);
                    }
                }
                if (row.status() == FireClosure.Status.CLOSED) {
                    hazardClosed++;
                    if (row.updatedAt() != null) {
                        YearMonth closedMonth = YearMonth.from(row.updatedAt());
                        if (monthlyHazardsClosed.containsKey(closedMonth)) {
                            monthlyHazardsClosed.put(closedMonth, monthlyHazardsClosed.get(closedMonth) + 1);
                        }
                    }
                } else {
                    hazardOpen++;
                    UnitCounter counter = unitCounters.get(row.unitId());
                    if (counter != null) counter.openHazardCount++;
                    switch (row.status()) {
                        case PENDING_RECTIFICATION:
                            hazardPendingRect++;
                            break;
                        case PENDING_DEPARTMENT_REVIEW:
                            hazardPendingDept++;
                            break;
                        case PENDING_SECTION_REVIEW:
                            hazardPendingSect++;
                            break;
                        case IN_WORKFLOW:
                            hazardInWorkflow++;
                            break;
                        case CLOSED:
                            break;
                    }
                }
            }
        }
        double closureRate = hazardTotal == 0 ? 100.0 : Math.round((hazardClosed * 1000.0) / hazardTotal) / 10.0;

        // 3. Drills
        int drillTotalYear = 0;
        int drillParticipantsYear = 0;
        Set<Long> drilledUnitsYear = new HashSet<>();
        if (canDrills) {
            LocalDateTime yearStart = today.withDayOfYear(1).atStartOfDay();
            List<DrillRow> drills = jdbc.sql("SELECT d.id, d.unit_id, d.title, d.drilled_at, d.participant_count\n"
                    + "FROM fm_fire_drill d WHERE " + unitsFilter.matches("d.unit_id") + "\n")
                    .params(unitsFilter.parameters()).query(DrillRow.class).list();

            for (DrillRow row : drills) {
                UnitCounter counter = unitCounters.get(row.unitId());
                if (counter != null && row.drilledAt() != null
                        && (counter.latestDrillAt == null || row.drilledAt().isAfter(counter.latestDrillAt))) {
                    counter.latestDrillAt = row.drilledAt();
                }
                if (row.drilledAt() != null) {
                    YearMonth ym = YearMonth.from(row.drilledAt());
                    if (monthlyDrills.containsKey(ym)) {
                        monthlyDrills.put(ym, monthlyDrills.get(ym) + 1);
                    }
                    if (!row.drilledAt().isBefore(yearStart)) {
                        drillTotalYear++;
                        drillParticipantsYear += row.participantCount();
                        drilledUnitsYear.add(row.unitId());
                    }
                }
            }
        }
        int drillTargetUnits = stationCount > 0 ? stationCount : scopedUnits.size();
        int drillCoveredUnits = stationCount > 0
                ? (int) scopedUnits.stream().filter(u -> u.kind() == FireAccess.Kind.STATION && drilledUnitsYear.contains(u.id())).count()
                : drilledUnitsYear.size();

        // 4. Maintainers
        List<MaintainerCard> maintainers = new ArrayList<>();
        int maintainerCount = 0;
        if (canMaintainers) {
            List<MaintainerRow> rows = jdbc.sql("SELECT DISTINCT m.id, m.name, m.contact, m.phone\n"
                    + "FROM fm_fire_maintainer m JOIN fm_fire_maintainer_unit mu ON mu.maintainer_id = m.id\n"
                    + "WHERE m.deleted = 0 AND " + unitsFilter.matches("mu.unit_id") + "\n"
                    + "ORDER BY m.name, m.id\n")
                    .params(unitsFilter.parameters()).query(MaintainerRow.class).list();
            maintainerCount = rows.size();
            if (!rows.isEmpty()) {
                Map<Long, Integer> mUnits = new HashMap<>();
                jdbc.sql("SELECT mu.maintainer_id AS id, COUNT(*) AS cnt FROM fm_fire_maintainer_unit mu\n"
                        + "WHERE " + unitsFilter.matches("mu.unit_id") + "\n"
                        + "GROUP BY mu.maintainer_id\n")
                        .params(unitsFilter.parameters())
                        .query(IdCount.class).list().forEach(r -> mUnits.put(r.id(), r.cnt()));

                Map<Long, Integer> mDevices = new HashMap<>();
                jdbc.sql("SELECT md.maintainer_id AS id, COUNT(*) AS cnt FROM fm_fire_maintainer_device md\n"
                        + "JOIN fm_fire_device d ON d.id = md.device_id AND d.deleted = 0\n"
                        + "WHERE " + unitsFilter.matches("d.unit_id") + "\n"
                        + "GROUP BY md.maintainer_id\n")
                        .params(unitsFilter.parameters())
                        .query(IdCount.class).list().forEach(r -> mDevices.put(r.id(), r.cnt()));

                for (MaintainerRow row : rows) {
                    maintainers.add(new MaintainerCard(row.id(), row.name(), row.contact(), row.phone(),
                            mUnits.getOrDefault(row.id(), 0), mDevices.getOrDefault(row.id(), 0)));
                }
                maintainers.sort(Comparator.comparingInt(MaintainerCard::deviceCount).reversed()
                        .thenComparingInt(MaintainerCard::unitCount).reversed()
                        .thenComparing(MaintainerCard::name));
                if (maintainers.size() > 4) {
                    maintainers = new ArrayList<>(maintainers.subList(0, 4));
                }
            }
        }

        // 5. Unit Status Matrix (prefer stations when present, otherwise all scoped units)
        List<UnitStatus> unitStatuses = unitCounters.values().stream()
                .filter(c -> stationCount == 0 || c.unit.kind() == FireAccess.Kind.STATION || c.deviceTotal > 0 || c.openHazardCount > 0)
                .map(UnitCounter::toStatus)
                .sorted(Comparator.comparingInt(UnitStatus::severityOrder)
                        .thenComparing(Comparator.comparingInt(UnitStatus::expiredCount).reversed())
                        .thenComparing(Comparator.comparingInt(UnitStatus::openHazardCount).reversed())
                        .thenComparing(UnitStatus::name))
                .collect(Collectors.toList());

        // 6. Recent Activity Stream (Devices + Hazards + Drills)
        List<ActivityItem> activities = loadRecentActivities(unitsFilter, canDevices, canHazards, canDrills);

        // 7. Monthly Trend Points
        List<MonthlyPoint> trends = new ArrayList<>(lastSixMonths.size());
        for (YearMonth ym : lastSixMonths) {
            trends.add(new MonthlyPoint(ym.getMonthValue() + "月",
                    monthlyHazardsCreated.get(ym),
                    monthlyHazardsClosed.get(ym),
                    monthlyDrills.get(ym)));
        }

        Summary summary = new Summary(
                deviceTotal, deviceInUse, deviceStock, deviceScrapped, healthRate,
                expiredCount, within3MonthsCount, within6MonthsCount, uninspectedCount, inspectionDueCount,
                hazardTotal, hazardOpen, hazardClosed, closureRate,
                hazardPendingRect, hazardPendingDept, hazardPendingSect, hazardInWorkflow,
                drillTotalYear, drillParticipantsYear, drillCoveredUnits, drillTargetUnits,
                maintainerCount, departmentCount, stationCount, scopedUnits.size()
        );

        return new Dashboard(
                now, unitId, visibleUnits, summary, categories, urgentDevices,
                unitStatuses, activities, maintainers, trends,
                new Capabilities(canDevices, canHazards, canDrills, canMaintainers,
                        scope.actor().has(Permission.FIRE_HAZARD_ADD),
                        scope.actor().has(Permission.FIRE_DEVICE_ADD),
                        scope.actor().has(Permission.FIRE_DRILL_WRITE))
        );
    }

    private List<ActivityItem> loadRecentActivities(JdbcClient.IdList unitsFilter,
                                                    boolean canDevices, boolean canHazards, boolean canDrills) {
        List<ActivityItem> items = new ArrayList<>();
        if (canDevices) {
            List<DeviceEventRow> rows = jdbc.limit("SELECT e.operation, d.code, d.item_name, u.name AS unit_name,\n"
                    + "       actor.display_name AS actor_name, e.created_at\n"
                    + "FROM fm_fire_device_event e\n"
                    + "JOIN fm_fire_device d ON d.id = e.device_id\n"
                    + "JOIN fm_fire_unit u ON u.id = d.unit_id\n"
                    + "JOIN fm_user actor ON actor.id = e.actor_id\n"
                    + "WHERE d.deleted = 0 AND " + unitsFilter.matches("d.unit_id") + "\n"
                    + "ORDER BY e.id DESC", 6)
                    .params(unitsFilter.parameters()).query(DeviceEventRow.class).list();
            for (DeviceEventRow r : rows) {
                String label = DEVICE_OP_LABELS.getOrDefault(r.operation(), r.operation());
                String tone = "SCRAP".equals(r.operation()) ? "destructive"
                        : "INSPECT".equals(r.operation()) || "ISSUE".equals(r.operation()) ? "ok" : "info";
                items.add(new ActivityItem("DEVICE", label, r.code() + " · " + r.itemName(),
                        r.unitName(), r.actorName(), r.createdAt(), tone));
            }
        }
        if (canHazards) {
            List<HazardEventRow> rows = jdbc.limit("SELECT e.action, flow.action_label, a.title, u.name AS unit_name,\n"
                    + "       actor.display_name AS actor_name, e.created_at\n"
                    + "FROM fm_approval_event e\n"
                    + "JOIN fm_approval a ON a.id = e.approval_id\n"
                    + "JOIN fm_fire_approval_unit au ON au.approval_id = a.id\n"
                    + "JOIN fm_fire_unit u ON u.id = au.unit_id\n"
                    + "JOIN fm_user actor ON actor.id = e.actor_id\n"
                    + "LEFT JOIN fm_approval_flow_event flow ON flow.event_id = e.id\n"
                    + "WHERE " + unitsFilter.matches("au.unit_id") + "\n"
                    + "ORDER BY e.id DESC", 6)
                    .params(unitsFilter.parameters()).query(HazardEventRow.class).list();
            for (HazardEventRow r : rows) {
                String label = r.actionLabel() != null && !r.actionLabel().isEmpty()
                        ? r.actionLabel()
                        : HAZARD_ACTION_LABELS.getOrDefault(r.action(), r.action());
                String tone = "RETURN".equals(r.action()) ? "attention"
                        : "FINAL_CLOSE".equals(r.action()) ? "ok" : "info";
                items.add(new ActivityItem("HAZARD", label, r.title(),
                        r.unitName(), r.actorName(), r.createdAt(), tone));
            }
        }
        if (canDrills) {
            List<DrillEventRow> rows = jdbc.limit("SELECT d.title, d.participant_count, u.name AS unit_name,\n"
                    + "       actor.display_name AS actor_name, d.created_at\n"
                    + "FROM fm_fire_drill d\n"
                    + "JOIN fm_fire_unit u ON u.id = d.unit_id\n"
                    + "JOIN fm_user actor ON actor.id = d.created_by\n"
                    + "WHERE " + unitsFilter.matches("d.unit_id") + "\n"
                    + "ORDER BY d.id DESC", 4)
                    .params(unitsFilter.parameters()).query(DrillEventRow.class).list();
            for (DrillEventRow r : rows) {
                items.add(new ActivityItem("DRILL", "登记应急演练", r.title() + "（" + r.participantCount() + "人）",
                        r.unitName(), r.actorName(), r.createdAt(), "ok"));
            }
        }
        items.sort(Comparator.comparing(ActivityItem::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        return items.size() > 8 ? new ArrayList<>(items.subList(0, 8)) : items;
    }

    private static int urgencyRank(List<FireReminder.Notice> notices) {
        if (notices.contains(FireReminder.Notice.EXPIRED)) return 1;
        if (notices.contains(FireReminder.Notice.THREE_MONTHS)) return 2;
        if (notices.contains(FireReminder.Notice.UNINSPECTED)) return 3;
        if (notices.contains(FireReminder.Notice.INSPECTION)) return 4;
        return 5;
    }

    private static List<YearMonth> lastSixMonths(LocalDate today) {
        YearMonth current = YearMonth.from(today);
        List<YearMonth> months = new ArrayList<>(6);
        for (int i = 5; i >= 0; i--) {
            months.add(current.minusMonths(i));
        }
        return months;
    }

    private static Map<String, String> deviceOpLabels() {
        Map<String, String> map = new HashMap<>();
        map.put("CREATE", "新增入库");
        map.put("EDIT", "修改设备资料");
        map.put("MOVE", "变更存放地点");
        map.put("INSPECT", "登记年检");
        map.put("ISSUE", "库存领用");
        map.put("REPLACE_OUT", "换下入库");
        map.put("REPLACE_IN", "换上使用");
        map.put("SCRAP", "设备报废");
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, String> hazardActionLabels() {
        Map<String, String> map = new HashMap<>();
        map.put("CREATE", "录入隐患");
        map.put("REPORT_CLOSE", "整改销号上报");
        map.put("CONFIRM_CLOSE", "科室确认销号");
        map.put("FINAL_CLOSE", "段级总销号");
        map.put("RETURN", "退回整改");
        return Collections.unmodifiableMap(map);
    }

    private static final class UnitCounter {
        final FireAccess.Unit unit;
        final FireAccess.Unit parent;
        int deviceTotal;
        int expiredCount;
        int expiringCount;
        int inspectionAlertCount;
        int openHazardCount;
        LocalDateTime latestDrillAt;

        UnitCounter(FireAccess.Unit unit, FireAccess.Unit parent) {
            this.unit = unit;
            this.parent = parent;
        }

        UnitStatus toStatus() {
            String level;
            int severity;
            if (expiredCount > 0 || openHazardCount >= 3) {
                level = "DANGER";
                severity = 1;
            } else if (expiringCount > 0 || inspectionAlertCount > 0 || openHazardCount > 0) {
                level = "WARNING";
                severity = 2;
            } else {
                level = "OK";
                severity = 3;
            }
            return new UnitStatus(unit.id(), unit.code(), unit.name(), unit.kind().name(),
                    parent == null ? null : parent.name(),
                    deviceTotal, expiredCount, expiringCount, inspectionAlertCount,
                    openHazardCount, latestDrillAt, level, severity);
        }
    }

    @Value
    static class DeviceRow {
        long id;
        long unitId;
        String unitName;
        String code;
        FireDeviceController.Category category;
        String itemName;
        String useSite;
        String storageLocation;
        LocalDate inspectedOn;
        LocalDate expiresOn;
        int state;
        LocalDateTime createdAt;
    }

    @Value
    static class HazardRow {
        long id;
        long unitId;
        FireClosure.Status status;
        LocalDateTime createdAt;
        LocalDateTime updatedAt;
    }

    @Value
    static class DrillRow {
        long id;
        long unitId;
        String title;
        LocalDateTime drilledAt;
        int participantCount;
    }

    @Value
    static class MaintainerRow {
        long id;
        String name;
        String contact;
        String phone;
    }

    @Value
    static class IdCount {
        long id;
        int cnt;
    }

    @Value
    static class DeviceEventRow {
        String operation;
        String code;
        String itemName;
        String unitName;
        String actorName;
        LocalDateTime createdAt;
    }

    @Value
    static class HazardEventRow {
        String action;
        String actionLabel;
        String title;
        String unitName;
        String actorName;
        LocalDateTime createdAt;
    }

    @Value
    static class DrillEventRow {
        String title;
        int participantCount;
        String unitName;
        String actorName;
        LocalDateTime createdAt;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Summary {
        int deviceTotal;
        int deviceInUse;
        int deviceStock;
        int deviceScrapped;
        double healthRate;
        int expiredCount;
        int within3MonthsCount;
        int within6MonthsCount;
        int uninspectedCount;
        int inspectionDueCount;
        int hazardTotal;
        int hazardOpen;
        int hazardClosed;
        double closureRate;
        int hazardPendingRectification;
        int hazardPendingDepartment;
        int hazardPendingSection;
        int hazardInWorkflow;
        int drillTotalYear;
        int drillParticipantsYear;
        int drillCoveredUnits;
        int drillTargetUnits;
        int maintainerCount;
        int departmentCount;
        int stationCount;
        int totalUnitCount;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class CategoryStat {
        String code;
        String label;
        int count;
        double ratio;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UrgentDevice {
        long id;
        long unitId;
        String unitName;
        String code;
        String category;
        String itemName;
        String useSite;
        String storageLocation;
        LocalDate expiresOn;
        LocalDate inspectedOn;
        List<Integer> notices;
        Long daysRemaining;
        int urgencyRank;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class UnitStatus {
        long unitId;
        String code;
        String name;
        String kind;
        String parentName;
        int deviceTotal;
        int expiredCount;
        int expiringCount;
        int inspectionAlertCount;
        int openHazardCount;
        LocalDateTime latestDrillAt;
        String level;
        int severityOrder;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class ActivityItem {
        String kind;
        String title;
        String detail;
        String unitName;
        String actorName;
        LocalDateTime occurredAt;
        String tone;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class MaintainerCard {
        long id;
        String name;
        String contact;
        String phone;
        int unitCount;
        int deviceCount;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class MonthlyPoint {
        String label;
        int hazardsCreated;
        int hazardsClosed;
        int drills;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Capabilities {
        boolean devices;
        boolean hazards;
        boolean drills;
        boolean maintainers;
        boolean canAddHazard;
        boolean canAddDevice;
        boolean canWriteDrill;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Dashboard {
        LocalDateTime generatedAt;
        Long selectedUnitId;
        List<FireAccess.Unit> units;
        Summary summary;
        List<CategoryStat> categories;
        List<UrgentDevice> urgentDevices;
        List<UnitStatus> unitStatuses;
        List<ActivityItem> recentActivities;
        List<MaintainerCard> maintainers;
        List<MonthlyPoint> trends;
        Capabilities capabilities;
    }
}
