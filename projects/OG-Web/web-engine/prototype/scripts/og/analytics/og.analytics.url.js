/*
 * Copyright 2012 - present by OpenGamma Inc. and the OpenGamma group of companies
 * Please see distribution for license.
 */
$.register_module({
    name: 'og.analytics.url',
    dependencies: ['og.common.routes', 'og.api.rest'],
    obj: function () {
        var url, last_fingerprint = {}, last_object = {}, routes = og.common.routes,
            panels = ['south', 'dock-north', 'dock-center', 'dock-south'];
        var go = function () {
            og.api.rest.compressor.put({content: last_object}).pipe(function (result) {
                routes.go(routes.hash(og.views.analytics2.rules.load_item, {data: result.data.data}));
            });
        };
        return url = {
            add: function (container, params) {
                return (last_object[container] || (last_object[container] = [])).push(params), go(), url;
            },
            last: last_object,
            main: function (params) {(last_object.main = params), go(), url;},
            process: function (args) {
                og.api.rest.compressor.get({content: args.data})
                    .pipe(function (result) {
                        var config = result.data.data, current_main, panel, cellmenu;
                        for (panel in last_object) delete last_object[panel];
                        if (config.main && last_fingerprint.main !== (current_main = JSON.stringify(config.main)))
                            og.analytics.grid = new og.analytics.Grid({
                                selector: '.OG-layout-analytics-center', cellmenu: true,
                                source: last_object.main = JSON.parse(last_fingerprint.main = current_main)
                            });
                        panels.forEach(function (panel) {
                            var gadgets = config[panel];
                            if (!gadgets) return;
                            if (!last_fingerprint[panel]) last_fingerprint[panel] = [];
                            if (!last_object[panel]) last_object[panel] = [];
                            last_fingerprint[panel] = gadgets.map(function (gadget, index) {
                                var current_gadget = JSON.stringify(gadget);
                                last_object[panel][index] = JSON.parse(current_gadget);
                                if (last_fingerprint[panel][index] === current_gadget) return current_gadget;
                                og.analytics.containers[panel].add([gadget], index);
                                return current_gadget;
                            });
                        });
                    });
                return url;
            },
            remove: function (container, index) {
                if (!last_fingerprint[container] || !last_fingerprint[container].length) return;
                last_fingerprint[container].splice(index, 1);
                last_object[container].splice(index, 1);
                if (!last_fingerprint[container].length) delete last_fingerprint[container];
                if (!last_object[container].length) delete last_object[container];
                return go(), url;
            }
        };
    }
});