package com.bidstream.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * Immutable monetary value object. Never backed by {@code double}/{@code float} (PDR §7.1) —
 * amounts are always {@link BigDecimal}, scaled to match the {@code NUMERIC(19,4)} columns.
 */
public final class Money implements Comparable<Money> {

    private static final int SCALE = 4;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        this.currency = currency;
    }

    public static Money of(BigDecimal amount, Currency currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative: " + amount);
        }
        return new Money(amount.setScale(SCALE, RoundingMode.HALF_UP), currency);
    }

    public static Money of(String amount, String currencyCode) {
        return of(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return of(BigDecimal.ZERO, currency);
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money max(Money other) {
        requireSameCurrency(other);
        return this.compareTo(other) >= 0 ? this : other;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);
        return this.compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.compareTo(other) < 0;
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Money money)) return false;
        return amount.compareTo(money.amount) == 0 && currency.equals(money.currency);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount.stripTrailingZeros(), currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
