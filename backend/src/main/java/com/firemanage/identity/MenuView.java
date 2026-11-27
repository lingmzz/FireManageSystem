package com.firemanage.identity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public enum MenuView {

    FIRE_DASHBOARD("fire-dashboard", "首页概览", Permission.FIRE_DASHBOARD),
    FIRE_DEVICES("fire-devices", "消防设备台账", Permission.FIRE_DEVICE_READ,
            Permission.FIRE_DEVICE_ADD, Permission.FIRE_DEVICE_EDIT, Permission.FIRE_DEVICE_OPERATE,
            Permission.FIRE_DEVICE_IMPORT),
    FIRE_MAINTAINERS("fire-maintainers", "维保单位", Permission.FIRE_MAINT_READ, Permission.FIRE_MAINT_WRITE),
    FIRE_DRILLS("fire-drills", "应急演练台账", Permission.FIRE_DRILL_READ, Permission.FIRE_DRILL_WRITE),
    FIRE_HAZARDS("fire-hazards", "隐患问题库", Permission.FIRE_HAZARD_READ,
            Permission.FIRE_HAZARD_ADD, Permission.FIRE_HAZARD_REPORT, Permission.FIRE_HAZARD_CONFIRM,
            Permission.FIRE_HAZARD_CLOSE),
    FIRE_UNITS("fire-units", "消防单位与授权", Permission.FIRE_ORG),
    APPROVALS("approvals", "工作台", Permission.APPROVAL_READ,
            Permission.APPROVAL_CREATE, Permission.APPROVAL_ASSIGN, Permission.APPROVAL_RECTIFY,
            Permission.APPROVAL_REVIEW, Permission.APPROVAL_ALL),
    USERS("users", "用户管理", Permission.USERS),
    ROLES("roles", "角色权限", Permission.ROLES),
    MENUS("menus", "菜单管理", Permission.MENUS),
    WORKFLOWS("approval-flows", "审批流程配置", Permission.WORKFLOWS);

    final String key;
    final String label;
    final Permission required;
    final List<Permission> permissions;

    MenuView(String key, String label, Permission required, Permission... operations) {
        this.key = key;
        this.label = label;
        this.required = required;
        ArrayList<Permission> options = new ArrayList<>();
        options.add(required);
        Collections.addAll(options, operations);
        this.permissions = Collections.unmodifiableList(options);
    }

    static MenuView find(String key) {
        return Arrays.stream(values()).filter(view -> view.key.equals(key)).findFirst().orElse(null);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }
}
