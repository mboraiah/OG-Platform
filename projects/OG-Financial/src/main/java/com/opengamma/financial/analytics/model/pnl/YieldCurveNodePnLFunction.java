/**
 * Copyright (C) 2011 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.financial.analytics.model.pnl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

import javax.time.calendar.Clock;
import javax.time.calendar.LocalDate;
import javax.time.calendar.Period;

import org.apache.commons.lang.ArrayUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.opengamma.OpenGammaRuntimeException;
import com.opengamma.analytics.financial.schedule.HolidayDateRemovalFunction;
import com.opengamma.analytics.financial.schedule.Schedule;
import com.opengamma.analytics.financial.schedule.ScheduleCalculatorFactory;
import com.opengamma.analytics.financial.schedule.TimeSeriesSamplingFunction;
import com.opengamma.analytics.financial.schedule.TimeSeriesSamplingFunctionFactory;
import com.opengamma.analytics.financial.timeseries.util.TimeSeriesDifferenceOperator;
import com.opengamma.core.config.ConfigSource;
import com.opengamma.core.historicaltimeseries.HistoricalTimeSeries;
import com.opengamma.core.position.Position;
import com.opengamma.core.security.Security;
import com.opengamma.core.value.MarketDataRequirementNames;
import com.opengamma.engine.ComputationTarget;
import com.opengamma.engine.ComputationTargetSpecification;
import com.opengamma.engine.ComputationTargetType;
import com.opengamma.engine.function.AbstractFunction;
import com.opengamma.engine.function.FunctionCompilationContext;
import com.opengamma.engine.function.FunctionExecutionContext;
import com.opengamma.engine.function.FunctionInputs;
import com.opengamma.engine.value.ComputedValue;
import com.opengamma.engine.value.ValueProperties;
import com.opengamma.engine.value.ValuePropertyNames;
import com.opengamma.engine.value.ValueRequirement;
import com.opengamma.engine.value.ValueRequirementNames;
import com.opengamma.engine.value.ValueSpecification;
import com.opengamma.financial.OpenGammaCompilationContext;
import com.opengamma.financial.OpenGammaExecutionContext;
import com.opengamma.financial.analytics.DoubleLabelledMatrix1D;
import com.opengamma.financial.analytics.fixedincome.InterestRateInstrumentType;
import com.opengamma.financial.analytics.ircurve.FixedIncomeStripWithSecurity;
import com.opengamma.financial.analytics.ircurve.InterpolatedYieldCurveSpecificationWithSecurities;
import com.opengamma.financial.analytics.ircurve.StripInstrumentType;
import com.opengamma.financial.analytics.ircurve.calcconfig.ConfigDBCurveCalculationConfigSource;
import com.opengamma.financial.analytics.ircurve.calcconfig.MultiCurveCalculationConfig;
import com.opengamma.financial.analytics.model.curve.interestrate.FXImpliedYieldCurveFunction;
import com.opengamma.financial.analytics.timeseries.DateConstraint;
import com.opengamma.financial.analytics.timeseries.HistoricalTimeSeriesBundle;
import com.opengamma.financial.analytics.timeseries.HistoricalTimeSeriesFunctionUtils;
import com.opengamma.financial.convention.calendar.Calendar;
import com.opengamma.financial.convention.calendar.MondayToFridayCalendar;
import com.opengamma.financial.currency.ConfigDBCurrencyPairsSource;
import com.opengamma.financial.currency.CurrencyPairs;
import com.opengamma.financial.security.FinancialSecurity;
import com.opengamma.financial.security.FinancialSecurityUtils;
import com.opengamma.financial.security.future.InterestRateFutureSecurity;
import com.opengamma.financial.security.option.IRFutureOptionSecurity;
import com.opengamma.financial.security.swap.SwapSecurity;
import com.opengamma.id.ExternalId;
import com.opengamma.id.UniqueId;
import com.opengamma.util.money.Currency;
import com.opengamma.util.money.UnorderedCurrencyPair;
import com.opengamma.util.timeseries.DoubleTimeSeries;

/**
 *
 */
public class YieldCurveNodePnLFunction extends AbstractFunction.NonCompiledInvoker {
  private static final Logger s_logger = LoggerFactory.getLogger(YieldCurveNodePnLFunction.class);
  // Please see http://jira.opengamma.com/browse/PLAT-2330 for information about this constant.
  /** Property name of the contribution to the P&L (e.g. yield curve, FX rate) */
  public static final String PROPERTY_PNL_CONTRIBUTIONS = "PnLContribution";
  private static final HolidayDateRemovalFunction HOLIDAY_REMOVER = HolidayDateRemovalFunction.getInstance();
  private static final Calendar WEEKEND_CALENDAR = new MondayToFridayCalendar("Weekend");
  private static final TimeSeriesDifferenceOperator DIFFERENCE = new TimeSeriesDifferenceOperator();

  @Override
  public Set<ComputedValue> execute(final FunctionExecutionContext executionContext, final FunctionInputs inputs, final ComputationTarget target, final Set<ValueRequirement> desiredValues) {
    final Position position = target.getPosition();
    final ConfigSource configSource = OpenGammaExecutionContext.getConfigSource(executionContext);
    final Clock snapshotClock = executionContext.getValuationClock();
    final LocalDate now = snapshotClock.zonedDateTime().toLocalDate();
    final Currency currency = FinancialSecurityUtils.getCurrency(position.getSecurity());
    final String currencyString = currency.getCode();
    final ValueRequirement desiredValue = desiredValues.iterator().next();
    final ValueProperties constraints = desiredValue.getConstraints();
    final String desiredCurrency;
    final Set<String> currencies = desiredValue.getConstraints().getValues(ValuePropertyNames.CURRENCY);
    if (currencies != null && !currencies.isEmpty()) {
      desiredCurrency = desiredValue.getConstraint(ValuePropertyNames.CURRENCY);
    } else {
      desiredCurrency = currencyString;
    }
    final String curveCalculationConfigName = desiredValue.getConstraint(ValuePropertyNames.CURVE_CALCULATION_CONFIG);
    final Set<String> yieldCurveNames = constraints.getValues(ValuePropertyNames.CURVE);
    final Period samplingPeriod = getSamplingPeriod(desiredValue.getConstraint(ValuePropertyNames.SAMPLING_PERIOD));
    final LocalDate startDate = now.minus(samplingPeriod);
    final Schedule scheduleCalculator = getScheduleCalculator(desiredValue.getConstraint(ValuePropertyNames.SCHEDULE_CALCULATOR));
    final TimeSeriesSamplingFunction samplingFunction = getSamplingFunction(desiredValue.getConstraint(ValuePropertyNames.SAMPLING_FUNCTION));
    final LocalDate[] schedule = HOLIDAY_REMOVER.getStrippedSchedule(scheduleCalculator.getSchedule(startDate, now, true, false), WEEKEND_CALENDAR); //REVIEW emcleod should "fromEnd" be hard-coded?
    DoubleTimeSeries<?> result = null;
    final ConfigDBCurveCalculationConfigSource curveCalculationConfigSource = new ConfigDBCurveCalculationConfigSource(configSource);
    final MultiCurveCalculationConfig curveCalculationConfig = curveCalculationConfigSource.getConfig(curveCalculationConfigName);
    HistoricalTimeSeries fxSeries = null;
    final ValueProperties resultProperties;
    boolean isInverse = true;
    if (!desiredCurrency.equals(currencyString)) {
      if (inputs.getValue(ValueRequirementNames.HISTORICAL_FX_TIME_SERIES) != null) {
        final Map<UnorderedCurrencyPair, HistoricalTimeSeries> allFXSeries = (Map<UnorderedCurrencyPair, HistoricalTimeSeries>) inputs.getValue(ValueRequirementNames.HISTORICAL_FX_TIME_SERIES);
        final ConfigDBCurrencyPairsSource currencyPairsSource = new ConfigDBCurrencyPairsSource(configSource);
        final CurrencyPairs currencyPairs = currencyPairsSource.getCurrencyPairs(CurrencyPairs.DEFAULT_CURRENCY_PAIRS);
        if (desiredCurrency.equals(currencyPairs.getCurrencyPair(Currency.of(desiredCurrency), currency).getCounter().getCode())) {
          isInverse = false;
        }
        if (allFXSeries.size() != 1) {
          throw new OpenGammaRuntimeException("Have more than one FX series; should not happen");
        }
        final Map.Entry<UnorderedCurrencyPair, HistoricalTimeSeries> entry = Iterables.getOnlyElement(allFXSeries.entrySet());
        if (!UnorderedCurrencyPair.of(Currency.of(desiredCurrency), currency).equals(entry.getKey())) {
          throw new OpenGammaRuntimeException("Could not get FX series for currency pair " + desiredCurrency + ", " + currencyString);
        }
        fxSeries = entry.getValue();
        resultProperties = getResultProperties(desiredValue, desiredCurrency, yieldCurveNames.toArray(ArrayUtils.EMPTY_STRING_ARRAY), curveCalculationConfigName);
      } else {
        throw new OpenGammaRuntimeException("Could not get FX series for currency pair " + desiredCurrency + ", " + currencyString);
      }
    } else {
      resultProperties = getResultProperties(desiredValue, currencyString, yieldCurveNames.toArray(ArrayUtils.EMPTY_STRING_ARRAY), curveCalculationConfigName);
    }
    for (final String yieldCurveName : yieldCurveNames) {
      final ValueRequirement ycnsRequirement = getYCNSRequirement(currencyString, curveCalculationConfigName, yieldCurveName, target, constraints);
      final Object ycnsObject = inputs.getValue(ycnsRequirement);
      if (ycnsObject == null) {
        throw new OpenGammaRuntimeException("Could not get yield curve node sensitivities; " + ycnsRequirement);
      }
      final DoubleLabelledMatrix1D ycns = (DoubleLabelledMatrix1D) ycnsObject;
      final ValueRequirement ychtsRequirement = getYCHTSRequirement(currency, yieldCurveName, samplingPeriod.toString());
      final Object ychtsObject = inputs.getValue(ychtsRequirement);
      if (ychtsObject == null) {
        throw new OpenGammaRuntimeException("Could not get yield curve historical time series; " + ychtsRequirement);
      }
      final HistoricalTimeSeriesBundle ychts = (HistoricalTimeSeriesBundle) ychtsObject;
      final DoubleTimeSeries<?> pnLSeries;
      if (curveCalculationConfig.getCalculationMethod().equals(FXImpliedYieldCurveFunction.FX_IMPLIED)) {
        pnLSeries = getPnLSeries(ycns, ychts, schedule, samplingFunction);
      } else {
        final ValueRequirement curveSpecRequirement = getCurveSpecRequirement(currency, yieldCurveName);
        final Object curveSpecObject = inputs.getValue(curveSpecRequirement);
        if (curveSpecObject == null) {
          throw new OpenGammaRuntimeException("Could not get curve specification; " + curveSpecRequirement);
        }
        final InterpolatedYieldCurveSpecificationWithSecurities curveSpec = (InterpolatedYieldCurveSpecificationWithSecurities) curveSpecObject;
        pnLSeries = getPnLSeries(curveSpec, ycns, ychts, schedule, samplingFunction, fxSeries, isInverse);
      }
      if (result == null) {
        result = pnLSeries;
      } else {
        result = result.add(pnLSeries);
      }
    }
    if (result == null) {
      throw new OpenGammaRuntimeException("Could not get any values for security " + position.getSecurity());
    }
    result = result.multiply(position.getQuantity().doubleValue());
    final ValueSpecification resultSpec = new ValueSpecification(ValueRequirementNames.PNL_SERIES, target.toSpecification(), resultProperties);
    return Sets.newHashSet(new ComputedValue(resultSpec, result));
  }

  @Override
  public boolean canApplyTo(final FunctionCompilationContext context, final ComputationTarget target) {
    if (target.getType() != ComputationTargetType.POSITION) {
      return false;
    }
    final Security security = target.getPosition().getSecurity();
    if (security instanceof InterestRateFutureSecurity || security instanceof IRFutureOptionSecurity) {
      return false;
    }
    if (!(security instanceof FinancialSecurity)) {
      return false;
    }
    if (security instanceof SwapSecurity) {
      try {
        final InterestRateInstrumentType type = InterestRateInstrumentType.getInstrumentTypeFromSecurity((SwapSecurity) security);
        return type == InterestRateInstrumentType.SWAP_FIXED_IBOR ||
            type == InterestRateInstrumentType.SWAP_FIXED_IBOR_WITH_SPREAD ||
            type == InterestRateInstrumentType.SWAP_IBOR_IBOR ||
            type == InterestRateInstrumentType.SWAP_FIXED_OIS;
      } catch (final OpenGammaRuntimeException ogre) {
        return false;
      }
    }
    return InterestRateInstrumentType.isFixedIncomeInstrumentType((FinancialSecurity) security);
  }

  @Override
  public Set<ValueRequirement> getRequirements(final FunctionCompilationContext context, final ComputationTarget target, final ValueRequirement desiredValue) {
    final Position position = target.getPosition();
    final ValueProperties constraints = desiredValue.getConstraints();
    final Set<String> curveCalculationConfigNames = constraints.getValues(ValuePropertyNames.CURVE_CALCULATION_CONFIG);
    if (curveCalculationConfigNames == null || curveCalculationConfigNames.size() != 1) {
      return null;
    }
    final String curveCalculationConfigName = curveCalculationConfigNames.iterator().next();
    final ConfigSource configSource = OpenGammaCompilationContext.getConfigSource(context);
    final ConfigDBCurveCalculationConfigSource curveCalculationConfigSource = new ConfigDBCurveCalculationConfigSource(configSource);
    final MultiCurveCalculationConfig curveCalculationConfig = curveCalculationConfigSource.getConfig(curveCalculationConfigName);
    if (curveCalculationConfig == null) {
      s_logger.error("Could not find curve calculation configuration named " + curveCalculationConfigName);
      return null;
    }
    final Set<String> periodNames = constraints.getValues(ValuePropertyNames.SAMPLING_PERIOD);
    if (periodNames == null || periodNames.size() != 1) {
      return null;
    }
    final String samplingPeriod = periodNames.iterator().next();
    final Set<String> scheduleNames = constraints.getValues(ValuePropertyNames.SCHEDULE_CALCULATOR);
    if (scheduleNames == null || scheduleNames.size() != 1) {
      return null;
    }
    final Set<String> samplingFunctionNames = constraints.getValues(ValuePropertyNames.SAMPLING_FUNCTION);
    if (samplingFunctionNames == null || samplingFunctionNames.size() != 1) {
      return null;
    }
    final String[] yieldCurveNames = curveCalculationConfig.getYieldCurveNames();
    final Set<ValueRequirement> requirements = new HashSet<ValueRequirement>();
    final Currency currency = FinancialSecurityUtils.getCurrency(position.getSecurity());
    final String currencyString = currency.getCode();
    for (final String yieldCurveName : yieldCurveNames) {
      requirements.add(getYCNSRequirement(currencyString, curveCalculationConfigName, yieldCurveName, target, constraints));
      requirements.add(getYCHTSRequirement(currency, yieldCurveName, samplingPeriod));
      if (!curveCalculationConfig.getCalculationMethod().equals(FXImpliedYieldCurveFunction.FX_IMPLIED)) {
        requirements.add(getCurveSpecRequirement(currency, yieldCurveName));
      }
    }
    final Set<String> resultCurrencies = constraints.getValues(ValuePropertyNames.CURRENCY);
    if (resultCurrencies != null && resultCurrencies.size() == 1) {
      final ValueProperties.Builder properties = ValueProperties.builder();
      properties.with(ValuePropertyNames.CURRENCY, resultCurrencies);
      final ComputationTargetSpecification targetSpec = new ComputationTargetSpecification(position.getSecurity());
      requirements.add(new ValueRequirement(ValueRequirementNames.HISTORICAL_FX_TIME_SERIES, targetSpec, properties.get()));
    }
    return requirements;
  }

  @Override
  public Set<ValueSpecification> getResults(final FunctionCompilationContext context, final ComputationTarget target) {
    final ValueProperties properties = createValueProperties()
        .withAny(ValuePropertyNames.CURRENCY)
        .withAny(ValuePropertyNames.CURVE_CALCULATION_CONFIG)
        .withAny(ValuePropertyNames.CURVE)
        .withAny(ValuePropertyNames.SAMPLING_PERIOD)
        .withAny(ValuePropertyNames.SCHEDULE_CALCULATOR)
        .withAny(ValuePropertyNames.SAMPLING_FUNCTION)
        .with(PROPERTY_PNL_CONTRIBUTIONS, ValueRequirementNames.YIELD_CURVE_NODE_SENSITIVITIES).get();
    return Sets.newHashSet(new ValueSpecification(ValueRequirementNames.PNL_SERIES, target.toSpecification(), properties));
  }

  @Override
  public Set<ValueSpecification> getResults(final FunctionCompilationContext context, final ComputationTarget target, final Map<ValueSpecification, ValueRequirement> inputs) {
    final Set<String> curveNames = new HashSet<String>();
    for (final Map.Entry<ValueSpecification, ValueRequirement> entry : inputs.entrySet()) {
      if (entry.getKey().getValueName().equals(ValueRequirementNames.YIELD_CURVE_NODE_SENSITIVITIES)) {
        curveNames.add(entry.getValue().getConstraint(ValuePropertyNames.CURVE));
      }
    }
    assert !curveNames.isEmpty();
    final ValueProperties properties = createValueProperties()
        .withAny(ValuePropertyNames.CURRENCY)
        .withAny(ValuePropertyNames.CURVE_CALCULATION_CONFIG)
        .with(ValuePropertyNames.CURVE, curveNames)
        .withAny(ValuePropertyNames.SAMPLING_PERIOD)
        .withAny(ValuePropertyNames.SCHEDULE_CALCULATOR)
        .withAny(ValuePropertyNames.SAMPLING_FUNCTION)
        .with(PROPERTY_PNL_CONTRIBUTIONS, ValueRequirementNames.YIELD_CURVE_NODE_SENSITIVITIES).get();
    return Sets.newHashSet(new ValueSpecification(ValueRequirementNames.PNL_SERIES, target.toSpecification(), properties));
  }

  @Override
  public ComputationTargetType getTargetType() {
    return ComputationTargetType.POSITION;
  }

  @Override
  public boolean canHandleMissingRequirements() {
    return true;
  }

  protected ValueProperties getResultProperties(final ValueRequirement desiredValue, final String currency, final String[] curveNames, final String curveCalculationConfig) {
    return createValueProperties()
        .with(ValuePropertyNames.CURRENCY, currency)
        .with(ValuePropertyNames.CURVE_CALCULATION_CONFIG, desiredValue.getConstraint(ValuePropertyNames.CURVE_CALCULATION_CONFIG))
        .with(ValuePropertyNames.CURVE, desiredValue.getConstraints().getValues(ValuePropertyNames.CURVE))
        .with(ValuePropertyNames.SAMPLING_PERIOD, desiredValue.getConstraint(ValuePropertyNames.SAMPLING_PERIOD))
        .with(ValuePropertyNames.SCHEDULE_CALCULATOR, desiredValue.getConstraint(ValuePropertyNames.SCHEDULE_CALCULATOR))
        .with(ValuePropertyNames.SAMPLING_FUNCTION, desiredValue.getConstraint(ValuePropertyNames.SAMPLING_FUNCTION))
        .with(PROPERTY_PNL_CONTRIBUTIONS, ValueRequirementNames.YIELD_CURVE_NODE_SENSITIVITIES).get();
  }

  private Period getSamplingPeriod(final String samplingPeriodName) {
    return Period.parse(samplingPeriodName);
  }

  private Schedule getScheduleCalculator(final String scheduleCalculatorName) {
    return ScheduleCalculatorFactory.getScheduleCalculator(scheduleCalculatorName);
  }

  private TimeSeriesSamplingFunction getSamplingFunction(final String samplingFunctionName) {
    return TimeSeriesSamplingFunctionFactory.getFunction(samplingFunctionName);
  }

  private DoubleTimeSeries<?> getPnLSeries(final InterpolatedYieldCurveSpecificationWithSecurities spec, final DoubleLabelledMatrix1D curveSensitivities,
      final HistoricalTimeSeriesBundle timeSeriesBundle, final LocalDate[] schedule, final TimeSeriesSamplingFunction samplingFunction,
      final HistoricalTimeSeries fxSeries, final boolean isInverse) {
    DoubleTimeSeries<?> pnlSeries = null;
    final int n = curveSensitivities.size();
    final Object[] labels = curveSensitivities.getLabels();
    final List<Object> labelsList = Arrays.asList(labels);
    final double[] values = curveSensitivities.getValues();
    final SortedSet<FixedIncomeStripWithSecurity> strips = (SortedSet<FixedIncomeStripWithSecurity>) spec.getStrips();
    final FixedIncomeStripWithSecurity[] stripsArray = strips.toArray(new FixedIncomeStripWithSecurity[] {});
    final List<StripInstrumentType> stripList = new ArrayList<StripInstrumentType>(n);
    int stripCount = 0;
    for (final FixedIncomeStripWithSecurity strip : strips) {
      final int index = stripCount++; //labelsList.indexOf(strip.getSecurityIdentifier());
      if (index < 0) {
        throw new OpenGammaRuntimeException("Could not get index for " + strip);
      }
      stripList.add(index, strip.getInstrumentType());
    }
    for (int i = 0; i < n; i++) {
      final ExternalId id = stripsArray[i].getSecurityIdentifier();
      double sensitivity = values[i];
      if (stripList.get(i) == StripInstrumentType.FUTURE) {
        // TODO Temporary fix as sensitivity is to rate, but historical time series is to price (= 1 - rate)
        sensitivity *= -1;
      }
      final HistoricalTimeSeries dbNodeTimeSeries = timeSeriesBundle.get(MarketDataRequirementNames.MARKET_VALUE, id);
      if (dbNodeTimeSeries == null) {
        throw new OpenGammaRuntimeException("Could not identifier / price series pair for " + id);
      }
      if (dbNodeTimeSeries.getTimeSeries().isEmpty()) {
        throw new OpenGammaRuntimeException("Time series " + id + " is empty");
      }
      DoubleTimeSeries<?> nodeTimeSeries = samplingFunction.getSampledTimeSeries(dbNodeTimeSeries.getTimeSeries(), schedule);
      if (fxSeries != null) {
        if (isInverse) {
          nodeTimeSeries = nodeTimeSeries.divide(fxSeries.getTimeSeries());
        } else {
          nodeTimeSeries = nodeTimeSeries.multiply(fxSeries.getTimeSeries());
        }
      }
      nodeTimeSeries = DIFFERENCE.evaluate(nodeTimeSeries);
      if (pnlSeries == null) {
        pnlSeries = nodeTimeSeries.multiply(sensitivity);
      } else {
        pnlSeries = pnlSeries.add(nodeTimeSeries.multiply(sensitivity));
      }
    }
    return pnlSeries;
  }

  private DoubleTimeSeries<?> getPnLSeries(final DoubleLabelledMatrix1D curveSensitivities, final HistoricalTimeSeriesBundle timeSeriesBundle, final LocalDate[] schedule,
      final TimeSeriesSamplingFunction samplingFunction) {
    DoubleTimeSeries<?> pnlSeries = null;
    final Object[] labels = curveSensitivities.getLabels();
    final double[] values = curveSensitivities.getValues();
    for (int i = 0; i < labels.length; i++) {
      final ExternalId id = (ExternalId) labels[i];
      final HistoricalTimeSeries dbNodeTimeSeries = timeSeriesBundle.get(MarketDataRequirementNames.MARKET_VALUE, id);
      if (dbNodeTimeSeries == null) {
        throw new OpenGammaRuntimeException("Could not identifier / price series pair for " + id);
      }
      DoubleTimeSeries<?> nodeTimeSeries = samplingFunction.getSampledTimeSeries(dbNodeTimeSeries.getTimeSeries(), schedule);
      nodeTimeSeries = DIFFERENCE.evaluate(nodeTimeSeries);
      if (pnlSeries == null) {
        pnlSeries = nodeTimeSeries.multiply(values[i]);
      } else {
        pnlSeries = pnlSeries.add(nodeTimeSeries.multiply(values[i]));
      }
    }
    return pnlSeries;
  }

  protected ValueRequirement getYCNSRequirement(final String currencyString, final String curveCalculationConfigName, final String yieldCurveName, final ComputationTarget target,
      final ValueProperties desiredValueProperties) {
    final UniqueId uniqueId = target.getPosition().getSecurity().getUniqueId();
    final ValueProperties properties = ValueProperties.builder()
        .with(ValuePropertyNames.CURRENCY, currencyString)
        .with(ValuePropertyNames.CURVE_CURRENCY, currencyString)
        .with(ValuePropertyNames.CURVE, yieldCurveName)
        .with(ValuePropertyNames.CURVE_CALCULATION_CONFIG, curveCalculationConfigName).get();
    return new ValueRequirement(ValueRequirementNames.YIELD_CURVE_NODE_SENSITIVITIES, ComputationTargetType.SECURITY, uniqueId, properties);
  }

  private ValueRequirement getYCHTSRequirement(final Currency currency, final String yieldCurveName, final String samplingPeriod) {
    return HistoricalTimeSeriesFunctionUtils.createYCHTSRequirement(currency, yieldCurveName, MarketDataRequirementNames.MARKET_VALUE, null, DateConstraint.VALUATION_TIME.minus(samplingPeriod), true,
        DateConstraint.VALUATION_TIME, true);
  }

  private ValueRequirement getCurveSpecRequirement(final Currency currency, final String yieldCurveName) {
    final ValueProperties properties = ValueProperties.builder()
        .with(ValuePropertyNames.CURVE, yieldCurveName).get();
    return new ValueRequirement(ValueRequirementNames.YIELD_CURVE_SPEC, currency, properties);
  }
}
