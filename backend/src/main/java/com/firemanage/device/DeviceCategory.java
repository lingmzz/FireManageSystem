package com.firemanage.device;

public enum DeviceCategory {
    EXTINGUISHER("灭火器"),
    HYDRANT("消火栓"),
    SMOKE_ALARM("烟感报警器"),
    HOSE("消防水带"),
    EMERGENCY_CABINET("消防应急柜"),
    RESPIRATOR("防毒面罩"),
    OTHER("其它");

    private final String label;

    DeviceCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
