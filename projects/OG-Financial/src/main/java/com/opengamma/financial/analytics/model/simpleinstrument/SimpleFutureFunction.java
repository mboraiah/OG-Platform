/**
 * Copyright (C) 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.financial.analytics.model.simpleinstrument;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.time.calendar.Period;
import javax.time.calendar.ZonedDateTime;

import org.apache.commons.lang.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.opengamma.OpenGammaRuntimeException;
import com.opengamma.analytics.financial.commodity.definition.CommodityFutureDefinition;
import com.opengamma.analytics.financial.commodity.derivative.CommodityFuture;
import com.opengamma.analytics.financial.simpleinstruments.pricing.SimpleFutureDataBundle;
import com.opengamma.core.security.Security;
import com.opengamma.core.value.MarketDataRequirementNames;
import com.opengamma.engine.ComputationTarget;
import com.opengamma.engine.ComputationTargetType;
import com.opengamma.engine.function.AbstractFunction.NonCompiledInvoker;
import com.opengamma.engine.function.FunctionCompilationContext;
import com.opengamma.engine.function.FunctionExecutionContext;
import com.opengamma.engine.function.FunctionInputs;
import com.opengamma.engine.value.ComputedValue;
import com.opengamma.engine.value.ValueProperties;
import com.opengamma.engine.value.ValueProperties.Builder;
import com.opengamma.engine.value.ValuePropertyNames;
import com.opengamma.engine.value.ValueRequirement;
import com.opengamma.engine.value.ValueSpecification;
import com.opengamma.financial.OpenGammaCompilationContext;
import com.opengamma.financial.analytics.conversion.CommodityFutureConverter;
import com.opengamma.financial.analytics.timeseries.DateConstraint;
import com.opengamma.financial.analytics.timeseries.HistoricalTimeSeriesBundle;
import com.opengamma.financial.analytics.timeseries.HistoricalTimeSeriesFunctionUtils;
import com.opengamma.financial.security.FinancialSecurityUtils;
import com.opengamma.financial.security.future.AgricultureFutureSecurity;
import com.opengamma.financial.security.future.EnergyFutureSecurity;
import com.opengamma.financial.security.future.FutureSecurity;
import com.opengamma.financial.security.future.MetalFutureSecurity;
import com.opengamma.id.ExternalIdBundle;
import com.opengamma.master.historicaltimeseries.HistoricalTimeSeriesResolutionResult;
import com.opengamma.master.historicaltimeseries.HistoricalTimeSeriesResolver;
import com.opengamma.util.async.AsynchronousExecution;
import com.opengamma.util.money.Currency;
import com.opengamma.util.timeseries.localdate.LocalDateDoubleTimeSeries;

/**
 *
 */
public abstract class SimpleFutureFunction extends NonCompiledInvoker {
  private static final CommodityFutureConverter CONVERTER = new CommodityFutureConverter();
  private final String _valueRequirementName;

  public SimpleFutureFunction(final String valueRequirementName) {
    Validate.notNull(valueRequirementName, "valueRequirement name was null.");
    _valueRequirementName = valueRequirementName;
  }

  @Override
  public Set<ComputedValue> execute(FunctionExecutionContext executionContext, FunctionInputs inputs, ComputationTarget target,
    Set<ValueRequirement> desiredValues) throws AsynchronousExecution {

    // 1. Build the analytic derivative to be priced
    final ZonedDateTime now = executionContext.getValuationClock().zonedDateTime();
    final FutureSecurity security = (FutureSecurity) target.getTrade().getSecurity();

    // Get reference price
    final HistoricalTimeSeriesBundle timeSeriesBundle = HistoricalTimeSeriesFunctionUtils.getHistoricalTimeSeriesInputs(executionContext, inputs);
    final LocalDateDoubleTimeSeries timeSeries = timeSeriesBundle.get(MarketDataRequirementNames.MARKET_VALUE, security.getExternalIdBundle()).getTimeSeries();
    if (timeSeries == null || timeSeries.isEmpty()) {
      throw new OpenGammaRuntimeException("Time Series is null or empty for " + security.getExternalIdBundle());
    }
    final Double lastMarginPrice = timeSeries.getLatestValue();
    if (lastMarginPrice == null) {
      throw new OpenGammaRuntimeException("Could not find latest value in time series.");
    }

    final CommodityFutureDefinition<?> defn = security.accept(CONVERTER);
    final CommodityFuture derivative = (CommodityFuture) defn.toDerivative(now, lastMarginPrice);

    if (derivative.getSettlement() < 0.0) { // Check to see whether it has already settled
      throw new OpenGammaRuntimeException("Future with expiry, " + security.getExpiry().getExpiry().toString() + ", has already settled.");
    }

    // 2. Build up the (simple) market data bundle
    final SimpleFutureDataBundle market = new SimpleFutureDataBundle(null, getMarketPrice(security, inputs), null, null, null);

    // 3. The Calculation - what we came here to do
    final Object results = computeValues(derivative, market);

    // 4. Create result specification to match the properties promised and return
    final ValueRequirement desiredValue = desiredValues.iterator().next();
    final ValueSpecification spec = new ValueSpecification(getValueRequirementName(), target.toSpecification(), createValueProperties(target, desiredValue, executionContext).get());
    return Collections.singleton(new ComputedValue(spec, results));
  }

  protected abstract <T extends CommodityFuture> Object computeValues(T derivative, SimpleFutureDataBundle market);

  @Override
  public boolean canApplyTo(FunctionCompilationContext context, ComputationTarget target) {
    if (target.getType() != ComputationTargetType.TRADE) {
      return false;
    }
    final Security security = target.getTrade().getSecurity();
    return security instanceof EnergyFutureSecurity
        || security instanceof MetalFutureSecurity
        || security instanceof AgricultureFutureSecurity;
  }

  @Override
  public Set<ValueSpecification> getResults(FunctionCompilationContext context, ComputationTarget target) {
    return Collections.singleton(new ValueSpecification(_valueRequirementName, target.toSpecification(), createValueProperties(target).get()));
  }

  private Builder createValueProperties(ComputationTarget target) {
    final Currency ccy = FinancialSecurityUtils.getCurrency(target.getTrade().getSecurity());
    final ValueProperties.Builder properties = createValueProperties()
      .with(ValuePropertyNames.CURRENCY, ccy.getCode());
    return properties;
  }

  protected Builder createValueProperties(ComputationTarget target, ValueRequirement desiredValue, FunctionExecutionContext executionContext) {
    return createValueProperties(target);
  }

  @Override
  public Set<ValueRequirement> getRequirements(FunctionCompilationContext context, ComputationTarget target, ValueRequirement desiredValue) {
    final Set<ValueRequirement> requirements = new HashSet<ValueRequirement>();
    final FutureSecurity security = (FutureSecurity) target.getTrade().getSecurity();
    // Live market price
    requirements.add(getMarketPriceRequirement(security));
    // Last day's closing price
    final ValueRequirement refPriceReq = getReferencePriceRequirement(context, security);
    if (refPriceReq == null) {
      return null;
    }
    requirements.add(refPriceReq);
    return requirements;
  }

  private ValueRequirement getMarketPriceRequirement(Security security) {
    return new ValueRequirement(MarketDataRequirementNames.MARKET_VALUE, ComputationTargetType.SECURITY, security.getUniqueId());
  }

  protected Double getMarketPrice(Security security, FunctionInputs inputs) {
    ValueRequirement marketPriceRequirement = getMarketPriceRequirement(security);
    final Object marketPriceObject = inputs.getValue(marketPriceRequirement);
    if (marketPriceObject == null) {
      throw new OpenGammaRuntimeException("Could not get " + marketPriceRequirement);
    }
    return (Double) marketPriceObject;
  }

  private ValueRequirement getReferencePriceRequirement(final FunctionCompilationContext context, final FutureSecurity security) {
    final HistoricalTimeSeriesResolver resolver = OpenGammaCompilationContext.getHistoricalTimeSeriesResolver(context);
    ExternalIdBundle idBundle = security.getExternalIdBundle();
    final HistoricalTimeSeriesResolutionResult timeSeries = resolver.resolve(security.getExternalIdBundle(), null, null, null, MarketDataRequirementNames.MARKET_VALUE, null);
    if (timeSeries == null) {
      s_logger.warn("Failed to find time series for: " + idBundle.toString());
      return null;
    }
    return HistoricalTimeSeriesFunctionUtils.createHTSRequirement(timeSeries, MarketDataRequirementNames.MARKET_VALUE,
        DateConstraint.VALUATION_TIME.minus(Period.ofDays(7)), true, DateConstraint.VALUATION_TIME, true);
  }

  @Override
  public ComputationTargetType getTargetType() {
    return ComputationTargetType.TRADE;
  }

  private String getValueRequirementName() {
    return _valueRequirementName;
  }

  private static final Logger s_logger = LoggerFactory.getLogger(SimpleFutureFunction.class);
}
