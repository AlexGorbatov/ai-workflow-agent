package com.altronixsoft.workflow.quote;

import static org.assertj.core.api.Assertions.assertThat;

import com.altronixsoft.workflow.IntegrationTest;
import com.altronixsoft.workflow.MockApps;
import com.altronixsoft.workflow.SampleEmails;
import com.altronixsoft.workflow.engine.StepResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** The Price step against the real mock rates service (lanes and carriers from its application.yml). */
@IntegrationTest
@TestPropertySource(properties = "workflow.real-steps=true")
@Import(MockApps.class)
class PriceStepIT {

    private static final Customer GOLD =
            new Customer("C-1001", "PolMarket Sp. z o.o.", "anna.kowalska@polmarket.test", CustomerTier.GOLD);

    @Autowired
    PriceStep step;

    private static QuoteContext enriched(String origin, String destination, String weightKg, String cargo, Customer c) {
        QuoteRequest request = new QuoteRequest(
                origin, destination, new BigDecimal(weightKg), 12, cargo, LocalDate.of(2026, 10, 6), "en");
        return QuoteContext.of(SampleEmails.read("01-happy-path-gold.eml"))
                .withRequest(request)
                .withCustomer(c);
    }

    private StepResult.Next price(QuoteContext ctx) {
        StepResult result = step.execute(UUID.randomUUID(), ctx);
        assertThat(result).isInstanceOf(StepResult.Next.class);
        return (StepResult.Next) result;
    }

    @Test
    void theCheapestCarrierIsPricedForTheCustomersTier() {
        // 575 km, 7200 kg: BudgetTrans 115 + 372.60 = 487.60 (2 days) is the cheapest.
        // 487.60 × 1.12 × 1.08 = 589.80096 → 590; (590 − 487.60) / 590 = 17.36 %
        StepResult.Next next = price(enriched("Warszawa", "Berlin", "7200", "household goods", GOLD));

        assertThat(next.next()).isEqualTo(QuoteState.PRICED);
        Quote quote = next.ctx().quote();
        assertThat(quote.carrier()).isEqualTo("BudgetTrans");
        assertThat(quote.cost()).isEqualByComparingTo("487.60");
        assertThat(quote.price()).isEqualByComparingTo("590");
        assertThat(quote.marginPct()).isEqualByComparingTo("17.36");
        assertThat(next.ctx().rates()).hasSize(3);
        assertThat(next.ctx().flags()).isEmpty();
    }

    @Test
    void carriersSlowerThanFiveDaysAreNotOffered() {
        // 3000 km: FastFreight 5 days, EuroLine 6, BudgetTrans 8; only FastFreight is fast enough.
        StepResult.Next next = price(enriched("Göteborg", "Madrid", "20000", "timber", GOLD));

        assertThat(next.next()).isEqualTo(QuoteState.PRICED);
        assertThat(next.ctx().quote().carrier()).isEqualTo("FastFreight");
    }

    @Test
    void dangerousGoodsPayTheAdrSurchargeAndAreFlagged() {
        StepResult.Next next = price(enriched("Warszawa", "Berlin", "7200", "paint, ADR class 3", GOLD));

        assertThat(next.ctx().quote().price()).isEqualByComparingTo("740"); // 589.80096 + 150 → 740
        assertThat(next.ctx().quote().breakdown()).contains("ADR surcharge: +150.00");
        assertThat(next.ctx().flags()).containsExactly(PriceStep.DANGEROUS_GOODS);
    }

    @Test
    void aNewCustomerPaysTheNewCustomerMargin() {
        // 487.60 × 1.22 × 1.08 = 642.46176 → 642
        StepResult.Next next = price(enriched("Warszawa", "Berlin", "7200", "household goods", null));

        assertThat(next.ctx().quote().price()).isEqualByComparingTo("642");
    }

    @Test
    void aLaneWithoutRatesGoesToInvestigation() {
        StepResult.Next next = price(enriched("Oslo", "Lisboa", "1000", "furniture", GOLD));

        assertThat(next.next()).isEqualTo(QuoteState.INVESTIGATING);
        assertThat(next.ctx().error()).isEqualTo(PriceStep.NO_RATES);
        assertThat(next.ctx().quote()).isNull();
    }
}
