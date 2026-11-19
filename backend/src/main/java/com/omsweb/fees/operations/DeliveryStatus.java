package com.omsweb.fees.operations;

public enum DeliveryStatus {
    NOT_SENT("未投递"),
    SENT("已发送"),
    BOUNCED("已退信");

    private final String label;

    DeliveryStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
