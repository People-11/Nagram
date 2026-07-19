package org.telegram.messenger;

import java.text.NumberFormat;
import java.util.Currency;

import org.telegram.ui.Components.BulletinFactory;

/** Currency formatting retained after payment support was removed. */
public final class BillingController {
    private static final BillingController INSTANCE = new BillingController();
    private static NumberFormat currencyInstance;

    public static BillingController getInstance() {
        return INSTANCE;
    }

    private BillingController() {
    }

    public static void showUnavailable() {
        BulletinFactory.global()
                .createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.nekoXPaymentRemovedToast))
                .show();
    }

    public String formatCurrency(long amount, String currency) {
        return formatCurrency(amount, currency, getCurrencyExp(currency));
    }

    public String formatCurrency(long amount, String currency, int exp) {
        return formatCurrency(amount, currency, exp, false);
    }

    public String formatCurrency(long amount, String currency, int exp, boolean rounded) {
        if (currency == null || currency.isEmpty()) {
            return String.valueOf(amount);
        }
        if ("TON".equalsIgnoreCase(currency)) {
            return "TON " + (amount / 1_000_000_000.0);
        }
        if ("XTR".equalsIgnoreCase(currency)) {
            return "XTR " + LocaleController.formatNumber(amount, ',');
        }
        try {
            Currency value = Currency.getInstance(currency);
            if (currencyInstance == null) {
                currencyInstance = NumberFormat.getCurrencyInstance();
            }
            currencyInstance.setCurrency(value);
            if (rounded) {
                currencyInstance.setMaximumFractionDigits(0);
                currencyInstance.setMinimumFractionDigits(0);
                return currencyInstance.format(Math.round(amount / Math.pow(10, exp)));
            }
            int digits = value.getDefaultFractionDigits();
            currencyInstance.setMinimumFractionDigits(digits);
            currencyInstance.setMaximumFractionDigits(digits);
            return currencyInstance.format(amount / Math.pow(10, exp));
        } catch (IllegalArgumentException ignored) {
            return amount + " " + currency;
        }
    }

    public int getCurrencyExp(String currency) {
        if ("TON".equalsIgnoreCase(currency)) {
            return 9;
        }
        if ("XTR".equalsIgnoreCase(currency)) {
            return 0;
        }
        try {
            return Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return 0;
        }
    }
}
