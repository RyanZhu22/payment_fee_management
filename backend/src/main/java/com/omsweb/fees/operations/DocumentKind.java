package com.omsweb.fees.operations;

public enum DocumentKind {
    NOTICE("缴费通知"),
    REMINDER("催缴提醒"),
    RECEIPT("收据");

    private final String label;

    DocumentKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
