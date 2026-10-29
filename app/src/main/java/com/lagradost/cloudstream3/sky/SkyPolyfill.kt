package com.lagradost.cloudstream3.sky

/**
 * The SkyStream host surface, ported from the verified S2 spike
 * (~/workspace/cloudstream/jvm-test/src/SkySpike.kt, where the real
 * 4KHD plugin ran end-to-end on this same engine):
 *
 * - console.* routed to the skyLog binding
 * - setTimeout/setInterval backed by the skySleepAsync binding
 * - model classes (MultimediaItem, Episode, StreamResult, Actor,
 *   Trailer, NextAiring) as plain Object.assign bags
 * - http_get / http_post / http_parallel -> {status, body, headers}
 *   via the skyHttpAsync binding, with the reference's {headers, body}
 *   second-argument overload normalised for http_post
 * - getPreference / setPreference via skyPrefGet / skyPrefSet
 * - Web-platform globals SkyStream's runtime provides (8.79):
 *   URL / URLSearchParams, atob / btoa, TextEncoder / TextDecoder,
 *   AbortController — real plugins die without them (katdrama-zoro
 *   needs URL, vegamovies needs btoa, cinestream needs TextEncoder)
 * - Native-assist parity (8.79): sendMessage() + nativeMd5 /
 *   nativeSha256 via the skyNativeSync binding, crypto.decryptAES /
 *   crypto.pbkdf2 via skyNativeAsync, and a pure-JS js_unpack —
 *   CastleTV's encrypted payloads decrypt through these
 * - __sky_call: the callback-envelope invoker. Exports are harvested
 *   via Reflect.get(globalThis, name) — direct property access on
 *   some plugins' exports throws, Reflect.get does not (spike
 *   learning). The envelope is completed through the skyComplete
 *   binding: {success:true, data} on callback/promise/return, or
 *   {success:false, errorCode, message} on failure.
 *
 * `manifest` is NOT part of the polyfill: the engine injects it as a
 * separate evaluate before plugin.js, because it differs per plugin
 * instance (effective baseUrl, providerId for sub-providers).
 */
object SkyPolyfill {

    const val CODE = """
(function () {
  function str(v) {
    if (v === null) return 'null';
    if (v === undefined) return 'undefined';
    if (typeof v === 'object') { try { return JSON.stringify(v); } catch (e) { return String(v); } }
    return String(v);
  }
  globalThis.console = {
    log: function () { skyLog(Array.prototype.map.call(arguments, str).join(' ')); },
    error: function () { skyLog('[err] ' + Array.prototype.map.call(arguments, str).join(' ')); },
    warn: function () { skyLog('[warn] ' + Array.prototype.map.call(arguments, str).join(' ')); },
    info: function () { skyLog(Array.prototype.map.call(arguments, str).join(' ')); }
  };
  var timers = {};
  var timerSeq = 0;
  globalThis.setTimeout = function (fn, ms) {
    var id = ++timerSeq;
    timers[id] = false;
    skySleepAsync(ms || 0).then(function () {
      if (!timers[id]) { try { fn(); } catch (e) { console.error('timer: ' + e); } }
    });
    return id;
  };
  globalThis.clearTimeout = function (id) { timers[id] = true; };
  globalThis.setInterval = function (fn, ms) {
    var id = ++timerSeq;
    timers[id] = false;
    function loop() {
      if (timers[id]) return;
      skySleepAsync(ms || 0).then(function () {
        if (timers[id]) return;
        try { fn(); } catch (e) { console.error('interval: ' + e); }
        loop();
      });
    }
    loop();
    return id;
  };
  globalThis.clearInterval = function (id) { timers[id] = true; };

  function bag(name) {
    globalThis[name] = class {
      constructor(p) { Object.assign(this, p || {}); }
    };
  }
  bag('MultimediaItem'); bag('Episode'); bag('StreamResult');
  bag('Actor'); bag('Trailer'); bag('NextAiring');

  function httpResult(json) { return JSON.parse(json); }
  globalThis.http_get = function (url, headers, cb) {
    var p = skyHttpAsync(JSON.stringify({ method: 'GET', url: url, headers: headers || {} })).then(httpResult);
    if (typeof cb === 'function') p.then(cb);
    return p;
  };
  globalThis.http_post = function (url, headers, body, cb) {
    if (body === undefined && headers && typeof headers === 'object' &&
        (headers.body !== undefined || headers.headers !== undefined)) {
      body = headers.body; headers = headers.headers;
    }
    if (typeof body === 'function' && cb === undefined) { cb = body; body = null; }
    var payload = { method: 'POST', url: url, headers: headers || {} };
    if (body !== null && body !== undefined) {
      payload.body = (typeof body === 'string') ? body : JSON.stringify(body);
    }
    var p = skyHttpAsync(JSON.stringify(payload)).then(httpResult);
    if (typeof cb === 'function') p.then(cb);
    return p;
  };
  globalThis.http_parallel = function (requests) {
    var jobs = (requests || []).map(function (r) {
      return skyHttpAsync(JSON.stringify({
        method: r.method || 'GET', url: r.url, headers: r.headers || {},
        body: (r.body === undefined ? null : r.body)
      })).then(httpResult).catch(function () { return { status: 0, body: '', headers: {} }; });
    });
    return Promise.all(jobs);
  };
  globalThis._fetch = function (url) { return globalThis.http_get(url, {}); };

  globalThis.getPreference = function (key) {
    var v = skyPrefGet(key);
    if (v === null || v === undefined) return null;
    try { return JSON.parse(v); } catch (e) { return v; }
  };
  globalThis.setPreference = function (key, value) {
    skyPrefSet(key, JSON.stringify(value === undefined ? null : value));
  };


  // ---- Web-platform globals SkyStream's runtime provides and
  // plugins rely on: URL/URLSearchParams, atob/btoa,
  // TextEncoder/TextDecoder, AbortController. (Added in the 8.79
  // round: katdrama-zoro needs URL, vegamovies needs btoa,
  // cinestream needs TextEncoder on its stream path.)
  function USP(init) {
    this._e = [];
    if (typeof init === 'string') {
      var q = init.charAt(0) === '?' ? init.slice(1) : init;
      if (q) {
        var self = this;
        q.split('&').forEach(function (pair) {
          if (!pair) return;
          var i = pair.indexOf('=');
          var k = i < 0 ? pair : pair.slice(0, i);
          var v = i < 0 ? '' : pair.slice(i + 1);
          self._e.push([decodeURIComponent(k.replace(/\+/g, ' ')), decodeURIComponent(v.replace(/\+/g, ' '))]);
        });
      }
    } else if (init && typeof init === 'object') {
      if (Array.isArray(init)) {
        for (var i = 0; i < init.length; i++) this._e.push([String(init[i][0]), String(init[i][1])]);
      } else {
        for (var k2 in init) this._e.push([k2, String(init[k2])]);
      }
    }
  }
  USP.prototype.append = function (k, v) { this._e.push([String(k), String(v)]); };
  USP.prototype.set = function (k, v) {
    k = String(k); v = String(v);
    var out = [], done = false;
    for (var i = 0; i < this._e.length; i++) {
      if (this._e[i][0] === k) { if (!done) { out.push([k, v]); done = true; } }
      else out.push(this._e[i]);
    }
    if (!done) out.push([k, v]);
    this._e = out;
  };
  USP.prototype.get = function (k) {
    for (var i = 0; i < this._e.length; i++) if (this._e[i][0] === String(k)) return this._e[i][1];
    return null;
  };
  USP.prototype.getAll = function (k) {
    var r = [];
    for (var i = 0; i < this._e.length; i++) if (this._e[i][0] === String(k)) r.push(this._e[i][1]);
    return r;
  };
  USP.prototype.has = function (k) { return this.get(k) !== null; };
  USP.prototype.delete = function (k) {
    this._e = this._e.filter(function (e) { return e[0] !== String(k); });
  };
  USP.prototype.forEach = function (fn) {
    for (var i = 0; i < this._e.length; i++) fn(this._e[i][1], this._e[i][0], this);
  };
  USP.prototype.entries = function () {
    var e = this._e, i = 0;
    return { next: function () { return i < e.length ? { value: e[i++], done: false } : { value: undefined, done: true }; } };
  };
  USP.prototype.toString = function () {
    return this._e.map(function (e) {
      return encodeURIComponent(e[0]).replace(/%20/g, '+') + '=' + encodeURIComponent(e[1]).replace(/%20/g, '+');
    }).join('&');
  };
  globalThis.URLSearchParams = USP;

  function parseUrlParts(href) {
    var m = /^((?:[a-zA-Z][a-zA-Z0-9+.-]*:)?\/\/)?([^/?#]*)((?:[^?#]*))(\?[^#]*)?(#.*)?$/.exec(href);
    if (!m) return null;
    var authority = m[2] || '';
    var host = authority, port = '';
    var ci = authority.lastIndexOf(':');
    if (ci > 0 && /^[0-9]+$/.test(authority.slice(ci + 1))) {
      host = authority.slice(0, ci); port = authority.slice(ci + 1);
    }
    var ui = host.lastIndexOf('@');
    var hostname = ui >= 0 ? host.slice(ui + 1) : host;
    return {
      scheme: (m[1] || '').replace(/\/\/$/, ''),
      authority: authority, hostname: hostname, port: port,
      path: m[3] || '', search: m[4] || '', hash: m[5] || ''
    };
  }
  function normalizePath(path) {
    var abs = path.charAt(0) === '/';
    var parts = path.split('/');
    var out = [];
    for (var i = 0; i < parts.length; i++) {
      var seg = parts[i];
      if (seg === '.') continue;
      if (seg === '..') { if (out.length && out[out.length - 1] !== '') out.pop(); continue; }
      out.push(seg);
    }
    var joined = out.join('/');
    if (abs && joined.charAt(0) !== '/') joined = '/' + joined;
    return joined === '' && abs ? '/' : joined;
  }
  function SkyURL(url, base) {
    var href = String(url);
    var parts;
    if (/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(href) || href.indexOf('//') === 0) {
      if (href.indexOf('//') === 0) {
        var bs = base ? parseUrlParts(String(base)) : null;
        href = (bs ? bs.scheme : 'https:') + href;
      }
      parts = parseUrlParts(href);
    } else if (base !== undefined && base !== null) {
      var b = new SkyURL(String(base));
      var bp = parseUrlParts(b.href);
      var rel = href;
      var hash = '', search = '';
      var hi = rel.indexOf('#'); if (hi >= 0) { hash = rel.slice(hi); rel = rel.slice(0, hi); }
      var qi = rel.indexOf('?'); if (qi >= 0) { search = rel.slice(qi); rel = rel.slice(0, qi); }
      var newPath;
      if (rel === '') newPath = bp.path;
      else if (rel.charAt(0) === '/') newPath = normalizePath(rel);
      else {
        var dir = bp.path.slice(0, bp.path.lastIndexOf('/') + 1);
        newPath = normalizePath(dir + rel);
      }
      if (rel === '' && !search) search = bp.search;
      parts = { scheme: bp.scheme, authority: bp.authority, hostname: bp.hostname, port: bp.port, path: newPath, search: search, hash: hash };
      href = parts.scheme + '//' + parts.authority + parts.path + parts.search + parts.hash;
      parts = parseUrlParts(href) || parts;
    } else {
      parts = parseUrlParts(href);
    }
    if (!parts) throw new TypeError('Invalid URL: ' + href);
    this._parts = parts;
    var self = this;
    this._usp = new USP(parts.search);
    Object.defineProperty(this, 'searchParams', { get: function () { return self._usp; } });
    this._sync();
  }
  SkyURL.prototype._sync = function () {
    var p = this._parts;
    var qs = this._usp.toString();
    p.search = qs ? '?' + qs : '';
    this.protocol = p.scheme;
    this.hostname = p.hostname;
    this.port = p.port;
    this.host = p.port ? p.hostname + ':' + p.port : p.hostname;
    this.pathname = p.path || '/';
    this.hash = p.hash;
    this.origin = p.scheme ? (p.scheme.replace(/:$/, '') + '://' + this.host) : 'null';
    this.href = (p.scheme ? p.scheme + '//' + p.authority : '') + this.pathname + this.search + this.hash;
  };
  ['search'].forEach(function (prop) {
    Object.defineProperty(SkyURL.prototype, prop, {
      get: function () { return this._parts.search; },
      set: function (v) {
        v = String(v || '');
        this._usp = new USP(v);
        this._parts.search = v && v.charAt(0) !== '?' ? '?' + v : v;
        this._sync();
      }
    });
  });
  SkyURL.prototype.toString = function () { this._sync(); return this.href; };
  SkyURL.prototype.toJSON = function () { return this.toString(); };
  globalThis.URL = SkyURL;

  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  globalThis.btoa = function (s) {
    s = String(s);
    var out = '';
    for (var i = 0; i < s.length; i += 3) {
      var a = s.charCodeAt(i) & 0xff;
      var b2 = i + 1 < s.length ? s.charCodeAt(i + 1) & 0xff : 0;
      var c = i + 2 < s.length ? s.charCodeAt(i + 2) & 0xff : 0;
      if (a > 0xff || b2 > 0xff || c > 0xff) throw new Error('InvalidCharacterError');
      var n = (a << 16) | (b2 << 8) | c;
      out += B64.charAt((n >> 18) & 63) + B64.charAt((n >> 12) & 63) +
        (i + 1 < s.length ? B64.charAt((n >> 6) & 63) : '=') +
        (i + 2 < s.length ? B64.charAt(n & 63) : '=');
    }
    return out;
  };
  globalThis.atob = function (s) {
    s = String(s).replace(/[\s]/g, '').replace(/-/g, '+').replace(/_/g, '/');
    while (s.length % 4) s += '=';
    var out = '';
    for (var i = 0; i < s.length; i += 4) {
      var n = 0, pad = 0;
      for (var j = 0; j < 4; j++) {
        var ch = s.charAt(i + j);
        var v = ch === '=' ? 0 : B64.indexOf(ch);
        if (v < 0) v = 0;
        if (ch === '=') pad++;
        n = (n << 6) | v;
      }
      out += String.fromCharCode((n >> 16) & 0xff);
      if (pad < 2) out += String.fromCharCode((n >> 8) & 0xff);
      if (pad < 1) out += String.fromCharCode(n & 0xff);
    }
    return out;
  };

  globalThis.TextEncoder = class {
    encode(str) {
      str = String(str === undefined ? '' : str);
      var bytes = [];
      for (var i = 0; i < str.length; i++) {
        var cp = str.codePointAt(i);
        if (cp > 0xffff) i++;
        if (cp < 0x80) bytes.push(cp);
        else if (cp < 0x800) bytes.push(0xc0 | (cp >> 6), 0x80 | (cp & 63));
        else if (cp < 0x10000) bytes.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
        else bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 63), 0x80 | ((cp >> 6) & 63), 0x80 | (cp & 63));
      }
      return new Uint8Array(bytes);
    }
  };
  globalThis.TextDecoder = class {
    constructor(label) { this.encoding = (label || 'utf-8').toLowerCase(); }
    decode(input) {
      if (!input) return '';
      var bytes = input instanceof Uint8Array ? input : new Uint8Array(input.buffer || input);
      var out = '', i = 0;
      while (i < bytes.length) {
        var b = bytes[i];
        var cp, len;
        if (b < 0x80) { cp = b; len = 1; }
        else if ((b & 0xe0) === 0xc0) { cp = b & 0x1f; len = 2; }
        else if ((b & 0xf0) === 0xe0) { cp = b & 0x0f; len = 3; }
        else if ((b & 0xf8) === 0xf0) { cp = b & 0x07; len = 4; }
        else { cp = 0xfffd; len = 1; }
        if (len > 1) {
          for (var j = 1; j < len && i + j < bytes.length; j++) cp = (cp << 6) | (bytes[i + j] & 0x3f);
        }
        out += String.fromCodePoint(cp);
        i += len;
      }
      return out;
    }
  };

  function SkyAbortSignal() { this.aborted = false; this._ls = []; }
  SkyAbortSignal.prototype.addEventListener = function (t, f) { if (t === 'abort') this._ls.push(f); };
  SkyAbortSignal.prototype.removeEventListener = function (t, f) {
    this._ls = this._ls.filter(function (x) { return x !== f; });
  };
  SkyAbortSignal.prototype.throwIfAborted = function () { if (this.aborted) throw new Error('AbortError'); };
  globalThis.AbortSignal = SkyAbortSignal;
  globalThis.AbortController = class {
    constructor() { this.signal = new SkyAbortSignal(); }
    abort() {
      this.signal.aborted = true;
      var ls = this.signal._ls.slice();
      for (var i = 0; i < ls.length; i++) { try { ls[i](); } catch (e) {} }
    }
  };


  // ---- Native-assist surface (SkyStream parity, 8.79):
  // sendMessage(channel, json) for the sync channels, crypto.* for
  // the async ones. js_unpack is implemented in pure JS below.
  globalThis.sendMessage = function (channel, payload) {
    var r = skyNativeSync(String(channel), typeof payload === 'string' ? payload : JSON.stringify(payload));
    return (r === undefined || r === null) ? null : r;
  };
  globalThis.nativeMd5 = function (input) { return sendMessage('crypto_md5', JSON.stringify(String(input))); };
  globalThis.nativeSha256 = function (input) { return sendMessage('crypto_sha256', JSON.stringify(String(input))); };
  globalThis.crypto = {
    decryptAES: function (data, key, iv, options) {
      return skyNativeAsync('crypto_decrypt_aes', JSON.stringify({
        data: data, key: key, iv: iv, mode: (options && options.mode) || 'cbc'
      }));
    },
    pbkdf2: function (password, salt, iterations, keyLength) {
      return skyNativeAsync('crypto_pbkdf2', JSON.stringify({
        password: password, salt: salt,
        iterations: iterations || 10000, keyLength: keyLength || 32
      }));
    }
  };
  globalThis.js_unpack = function (js) {
    try {
      var m = /}\('(.*)',(\d+),(\d+),'(.*)'\.split\('\|'\)/.exec(String(js));
      if (!m) return js;
      var payload = m[1].replace(/\\'/g, "'").replace(/\\\\/g, '\\');
      var radix = parseInt(m[2], 10), count = parseInt(m[3], 10);
      var dict = m[4].split('|');
      function sym(c) {
        return (c < radix ? '' : sym(Math.floor(c / radix))) +
          ((c % radix) > 35 ? String.fromCharCode((c % radix) + 29) : (c % radix).toString(36));
      }
      var map = {};
      for (var i = 0; i < count; i++) map[sym(i)] = dict[i] || sym(i);
      return payload.replace(/\b\w+\b/g, function (w) { return map[w] || w; });
    } catch (e) { return js; }
  };

  globalThis.__sky_call = function (name, argsJson) {
    var args = JSON.parse(argsJson);
    var done = false;
    function finish(json) { if (!done) { done = true; skyComplete(json); } }
    function cb(res) {
      try { finish(JSON.stringify(res)); }
      catch (e) { finish(JSON.stringify({ success: false, errorCode: 'SERIALIZE', message: String(e) })); }
    }
    try {
      var fn = Reflect.get(globalThis, name);
      if (typeof fn !== 'function') {
        finish(JSON.stringify({ success: false, errorCode: 'NO_EXPORT', message: name + ' is not exported' }));
        return;
      }
      var ret = fn.apply(null, args.concat([cb]));
      if (ret && typeof ret.then === 'function') {
        ret.then(function (v) {
          if (v !== undefined) cb({ success: true, data: v, via: 'promise' });
        }, function (e) {
          cb({ success: false, errorCode: 'REJECT', message: String(e) });
        });
      } else if (ret !== undefined) {
        cb({ success: true, data: ret, via: 'return' });
      }
    } catch (e) {
      cb({ success: false, errorCode: 'THROW', message: String((e && e.stack) || e) });
    }
  };
})();
"""
}
