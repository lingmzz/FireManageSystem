package com.firemanage;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

enum Permission {
    USERS("users:manage", "管理用户"),
    ROLES("roles:manage", "管理角色"),
    MENUS("menus:manage", "管理菜单"),
    WORKFLOWS("workflows:manage", "配置审批流程"),
    APPROVAL_READ("approvals:read", "查看审批"),
    APPROVAL_CREATE("approvals:create", "上报隐患"),
    APPROVAL_ASSIGN("approvals:assign", "指派整改"),
    APPROVAL_RECTIFY("approvals:rectify", "提交整改"),
    APPROVAL_REVIEW("approvals:review", "复核整改"),
    APPROVAL_ALL("approvals:view-all", "查看全部审批"),
    FIRE_ORG("fire:org", "管理消防单位和用户范围"),
    FIRE_DEVICE_READ("fire:device-read", "查看消防设备"),
    FIRE_DEVICE_ADD("fire:device-add", "新增消防设备"),
    FIRE_DEVICE_EDIT("fire:device-edit", "维护消防设备基本资料"),
    FIRE_DEVICE_OPERATE("fire:device-operate", "领用、更换、年检和报废消防设备"),
    FIRE_DEVICE_IMPORT("fire:device-import", "导入消防设备"),
    FIRE_MAINT_READ("fire:maint-read", "查看维保单位"),
    FIRE_MAINT_WRITE("fire:maint-write", "维护维保单位和关系"),
    FIRE_DRILL_READ("fire:drill-read", "查看应急演练"),
    FIRE_DRILL_WRITE("fire:drill-write", "维护应急演练"),
    FIRE_HAZARD_READ("fire:hazard-read", "查看消防隐患"),
    FIRE_HAZARD_ADD("fire:hazard-add", "录入消防隐患"),
    FIRE_HAZARD_REPORT("fire:hazard-report", "上报消防隐患销号"),
    FIRE_HAZARD_CONFIRM("fire:hazard-confirm", "确认车站消防隐患销号"),
    FIRE_HAZARD_CLOSE("fire:hazard-close", "消防隐患总销号");

    final String code;
    final String label;

    Permission(String code, String label) {
        this.code = code;
        this.label = label;
    }

    static final Set<String> ALL_CODES = Arrays.stream(values())
            .map(permission -> permission.code).collect(Collectors.toUnmodifiableSet());
}
