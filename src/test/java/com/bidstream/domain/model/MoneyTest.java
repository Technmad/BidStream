package com.bidstream.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Test
    void roundsHalfUpToFourDecimalPlaces() {
        Money money = Money.of(new BigDecimal("10.00005"), USD);

        assertThat(money.amount()).isEqualByComparingTo("10.0001");
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> Money.of(new BigDecimal("-1.00"), USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionAccumulatesPrecisely() {
        Money a = Money.of("10.10", "USD");
        Money b = Money.of("0.05", "USD");

        assertThat(a.plus(b)).isEqualTo(Money.of("10.15", "USD"));
    }

    @Test
    void comparingDifferentCurrenciesThrows() {
        Money usd = Money.of("10.00", "USD");
        Money eur = Money.of(BigDecimal.TEN, EUR);

        assertThatThrownBy(() -> usd.isGreaterThan(eur))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addingDifferentCurrenciesThrows() {
        Money usd = Money.of("10.00", "USD");
        Money eur = Money.of(BigDecimal.TEN, EUR);

        assertThatThrownBy(() -> usd.plus(eur)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIsValueBasedIgnoringTrailingZeroFormatting() {
        assertThat(Money.of("5.5", "USD")).isEqualTo(Money.of("5.5000", "USD"));
    }

    @Test
    void maxReturnsTheLargerValue() {
        Money small = Money.of("10.00", "USD");
        Money large = Money.of("20.00", "USD");

        assertThat(small.max(large)).isEqualTo(large);
        assertThat(large.max(small)).isEqualTo(large);
    }

    @Test
    void zeroIsZeroInTheGivenCurrency() {
        assertThat(Money.zero(USD).amount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(Money.zero(USD).currency()).isEqualTo(USD);
    }
}
