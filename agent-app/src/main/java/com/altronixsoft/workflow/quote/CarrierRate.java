package com.altronixsoft.workflow.quote;

import java.math.BigDecimal;

public record CarrierRate(String carrier, BigDecimal cost, String currency, int transitDays) {}
