window._fetch = window.fetch;
window.fetch = function (input, init = {}) {
    if (input === '/graphql' && init.method === 'POST' && init.body) {
        init.headers['Body-Digest'] = window._postInterceptor.register(init.body);
    }
    return window._fetch(input, init);
};
