window._autoKeyboardCallback = {
    _setValue: Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set,
    _handler: function (e) {
        if (this._ignoreNextFocus && e instanceof FocusEvent) return delete this._ignoreNextFocus;
        if (!this._currentInput && e.target instanceof HTMLInputElement &&
            window._autoKeyboard.request(e.target.value, e.target.placeholder)) this._currentInput = e.target;
    },
    valueReady: function (value) {
        this._ignoreNextFocus = true;
        this._setValue.call(this._currentInput, value);
        this._currentInput.dispatchEvent(new Event('input', { bubbles: true }));
        delete this._currentInput;
    },
    dismiss: function () {
        delete this._currentInput;
    },
};
document.addEventListener('click', window._autoKeyboardCallback._handler.bind(window._autoKeyboardCallback));
document.addEventListener('focusin', window._autoKeyboardCallback._handler.bind(window._autoKeyboardCallback));
