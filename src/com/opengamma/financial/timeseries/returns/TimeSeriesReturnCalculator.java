package com.opengamma.financial.timeseries.returns;

import com.opengamma.math.function.Function;
import com.opengamma.timeseries.DoubleTimeSeries;
import com.opengamma.timeseries.TimeSeriesException;

/**
 * 
 * @author emcleod
 *         <p>
 *         A single-period time series return calculator.
 */

public abstract class TimeSeriesReturnCalculator implements Function<DoubleTimeSeries, DoubleTimeSeries, TimeSeriesException> {

  @Override
  public abstract DoubleTimeSeries evaluate(DoubleTimeSeries... x) throws TimeSeriesException;

}
