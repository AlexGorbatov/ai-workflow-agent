package com.altronixsoft.workflow.mockrates;

import java.math.BigDecimal;

public record CarrierRate(String carrier, BigDecimal cost, String currency, int transitDays) {}
