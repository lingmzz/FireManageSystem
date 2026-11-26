package com.firemanage;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Value;

import static com.firemanage.ApiSupport.invalidField;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.strip;

final class ApprovalFlow {
    private ApprovalFlow() {}

    enum Type {
        WORKBENCH("工作台", Permission.APPROVAL_READ, Collections.singletonList(UnitScope.NONE)),
        STATION_HAZARD("车站隐患", Permission.FIRE_HAZARD_READ,
                Collections.unmodifiableList(Arrays.asList(UnitScope.OWN_UNIT, UnitScope.DEPARTMENT, UnitScope.SEGMENT, UnitScope.VISIBLE_UNIT))),
        DEPARTMENT_HAZARD("科室隐患", Permission.FIRE_HAZARD_READ,
                Collections.unmodifiableList(Arrays.asList(UnitScope.OWN_UNIT, UnitScope.DEPARTMENT, UnitScope.SEGMENT, UnitScope.VISIBLE_UNIT))),
        SEGMENT_HAZARD("段级直属隐患", Permission.FIRE_HAZARD_READ,
                Collections.unmodifiableList(Arrays.asList(UnitScope.OWN_UNIT, UnitScope.SEGMENT, UnitScope.VISIBLE_UNIT)));

        final String label;
        final Permission reader;
        final List<UnitScope> scopes;

        Type(String label, Permission reader, List<UnitScope> scopes) {
            this.label = label;
            this.reader = reader;
            this.scopes = scopes;
        }

        static Type hazard(FireAccess.Kind kind) {
            switch (kind) {
                case STATION:
                    return STATION_HAZARD;
                case DEPARTMENT:
                    return DEPARTMENT_HAZARD;
                case SEGMENT:
                    return SEGMENT_HAZARD;
                default:
                    throw new AssertionError(kind);
            }
        }
    }

    enum UnitScope {
        NONE("不限单位"),
        OWN_UNIT("隐患所属单位"),
        DEPARTMENT("对应科室"),
        SEGMENT("对应车务段"),
        VISIBLE_UNIT("有权查看隐患所属单位");

        final String label;

        UnitScope(String label) {
            this.label = label;
        }

        boolean allows(FireAccess.Scope scope, Long unitId) {
            if (this == NONE) return unitId == null;
            if (scope == null || unitId == null) return false;
            switch (this) {
                case OWN_UNIT:
                    return scope.owns(unitId);
                case DEPARTMENT:
                    return scope.hasCoverage(unitId, FireAccess.Kind.DEPARTMENT);
                case SEGMENT:
                    return scope.hasCoverage(unitId, FireAccess.Kind.SEGMENT);
                case VISIBLE_UNIT:
                    return scope.canSee(unitId);
                case NONE:
                    return false;
                default:
                    throw new AssertionError(this);
            }
        }
    }

    private static final String STEP_KEY = "[a-z0-9_-]{1,24}";

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Draft {
        @Pattern(regexp = STEP_KEY) String startKey;
        @NotNull @Size(max = 20, message = "流程最多设置 20 个步骤") List<@NotNull @Valid Step> steps;
    }

    @Value
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
    static class Step {
        @NotNull @Pattern(regexp = STEP_KEY) String key;
        @Size(max = 80, message = "步骤名称最多 80 个字符") String name;
        @Positive Long roleId;
        UnitScope unitScope;
        boolean assigneeOnly;
        @Size(max = 30, message = "通过按钮文字最多 30 个字符") String passLabel;
        boolean remarkRequired;
        @Pattern(regexp = STEP_KEY) String passTo;
        @Pattern(regexp = STEP_KEY) String returnTo;

        String field(String name) {
            return "step." + key + "." + name;
        }

        String actionLabel() {
            return passLabel == null || isBlank(passLabel) ? "通过" : strip(passLabel);
        }
    }

    static void validate(Type type, Draft draft) {
        LinkedHashMap<String, Step> steps = new LinkedHashMap<>();
        for (Step step : draft.steps()) {
            if (steps.put(step.key(), step) != null) throw invalidField("steps", "步骤标识重复，请重新添加该步骤");
            if (step.name() == null || isBlank(step.name())) {
                throw invalidField(step.field("name"), "请输入步骤名称");
            }
            if (step.roleId() == null) throw invalidField(step.field("roleId"), "请选择办理角色");
            if (step.unitScope() == null || !type.scopes.contains(step.unitScope())) {
                throw invalidField(step.field("unitScope"), "请选择适用于此流程的单位范围");
            }
            if (step.assigneeOnly() && type != Type.WORKBENCH) {
                throw invalidField(step.field("assigneeOnly"), "消防隐患按办理角色和单位范围分配");
            }
        }
        Step start = steps.get(draft.startKey());
        if (start == null) throw invalidField("startKey", "请选择第一个办理步骤");
        if (start.assigneeOnly()) {
            throw invalidField(start.field("assigneeOnly"), "第一个步骤由办理角色接收");
        }
        for (Step step : steps.values()) {
            if (step.passTo() != null && !steps.containsKey(step.passTo())) {
                throw invalidField(step.field("passTo"), "请选择有效的通过去向");
            }
            if (step.returnTo() != null && !steps.containsKey(step.returnTo())) {
                throw invalidField(step.field("returnTo"), "请选择有效的退回去向");
            }
        }

        HashMap<String, Integer> order = new HashMap<>();
        for (Step step = start; step != null; step = steps.get(step.passTo())) {
            order.put(step.key(), order.size());
            if (order.containsKey(step.passTo())) {
                throw invalidField(step.field("passTo"), "通过去向形成了循环，请检查步骤顺序");
            }
        }
        for (Step step : steps.values()) {
            if (!order.containsKey(step.key())) {
                throw invalidField(step.field("passTo"), "从第一个步骤出发无法到达「" + strip(step.name()) + "」");
            }
            if (step.returnTo() != null && order.getOrDefault(step.returnTo(), Integer.MAX_VALUE) >= order.get(step.key())) {
                throw invalidField(step.field("returnTo"), "退回去向请选择流程中已办理的步骤");
            }
        }
    }

    static Draft example(Type type, Map<String, Long> roles) {
        switch (type) {
            case WORKBENCH:
                return new Draft("assign", Collections.unmodifiableList(Arrays.asList(
                        new Step("assign", "待指派", roles.get("DISPATCHER"), UnitScope.NONE, false,
                                "指派整改", false, "rectify", null),
                        new Step("rectify", "待整改", roles.get("RECTIFIER"), UnitScope.NONE, true,
                                "提交整改", true, "review", null),
                        new Step("review", "待复核", roles.get("REVIEWER"), UnitScope.NONE, false,
                                "复核通过", false, null, "rectify"))));
            case STATION_HAZARD:
                return new Draft("report", Collections.unmodifiableList(Arrays.asList(
                        report(roles.get("FIRE_STATION"), "department"),
                        new Step("department", "待科室确认", roles.get("FIRE_DEPARTMENT"), UnitScope.DEPARTMENT, false,
                                "科室确认", false, "close", "report"), close(roles.get("FIRE_SEGMENT")))));
            case DEPARTMENT_HAZARD:
                return new Draft("report", Collections.unmodifiableList(Arrays.asList(
                        report(roles.get("FIRE_DEPARTMENT"), "close"), close(roles.get("FIRE_SEGMENT")))));
            case SEGMENT_HAZARD:
                return new Draft("report", Collections.unmodifiableList(Arrays.asList(
                        report(roles.get("FIRE_SEGMENT"), "close"), close(roles.get("FIRE_SEGMENT")))));
            default:
                throw new AssertionError(type);
        }
    }

    private static Step report(Long roleId, String next) {
        return new Step("report", "待整改上报", roleId, UnitScope.OWN_UNIT, false, "整改销号上报", true, next, null);
    }

    private static Step close(Long roleId) {
        return new Step("close", "待段级销号", roleId, UnitScope.SEGMENT, false, "段级总销号", false, null, "report");
    }
}
