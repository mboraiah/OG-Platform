/**
 * Copyright (C) 2009 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.financial.provider.historicaltimeseries.impl;

import javax.time.calendar.LocalDate;

import com.opengamma.financial.provider.historicaltimeseries.HistoricalTimeSeriesProviderGetRequest;
import com.opengamma.financial.provider.historicaltimeseries.HistoricalTimeSeriesProviderGetResult;

/**
 * Simple implementation of a provider of time-series that finds nothing.
 */
public class NoneFoundHistoricalTimeSeriesProvider extends AbstractHistoricalTimeSeriesProvider {

  /**
   * Creates an instance.
   */
  public NoneFoundHistoricalTimeSeriesProvider() {
    super(".*", LocalDate.MIN_DATE);
  }

  //-------------------------------------------------------------------------
  @Override
  protected HistoricalTimeSeriesProviderGetResult doBulkGet(HistoricalTimeSeriesProviderGetRequest request) {
    return new HistoricalTimeSeriesProviderGetResult();
  }

}