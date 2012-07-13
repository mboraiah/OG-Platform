/**
 * Copyright (C) 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 *
 * Please see distribution for license.
 */
package com.opengamma.web.server.push.analytics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.opengamma.DataNotFoundException;
import com.opengamma.engine.ComputationTargetResolver;
import com.opengamma.engine.value.ValueSpecification;
import com.opengamma.engine.view.InMemoryViewComputationResultModel;
import com.opengamma.engine.view.ViewResultModel;
import com.opengamma.engine.view.calc.ViewCycle;
import com.opengamma.engine.view.compilation.CompiledViewDefinition;
import com.opengamma.util.ArgumentChecker;
import com.opengamma.util.tuple.Pair;

/**
 *
 */
/* package */ class MainAnalyticsGrid extends AnalyticsGrid<MainGridViewport> {

  private final AnalyticsView.GridType _gridType;
  private final Map<String, DependencyGraphGrid> _depGraphs = new HashMap<String, DependencyGraphGrid>();
  private final MainGridStructure _gridStructure;
  private final ComputationTargetResolver _targetResolver;

  private ViewResultModel _latestResults = new InMemoryViewComputationResultModel();
  private AnalyticsHistory _history = new AnalyticsHistory();
  private ViewCycle _cycle = EmptyViewCycle.INSTANCE;

  /* package */ MainAnalyticsGrid(AnalyticsView.GridType gridType,
                                  MainGridStructure gridStructure,
                                  String gridId,
                                  ComputationTargetResolver targetResolver) {
    super(gridId);
    ArgumentChecker.notNull(gridType, "gridType");
    ArgumentChecker.notNull(gridStructure, "gridStructure");
    ArgumentChecker.notNull(targetResolver, "targetResolver");
    _gridType = gridType;
    _gridStructure = gridStructure;
    _targetResolver = targetResolver;
  }

  /* package */ static MainAnalyticsGrid emptyPortfolio(String gridId, ComputationTargetResolver targetResolver) {
    return new MainAnalyticsGrid(AnalyticsView.GridType.PORTFORLIO, PortfolioGridStructure.empty(), gridId, targetResolver);
  }

  /* package */ static MainAnalyticsGrid emptyPrimitives(String gridId, ComputationTargetResolver targetResolver) {
    return new MainAnalyticsGrid(AnalyticsView.GridType.PRIMITIVES, PrimitivesGridStructure.empty(), gridId, targetResolver);
  }

  /* package */ static MainAnalyticsGrid portfolio(CompiledViewDefinition compiledViewDef,
                                                   String gridId,
                                                   ComputationTargetResolver targetResolver) {
    MainGridStructure gridStructure = new PortfolioGridStructure(compiledViewDef);
    return new MainAnalyticsGrid(AnalyticsView.GridType.PORTFORLIO, gridStructure, gridId, targetResolver);
  }

  /* package */ static MainAnalyticsGrid primitives(CompiledViewDefinition compiledViewDef,
                                                    String gridId,
                                                    ComputationTargetResolver targetResolver) {
    MainGridStructure gridStructure = new PrimitivesGridStructure(compiledViewDef);
    return new MainAnalyticsGrid(AnalyticsView.GridType.PRIMITIVES, gridStructure, gridId, targetResolver);
  }

  // -------- dependency graph grids --------

  private DependencyGraphGrid getDependencyGraph(String graphId) {
    DependencyGraphGrid grid = _depGraphs.get(graphId);
    if (grid == null) {
      throw new DataNotFoundException("No dependency graph found with ID " + graphId + " for " + _gridType + " grid");
    }
    return grid;
  }

  // TODO a better way to specify which cell we want - target spec? stable row ID generated on the server?
  /* package */ void openDependencyGraph(String graphId,
                                         String gridId,
                                         int row,
                                         int col,
                                         CompiledViewDefinition compiledViewDef) {
    if (_depGraphs.containsKey(graphId)) {
      throw new IllegalArgumentException("Dependency graph ID " + graphId + " is already in use");
    }
    Pair<ValueSpecification, String> targetForCell = _gridStructure.getTargetForCell(row, col);
    if (targetForCell == null) {
      throw new DataNotFoundException("No dependency graph is available for row " + row + ", col " + col);
    }
    ValueSpecification valueSpec = targetForCell.getFirst();
    String calcConfigName = targetForCell.getSecond();
    DependencyGraphGrid grid =
        DependencyGraphGrid.create(compiledViewDef, valueSpec, calcConfigName, _cycle, _history, gridId, _targetResolver);
    _depGraphs.put(graphId, grid);
  }

  /* package */ void updateViewport(String viewportId, ViewportSpecification viewportSpecification) {
    getViewport(viewportId).update(viewportSpecification, _latestResults, _history);
  }

  /* package */ void updateResults(ViewResultModel results, AnalyticsHistory history, ViewCycle cycle) {
    _latestResults = results;
    _history = history;
    _cycle = cycle;
    for (MainGridViewport viewport : _viewports.values()) {
      viewport.updateResults(results, history);
    }
    for (DependencyGraphGrid grid : _depGraphs.values()) {
      grid.updateResults(cycle, history);
    }
  }

  /* package */ void closeDependencyGraph(String graphId) {
    AnalyticsGrid grid = _depGraphs.remove(graphId);
    if (grid == null) {
      throw new DataNotFoundException("No dependency graph found with ID " + graphId + " for " + _gridType + " grid");
    }
  }

  /* package */ DependencyGraphGridStructure getGridStructure(String graphId) {
    return getDependencyGraph(graphId).getGridStructure();
  }

  /* package */ void createViewport(String graphId,
                                    String viewportId,
                                    String dataId,
                                    ViewportSpecification viewportSpecification) {
    getDependencyGraph(graphId).createViewport(viewportId, dataId, viewportSpecification);
  }

  /* package */ void updateViewport(String graphId,
                                    String viewportId,
                                    ViewportSpecification viewportSpec) {
    getDependencyGraph(graphId).updateViewport(viewportId, viewportSpec, _cycle, _history);
  }

  /* package */ void deleteViewport(String graphId, String viewportId) {
    getDependencyGraph(graphId).deleteViewport(viewportId);
  }

  /* package */ ViewportResults getData(String graphId, String viewportId) {
    return getDependencyGraph(graphId).getData(viewportId);
  }

  /* package */ List<String> getDependencyGraphGridIds() {
    List<String> gridIds = new ArrayList<String>();
    for (AnalyticsGrid grid : _depGraphs.values()) {
      gridIds.add(grid.getGridId());
    }
    return gridIds;
  }

  /* package */ List<String> getDependencyGraphViewportDataIds() {
    List<String> dataIds = new ArrayList<String>();
    for (DependencyGraphGrid grid : _depGraphs.values()) {
      dataIds.addAll(grid.getViewportDataIds());
    }
    return dataIds;
  }

  @Override
  public GridStructure getGridStructure() {
    return _gridStructure;
  }

  @Override
  protected MainGridViewport createViewport(ViewportSpecification viewportSpecification, String dataId) {
    return new MainGridViewport(viewportSpecification, _gridStructure, dataId, _latestResults, _history);
  }
}