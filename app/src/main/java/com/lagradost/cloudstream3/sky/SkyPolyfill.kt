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
