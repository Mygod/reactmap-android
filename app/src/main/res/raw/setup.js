Object.defineProperty(navigator, 'geolocation', {
    value: {
        _positionCallbacks: {
            count: 0,
        },
        getCurrentPosition: function (successCallback, errorCallback, options) {
            const i = this._positionCallbacks.count++;
            this._positionCallbacks[i] = {
                successCallback: successCallback,
                errorCallback: errorCallback,
                options: options,
            };
            window._glocation.getCurrentPosition(i);
        },
        _getCurrentPositionSuccess: function (ids, data) {
            ids.forEach(i => {
                const callbacks = this._positionCallbacks[i];
                delete this._positionCallbacks[i];
                callbacks.successCallback(data);
            })
        },
        _getCurrentPositionError: function (ids, data) {
            ids.forEach(i => {
                const callbacks = this._positionCallbacks[i];
                delete this._positionCallbacks[i];
                callbacks.errorCallback(data);
            })
        },
        watchPosition: function (successCallback, errorCallback, options) {
            const i = this._positionCallbacks.count++;
            this._positionCallbacks[i] = {
                successCallback: successCallback,
                errorCallback: errorCallback,
                options: options,
            };
            window._glocation.watchPosition(i);
            return i;
        },
        _watchPositionSuccess: function (ids, data) {
            ids.forEach(i => this._positionCallbacks[i].successCallback(data));
        },
        _watchPositionError: function (ids, data) {
            ids.forEach(i => this._positionCallbacks[i].errorCallback(data));
        },
        clearWatch: function (watchId) {
            window._glocation.clearWatch(watchId);
            delete this._positionCallbacks[watchId];
        },
    },
});
window._fetch = window.fetch;
window.fetch = function (input, init = {}) {
    if (input === '/graphql' && init.method === 'POST' && init.body) {
        init.headers['_interceptedBody'] = init.body;
    }
    return window._fetch(input, init);
};
