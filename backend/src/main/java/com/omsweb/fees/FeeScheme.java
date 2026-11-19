package com.omsweb.fees;

public record FeeScheme(long id, String code, String name, String payer, String payerEmail, boolean active) {}
