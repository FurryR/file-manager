(function () {
  'use strict';

  var TOKEN = '__US_TOKEN__';
  if (!TOKEN || TOKEN === '__US_TOKEN__') return;
  if (window.top !== window) return;
  var protocol = location.protocol;
  if (protocol !== 'http:' && protocol !== 'https:') return;

  var bridge = window.androidUserScript;
  if (!bridge) return;

  var guard = '__fileUserScriptLoaded';
  if (window[guard]) return;
  window[guard] = true;

  var pending = Object.create(null);
  var sequence = 1;

  function logError(script, error) {
    var message = error && error.stack ? error.stack : String(error);
    try { bridge.log(TOKEN, script && script.id ? script.id : '', message); } catch (ignored) { }
  }

  function headerValue(raw, name) {
    var wanted = String(name || '').toLowerCase();
    var lines = String(raw || '').split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var separator = line.indexOf(':');
      if (separator <= 0) continue;
      if (line.slice(0, separator).trim().toLowerCase() === wanted) {
        return line.slice(separator + 1).trim();
      }
    }
    return null;
  }

  function decodeBase64(value) {
    var text = atob(String(value || ''));
    var buffer = new ArrayBuffer(text.length);
    var bytes = new Uint8Array(buffer);
    for (var i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
    return buffer;
  }

  window.__fileUserScriptDispatch = function (payloadJson) {
    var response;
    try { response = JSON.parse(payloadJson); } catch (ignored) { response = null; }
    if (!response) return;
    var requestId = response.id;
    var entry = pending[requestId];
    if (!entry) return;
    delete pending[requestId];
    if (!response.ok) {
      if (entry.details.onerror) {
        try {
          entry.details.onerror({
            readyState: 4,
            status: 0,
            statusText: '',
            error: response.error || 'request failed',
            finalUrl: entry.details.url
          });
        } catch (error) { logError(entry.script, error); }
      }
      return;
    }
    var text = response.text || '';
    var headers = response.headers || '';
    var result = {
      readyState: 4,
      status: response.status || 0,
      statusText: response.statusText || '',
      responseHeaders: headers,
      finalUrl: response.finalUrl || entry.details.url,
      responseText: text,
      getResponseHeader: function (name) { return headerValue(headers, name); }
    };
    var type = String(entry.type || '').toLowerCase();
    if (type === 'arraybuffer' && response.base64) {
      result.response = decodeBase64(response.base64);
    } else if (type === 'json') {
      try { result.response = JSON.parse(text); } catch (ignored) { result.response = null; }
    } else {
      result.response = text;
    }
    if (entry.details.onload) {
      try { entry.details.onload(result); } catch (error) { logError(entry.script, error); }
    }
  };

  function readValue(scriptId, key, fallback) {
    var encoded = 'null';
    try { if (fallback !== undefined) encoded = JSON.stringify(fallback); } catch (ignored) { }
    var raw;
    try { raw = bridge.getValue(TOKEN, scriptId, String(key), encoded); }
    catch (ignored) { return fallback; }
    try {
      var parsed = JSON.parse(raw);
      if (parsed === null && fallback === undefined) return undefined;
      return parsed;
    } catch (ignored) {
      return fallback;
    }
  }

  function writeValue(scriptId, key, value) {
    var encoded = 'null';
    try { if (value !== undefined) encoded = JSON.stringify(value); } catch (ignored) { }
    bridge.setValue(TOKEN, scriptId, String(key), encoded);
  }

  function addStyle(css) {
    var style = document.createElement('style');
    style.textContent = String(css);
    var root = document.head || document.documentElement;
    if (root) root.appendChild(style);
    return style;
  }

  function addElement(tag, attributes) {
    var element = document.createElement(String(tag));
    if (attributes && typeof attributes === 'object') {
      Object.keys(attributes).forEach(function (name) {
        if (name === 'textContent') element.textContent = String(attributes[name]);
        else if (name === 'style' && attributes[name] && typeof attributes[name] === 'object') {
          Object.keys(attributes[name]).forEach(function (property) {
            element.style[property] = attributes[name][property];
          });
        } else {
          element.setAttribute(name, String(attributes[name]));
        }
      });
    }
    var root = document.head || document.documentElement;
    if (root) root.appendChild(element);
    return element;
  }

  function xmlHttpRequest(script, details) {
    var id = sequence++;
    pending[id] = {
      script: script,
      details: details || {},
      type: String((details && details.responseType) || 'text')
    };
    var payload = {
      id: id,
      page: location.href,
      method: (details && details.method) || 'GET',
      url: (details && details.url) || '',
      headers: (details && details.headers) || null,
      data: details && details.data !== undefined && details.data !== null
        ? String(details.data)
        : null,
      timeout: (details && details.timeout) || 0,
      responseType: String((details && details.responseType) || 'text')
    };
    try {
      bridge.xhr(TOKEN, script.id, JSON.stringify(payload));
    } catch (error) {
      delete pending[id];
      logError(script, error);
      if (details && details.onerror) {
        try { details.onerror({ readyState: 4, status: 0, statusText: '', error: String(error) }); }
        catch (ignored) { }
      }
    }
    return {
      abort: function () {
        delete pending[id];
        try { bridge.abort(TOKEN, id); } catch (ignored) { }
        if (details && details.onabort) {
          try { details.onabort({ readyState: 4, status: 0, statusText: '', finalUrl: payload.url }); }
          catch (ignored) { }
        }
      }
    };
  }

  function buildApi(script) {
    var api = {};
    var id = script.id;
    api.GM_info = script.info || {};
    if (script.grantsNone) {
      api.GM = api;
      return api;
    }
    api.GM_getValue = function (key, fallback) { return readValue(id, key, fallback); };
    api.GM_setValue = function (key, value) { writeValue(id, key, value); };
    api.GM_deleteValue = function (key) {
      try { bridge.deleteValue(TOKEN, id, String(key)); } catch (ignored) { }
    };
    api.GM_listValues = function () {
      var raw = '{}';
      try { raw = bridge.listValues(TOKEN, id); } catch (ignored) { }
      var values;
      try { values = JSON.parse(raw); } catch (ignored) { values = {}; }
      return Object.keys(values);
    };
    api.GM_addStyle = addStyle;
    api.GM_addElement = addElement;
    api.GM_log = function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) parts.push(String(arguments[i]));
      try { bridge.log(TOKEN, id, parts.join(' ')); } catch (ignored) { }
    };
    api.GM_setClipboard = function (value) {
      var text = value && typeof value === 'object' ? value.text : value;
      try { bridge.clipboard(TOKEN, String(text == null ? '' : text)); } catch (ignored) { }
    };
    api.GM_notification = function (details, title) {
      var text = details;
      var heading = title || '';
      if (details && typeof details === 'object') {
        text = details.text;
        heading = details.title || heading;
      }
      try { bridge.notify(TOKEN, String(heading || ''), String(text == null ? '' : text)); }
      catch (ignored) { }
    };
    api.GM_openInTab = function (url) {
      try { bridge.openUrl(TOKEN, String(url)); } catch (ignored) { }
      return { close: function () { } };
    };
    api.GM_xmlhttpRequest = function (details) { return xmlHttpRequest(script, details); };
    api.GM_getResourceText = function (name) {
      try { return bridge.resourceText(TOKEN, id, String(name)); } catch (ignored) { return null; }
    };
    api.GM_getResourceURL = function (name) {
      try { return bridge.resourceUrl(TOKEN, id, String(name)); } catch (ignored) { return null; }
    };
    // No renderer menu exists for scripts; accept the registration calls so a
    // normal user script does not crash before its first line runs.
    api.GM_registerMenuCommand = function () { return 0; };
    api.GM_unregisterMenuCommand = function () { };
    api.GM = api;
    return api;
  }

  function execute(script) {
    if (!script || typeof script.code !== 'string') return;
    var api = buildApi(script);
    var names = Object.keys(api).filter(function (name) { return name !== 'unsafeWindow'; });
    var values = names.map(function (name) { return api[name]; });
    var source = '';
    if (script.requires && script.requires.length) {
      source += script.requires.join('\n;\n') + '\n;\n';
    }
    source += script.code;
    var fn;
    try {
      fn = Function.apply(null, names.concat(['unsafeWindow'], source));
    } catch (error) {
      logError(script, error);
      return;
    }
    try {
      fn.apply(window, values.concat([window]));
    } catch (error) {
      logError(script, error);
    }
  }

  function schedule(script) {
    var runAt = script.runAt || 'document-idle';
    if (runAt === 'document-start') {
      execute(script);
      return;
    }
    if (runAt === 'document-end') {
      if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () { execute(script); }, { once: true });
      } else {
        execute(script);
      }
      return;
    }
    if (document.readyState === 'complete') {
      execute(script);
    } else {
      window.addEventListener('load', function () { execute(script); }, { once: true });
    }
  }

  var response;
  try {
    response = JSON.parse(bridge.getScripts(location.href, TOKEN));
  } catch (error) {
    logError(null, error);
    return;
  }
  var scripts = (response && response.scripts) || [];
  for (var i = 0; i < scripts.length; i++) {
    schedule(scripts[i]);
  }
})();
