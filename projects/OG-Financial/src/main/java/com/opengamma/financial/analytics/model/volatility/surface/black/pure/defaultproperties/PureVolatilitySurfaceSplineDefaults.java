/**
 * Copyright (C) 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.financial.analytics.model.volatility.surface.black.pure.defaultproperties;

import java.util.Collections;
import java.util.Set;

import com.opengamma.engine.ComputationTarget;
import com.opengamma.engine.function.FunctionCompilationContext;
import com.opengamma.engine.value.ValueRequirement;
import com.opengamma.engine.value.ValueRequirementNames;
import com.opengamma.financial.analytics.model.volatility.surface.black.BlackVolatilitySurfacePropertyNamesAndValues;
import com.opengamma.util.ArgumentChecker;


/**
 *
 */
public class PureVolatilitySurfaceSplineDefaults extends PureVolatilitySurfaceDefaults {
  private final String _yInterpolator;
  private final String _yLeftExtrapolator;
  private final String _yRightExtrapolator;
  private final String _splineExtrapolatorFailBehaviour;

  public PureVolatilitySurfaceSplineDefaults(final String timeAxis, final String yAxis, final String volatilityTransform, final String timeInterpolator,
      final String timeLeftExtrapolator, final String timeRightExtrapolator, final String discountingCurveName, final String discountingCurveCalculationConfig, final String surfaceName,
      final String curveCurrency, final String yInterpolator, final String yLeftExtrapolator, final String yRightExtrapolator, final String splineExtrapolatorFailureBehaviour) {
    super(timeAxis, yAxis, volatilityTransform, timeInterpolator, timeLeftExtrapolator, timeRightExtrapolator, discountingCurveName, discountingCurveCalculationConfig, surfaceName,
        curveCurrency);
    ArgumentChecker.notNull(yInterpolator, "y interpolator");
    ArgumentChecker.notNull(yLeftExtrapolator, "y left extrapolator");
    ArgumentChecker.notNull(yRightExtrapolator, "y right extrapolator");
    ArgumentChecker.notNull(splineExtrapolatorFailureBehaviour, "spline extrapolator failure behaviour not set");
    _yInterpolator = yInterpolator;
    _yLeftExtrapolator = yLeftExtrapolator;
    _yRightExtrapolator = yRightExtrapolator;
    _splineExtrapolatorFailBehaviour = splineExtrapolatorFailureBehaviour;
  }

  @Override
  protected void getDefaults(final PropertyDefaults defaults) {
    super.getDefaults(defaults);
    defaults.addValuePropertyName(ValueRequirementNames.PURE_VOLATILITY_SURFACE, BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_INTERPOLATOR);
    defaults.addValuePropertyName(ValueRequirementNames.PURE_VOLATILITY_SURFACE, BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_LEFT_EXTRAPOLATOR);
    defaults.addValuePropertyName(ValueRequirementNames.PURE_VOLATILITY_SURFACE, BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_RIGHT_EXTRAPOLATOR);
    defaults.addValuePropertyName(ValueRequirementNames.PURE_VOLATILITY_SURFACE, BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_EXTRAPOLATOR_FAILURE);
  }

  @Override
  protected Set<String> getDefaultValue(final FunctionCompilationContext context, final ComputationTarget target, final ValueRequirement desiredValue, final String propertyName) {
    final Set<String> surfaceDefaults = super.getDefaultValue(context, target, desiredValue, propertyName);
    if (surfaceDefaults != null) {
      return surfaceDefaults;
    }
    if (BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_INTERPOLATOR.equals(propertyName)) {
      return Collections.singleton(_yInterpolator);
    }
    if (BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_LEFT_EXTRAPOLATOR.equals(propertyName)) {
      return Collections.singleton(_yLeftExtrapolator);
    }
    if (BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_RIGHT_EXTRAPOLATOR.equals(propertyName)) {
      return Collections.singleton(_yRightExtrapolator);
    }
    if (BlackVolatilitySurfacePropertyNamesAndValues.PROPERTY_SPLINE_EXTRAPOLATOR_FAILURE.equals(propertyName)) {
      return Collections.singleton(_splineExtrapolatorFailBehaviour);
    }
    return null;
  }

}
