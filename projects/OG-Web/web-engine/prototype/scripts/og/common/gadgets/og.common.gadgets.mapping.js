/*
 * Copyright 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 * Please see distribution for license.
 */
$.register_module({
    name: 'og.common.gadgets.mapping',
    dependencies: [],
    obj: function () {
        var module = this, mapping, gadget_names = {
            'Curve': 'Curve',
            'Data': 'Data',
            'Depgraph': 'Dependency Graph',
            'Surface': 'Surface',
            'Timeseries': 'Time Series'
        };
        return mapping = {
            gadgets: ['Depgraph', 'Data', 'Surface', 'Curve', 'Timeseries'],
            panel_preference: {
                'south'      : [0, 2, 4, 3, 1],
                'dock-north' : [2, 4, 3, 1, 0],
                'dock-center': [2, 4, 3, 1, 0],
                'dock-south' : [2, 4, 3, 1, 0],
                'new-window' : [2, 4, 3, 1, 0]
            },
            options: function (cell, grid, panel) {
                var type = mapping.type(cell, panel), source = $.extend({}, grid.source), gadget_options = {
                    gadget: 'og.common.gadgets.' + type,
                    options: {source: source, child: true},
                    row_name: cell.row_name,
                    col_name: cell.col_name,
                    type: gadget_names[type]
                };
                if (type === 'Data') $.extend(gadget_options.options, {col: cell.col, row: cell.row});
                if (type === 'Depgraph') $.extend(source, {depgraph: true, col: cell.col, row: cell.row});
                if (type === 'Timeseries') gadget_options.options.datapoints_link = false;
                return gadget_options;
            },
            type : function (cell, panel) {
                var order = mapping.panel_preference[panel || 'new-window'],
                    type_map = mapping.type_map[cell.type], i, k;
                for (i = 0; i < order.length; i++) for (k = 0; k < type_map.length; k++) if (order[i] === type_map[k])
                    return mapping.gadgets[order[i]];
            },
            type_map: {
                CURVE             : [1, 3],
                DOUBLE            : [0],
                LABELLED_MATRIX_1D: [0, 1],
                LABELLED_MATRIX_2D: [0, 1, 2, 3],
                LABELLED_MATRIX_3D: [0, 1],
                PRIMITIVE         : [0],
                SURFACE_DATA      : [2, 1],
                TENOR             : [0],
                TIME_SERIES       : [4, 1],
                UNKNOWN           : [0]
            }
        }
    }
});