/**
 * Copyright (C) 2013 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.financial.convention;

import javax.time.calendar.DateAdjuster;
import javax.time.calendar.DateAdjusters;
import javax.time.calendar.LocalDate;

import com.opengamma.financial.convention.calendar.Calendar;
import com.opengamma.util.ArgumentChecker;

// Some future options which appear to exist don't seem to fit the exchange rules - need to investigate these

/**
 * Calculates expiry dates for gold future options
 */
public final class GoldFutureOptionExpiryCalculator implements ExchangeTradedInstrumentExpiryCalculator {
  /** Name of the calculator */
  public static final String NAME = "GoldFutureOptionExpiryCalculator";
  private static final DateAdjuster LAST_DAY_ADJUSTER = DateAdjusters.lastDayOfMonth();
  private static final GoldFutureOptionExpiryCalculator INSTANCE = new GoldFutureOptionExpiryCalculator();

  public static GoldFutureOptionExpiryCalculator getInstance() {
    return INSTANCE;
  }

  private GoldFutureOptionExpiryCalculator() {
  }


  /**
   * Expiry date of Gold Future Options:
   * Four days before the end of the month preceding the option month.
   * If expiration falls on a Friday or a day before a holiday it moves to the previous business day
   * See http://www.cmegroup.com/trading/metals/precious/gold_contractSpecs_options.html#prodType=AME
   *
   * @param n n'th expiry date after today
   * @param today valuation date
   * @param holidayCalendar holiday calendar
   * @return expiry date of the option
   */
  @Override
  public LocalDate getExpiryDate(final int n, final LocalDate today, final Calendar holidayCalendar) {
    ArgumentChecker.isTrue(n > 0, "n must be greater than zero; have {}", n);
    ArgumentChecker.notNull(today, "today");
    ArgumentChecker.notNull(holidayCalendar, "holiday calendar");
    // as options expire 1 month before futures need to get future expiry after this nth option (n + 1)
    final LocalDate futuresExpiry = GoldFutureExpiryCalculator.getInstance().getExpiryMonth(n + 1, today);
    int nBusinessDays = 4;
    LocalDate date = LAST_DAY_ADJUSTER.adjustDate(futuresExpiry.minusMonths(1));
    if (holidayCalendar.isWorkingDay(date)) {
      nBusinessDays--;
    }
    // go back to 4 business days
    while (nBusinessDays > 0) {
      date = date.minusDays(1);
      if (holidayCalendar.isWorkingDay(date)) {
        nBusinessDays--;
      }
    }
    // If day is a Friday or immediately precedes a holiday (e.g. a Friday) it moves to the previous business day
    if (!holidayCalendar.isWorkingDay(date.plusDays(1))) {
      date = date.minusDays(1);
      while (!holidayCalendar.isWorkingDay(date)) {
        date = date.minusDays(1);
      }
    }
    return date;
  }

  @Override
  /**
   * Given a LocalDate representing the valuation date and
   * an integer representing the n'th expiry after that date,
   * returns a date in the expiry month
   *
   * @param n the nth expiry
   * @param today the date
   * @return a date in the nth expiry month
   *
   */
  public LocalDate getExpiryMonth(final int n, final LocalDate today) {
    return GoldFutureExpiryCalculator.getInstance().getExpiryMonth(n, today);
  }

  @Override
  public String getName() {
    return NAME;
  }
}
