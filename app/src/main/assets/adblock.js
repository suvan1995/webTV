/*
 * Web TV shields: runs at document start in every frame.
 * Applies cosmetic filters (element hiding), procedural filters (:has-text, :upward, ...)
 * and scriptlets (##+js(...)) supplied by the native filter engine through window.__wtvAdb.
 */
(function () {
  'use strict';
  var DONE = '__wtvAdbDone';
  try {
    if (window[DONE]) return;
    Object.defineProperty(window, DONE, { value: true });
  } catch (e) { return; }

  var B = window.__wtvAdb;
  if (!B || typeof B.cosmetic !== 'function') return;
  var data;
  try { data = JSON.parse(B.cosmetic(String(location.href))); } catch (e) { return; }
  if (!data || data.off) return;

  // Natives captured before any scriptlet patches them
  var W = window, D = document;
  var setT = W.setTimeout.bind(W), clearT = W.clearTimeout.bind(W);
  var JParse = JSON.parse, JStr = JSON.stringify;
  var fnToStr = Function.prototype.toString;
  var defProp = Object.defineProperty, getDesc = Object.getOwnPropertyDescriptor;
  var hasOwn = function (o, k) { return Object.prototype.hasOwnProperty.call(o, k); };
  var MO = W.MutationObserver;

  // ------------------------------------------------------------------ helpers
  function patRe(p, extraFlags) {
    if (p === undefined || p === null || p === '') return null; // null: matches everything
    var m = /^\/(.+)\/([dgimsuvy]*)$/.exec(p);
    if (m) {
      try { return new RegExp(m[1], m[2].replace('g', '') + (extraFlags || '')); }
      catch (e) { return /(?!)/; }
    }
    return new RegExp(String(p).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), extraFlags || '');
  }
  /** Needle matcher with uBO's leading "!" negation; empty needle matches all. */
  function matcher(p) {
    p = p === undefined ? '' : String(p);
    var neg = false;
    if (p.charAt(0) === '!') { neg = true; p = p.slice(1); }
    var re = patRe(p);
    return function (s) { var r = re === null || re.test(String(s)); return neg ? !r : r; };
  }
  function delayOk(d, spec) {
    if (spec === undefined || spec === '' || spec === '*') return true;
    spec = String(spec);
    var neg = spec.charAt(0) === '!';
    var n = parseInt(neg ? spec.slice(1) : spec, 10);
    if (isNaN(n)) return true;
    var r = Number(d) === n;
    return neg ? !r : r;
  }
  function fnText(f) {
    try { return typeof f === 'function' ? fnToStr.call(f) : String(f); } catch (e) { return ''; }
  }
  function rid() { return Math.random().toString(36).slice(2, 10); }
  function whenReady(fn) {
    if (D.readyState !== 'loading') { try { fn(); } catch (e) {} return; }
    D.addEventListener('DOMContentLoaded', function () { try { fn(); } catch (e) {} }, { once: true });
  }
  function observe(fn, attrs) {
    if (!MO) return;
    var pending = false;
    var mo = new MO(function () {
      if (pending) return;
      pending = true;
      setT(function () { pending = false; try { fn(); } catch (e) {} }, 100);
    });
    var opts = { childList: true, subtree: true };
    if (attrs) { opts.attributes = true; }
    mo.observe(D, opts);
    return mo;
  }
  function constValue(v, trusted) {
    if (trusted) {
      if (v === 'undefined') return undefined;
      try { return JParse(v); } catch (e) { /* fall through to the token list */ }
    }
    switch (v) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case "''": case '""': case '': case 'emptyStr': return '';
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new Error(rid()); };
      case 'noopCallbackFunc': return function () { return function () {}; };
      case 'emptyArr': case '[]': return [];
      case 'emptyObj': case '{}': return {};
      case 'noopThisFunc': return function () { return this; };
      case 'NaN': return NaN;
      case 'Infinity': return Infinity;
      case '-Infinity': return -Infinity;
      case 'yes': case 'no': case 'on': case 'off': return v;
    }
    if (/^-?\d+(\.\d+)?$/.test(v)) return Number(v);
    return trusted ? v : ABORT;
  }
  var ABORT = {};

  /** Follows a.b.c, trapping missing links so the final hook still lands when they appear. */
  function trapChain(chain, onFinal) {
    var parts = String(chain).split('.');
    var root = W;
    if (parts[0] === 'window' || parts[0] === 'self' || parts[0] === 'globalThis') parts.shift();
    if (!parts.length) return;
    (function walk(owner, i) {
      var prop = parts[i];
      if (i === parts.length - 1) { try { onFinal(owner, prop); } catch (e) {} return; }
      var v;
      try { v = owner[prop]; } catch (e) { return; }
      if (v !== null && (typeof v === 'object' || typeof v === 'function')) { walk(v, i + 1); return; }
      if (v !== undefined && v !== null) return;
      whenSet(owner, prop, function (nv) { walk(nv, i + 1); });
    })(root, 0);
  }
  /** Runs cb(value) whenever owner[prop] gets an object; several rules can share one trap. */
  var pendingTraps = new WeakMap();
  function whenSet(owner, prop, cb) {
    var m = pendingTraps.get(owner);
    if (!m) { m = Object.create(null); pendingTraps.set(owner, m); }
    if (m[prop]) { m[prop].cbs.push(cb); return; }
    var d = getDesc(owner, prop);
    if (d && d.configurable === false) return;
    var t = m[prop] = { cbs: [cb], cur: d && !d.get ? d.value : undefined };
    try {
      defProp(owner, prop, {
        configurable: true, enumerable: true,
        get: function () { return d && d.get ? d.get.call(this) : t.cur; },
        set: function (nv) {
          if (d && d.set) d.set.call(this, nv); else t.cur = nv;
          if (nv !== null && (typeof nv === 'object' || typeof nv === 'function')) {
            t.cbs.slice().forEach(function (f) { try { f(nv); } catch (e) {} });
          }
        }
      });
    } catch (e) {}
  }

  function hookFn(owner, name, handler) {
    try {
      var orig = owner[name];
      if (typeof orig !== 'function') return;
      owner[name] = new Proxy(orig, { apply: handler });
    } catch (e) {}
  }

  // Path walker for json-prune: a.b.c, [] (any element), [-] (remove matching array items), *
  function jsonPath(root, parts, i, prune) {
    if (root === null || typeof root !== 'object') return false;
    var k = parts[i], last = i === parts.length - 1, any = false, key;
    if (k === '[]' || k === '*' || k === '{}') {
      if (last) {
        if (prune) { if (Array.isArray(root)) root.length = 0; else for (key in root) delete root[key]; }
        return true;
      }
      for (key in root) if (hasOwn(root, key) && jsonPath(root[key], parts, i + 1, prune)) any = true;
      return any;
    }
    if (k === '[-]' || k === '{-}') {
      if (!Array.isArray(root) || last) return false;
      for (var j = root.length - 1; j >= 0; j--) {
        if (jsonPath(root[j], parts, i + 1, false)) { any = true; if (prune) root.splice(j, 1); }
      }
      return any;
    }
    if (!hasOwn(root, k)) return false;
    if (last) { if (prune) delete root[k]; return true; }
    return jsonPath(root[k], parts, i + 1, prune);
  }
  function makePruner(prune, needle) {
    var pp = String(prune || '').split(/ +/).filter(Boolean).map(function (p) { return p.split('.'); });
    var np = String(needle || '').split(/ +/).filter(Boolean).map(function (p) { return p.split('.'); });
    return function (obj) {
      if (!pp.length || obj === null || typeof obj !== 'object') return obj;
      for (var i = 0; i < np.length; i++) if (!jsonPath(obj, np[i], 0, false)) return obj;
      for (var j = 0; j < pp.length; j++) jsonPath(obj, pp[j], 0, true);
      return obj;
    };
  }

  /** "url:/re/ method:POST" or a bare url needle -> tester(details) */
  function propsMatcher(raw) {
    raw = String(raw || '').trim();
    if (!raw) return function () { return true; };
    var checks = [];
    raw.split(/\s+/).forEach(function (tok) {
      var m = /^(url|method|headers|mode|body|credentials|cache|redirect|referrer|referrerPolicy|integrity|keepalive):(.*)$/.exec(tok);
      if (m) checks.push([m[1], matcher(m[2])]); else checks.push(['url', matcher(tok)]);
    });
    return function (d) {
      for (var i = 0; i < checks.length; i++) {
        var v = d[checks[i][0]];
        if (v === undefined) v = '';
        if (!checks[i][1](typeof v === 'string' ? v : JStr(v))) return false;
      }
      return true;
    };
  }
  /** Reads trailing "name, value" pairs of a scriptlet (e.g. propsToMatch, /player/). */
  function varArgs(args) {
    var o = {};
    for (var i = 0; i + 1 < args.length; i += 2) if (args[i]) o[args[i]] = args[i + 1];
    return o;
  }
  function fetchDetails(input, init) {
    var d = { url: '', method: 'GET' };
    try {
      if (typeof input === 'string' || input instanceof URL) d.url = String(input);
      else if (input && input.url) { d.url = input.url; d.method = input.method || 'GET'; }
      if (init) for (var k in init) if (hasOwn(init, k)) d[k] = init[k];
      d.url = new URL(d.url, location.href).href;
    } catch (e) {}
    return d;
  }

  // Response rewriting: fetch
  var fetchMods = [];
  function installFetchHook() {
    if (installFetchHook.done || typeof W.fetch !== 'function') return;
    installFetchHook.done = true;
    hookFn(W, 'fetch', function (target, self, args) {
      var det = fetchDetails(args[0], args[1]);
      var mods = fetchMods.filter(function (m) { return m.match(det); });
      if (!mods.length) return Reflect.apply(target, self, args);
      return Reflect.apply(target, self, args).then(function (resp) {
        return resp.clone().text().then(function (text) {
          var out = text;
          mods.forEach(function (m) { try { out = m.edit(out); } catch (e) {} });
          if (out === text) return resp;
          var r = new Response(out, { status: resp.status, statusText: resp.statusText, headers: resp.headers });
          try {
            defProp(r, 'url', { value: resp.url });
            defProp(r, 'type', { value: resp.type });
            defProp(r, 'redirected', { value: resp.redirected });
          } catch (e) {}
          return r;
        }).catch(function () { return resp; });
      });
    });
  }

  // Response rewriting: XMLHttpRequest
  var xhrMods = [];
  var xhrInfo = new WeakMap();
  function installXhrHook() {
    if (installXhrHook.done || !W.XMLHttpRequest) return;
    installXhrHook.done = true;
    var XP = W.XMLHttpRequest.prototype;
    hookFn(XP, 'open', function (target, self, args) {
      var url = '';
      try { url = new URL(String(args[1]), location.href).href; } catch (e) { url = String(args[1]); }
      xhrInfo.set(self, { method: String(args[0] || 'GET').toUpperCase(), url: url });
      return Reflect.apply(target, self, args);
    });
    ['response', 'responseText'].forEach(function (prop) {
      var d = getDesc(XP, prop);
      if (!d || !d.get) return;
      var get = d.get;
      defProp(XP, prop, {
        configurable: true, enumerable: d.enumerable,
        get: function () {
          var v = get.call(this);
          var info = xhrInfo.get(this);
          if (!info || this.readyState !== 4) return v;
          var key = prop + '_out';
          if (info[prop + '_in'] === v && hasOwn(info, key)) return info[key];
          var mods = xhrMods.filter(function (m) { return m.match(info); });
          var out = v;
          if (mods.length) {
            var rt = this.responseType;
            if (typeof v === 'string') {
              mods.forEach(function (m) { try { out = m.edit(out); } catch (e) {} });
            } else if (rt === 'json' && v && typeof v === 'object') {
              var s = JStr(v), s2 = s;
              mods.forEach(function (m) { try { s2 = m.edit(s2); } catch (e) {} });
              if (s2 !== s) { try { out = JParse(s2); } catch (e) { out = v; } }
            }
          }
          info[prop + '_in'] = v; info[key] = out;
          return out;
        }
      });
    });
  }

  function replacer(pattern, replacement) {
    var re = patRe(pattern);
    if (re === null) return function (t) { return t; };
    var m = /^\/(.+)\/([dgimsuvy]*)$/.exec(pattern);
    if (m) { try { re = new RegExp(m[1], m[2].indexOf('g') >= 0 ? m[2] : m[2]); } catch (e) {} }
    else re = new RegExp(re.source, 'g');
    var rep = replacement === undefined ? '' : String(replacement);
    return function (t) { return String(t).replace(re, rep); };
  }
  function jsonEditor(prune, needle) {
    var pr = makePruner(prune, needle);
    return function (t) {
      var o;
      try { o = JParse(t); } catch (e) { return t; }
      var before = JStr(o);
      pr(o);
      var after = JStr(o);
      return after === before ? t : after;
    };
  }

  function cookieSet(name, value, expires, path) {
    var c = encodeURIComponent(name) + '=' + value + '; path=' + (path && path !== 'none' ? path : '/');
    if (expires) c += '; expires=' + expires;
    try { D.cookie = c; } catch (e) {}
  }
  function storageValue(v, trusted) {
    var map = { 'undefined': 'undefined', 'false': 'false', 'true': 'true', 'null': 'null', 'emptyObj': '{}',
      'emptyArr': '[]', 'emptyStr': '', '': '', 'yes': 'yes', 'no': 'no', 'on': 'on', 'off': 'off' };
    if (hasOwn(map, v)) return map[v];
    if (/^\d+$/.test(v)) return v;
    if (!trusted) return null;
    return String(v).replace('$now$', Date.now()).replace('$currentDate$', new Date().toISOString())
      .replace('$currentISODate$', new Date().toISOString());
  }
  function splitSelectors(s) {
    var out = [], cur = '', depth = 0, q = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charAt(i);
      if (q) { cur += c; if (c === q) q = ''; continue; }
      if (c === '"' || c === "'") { q = c; cur += c; continue; }
      if (c === '(' || c === '[') depth++;
      if (c === ')' || c === ']') depth--;
      if (c === ',' && depth === 0) { if (cur.trim()) out.push(cur.trim()); cur = ''; continue; }
      cur += c;
    }
    if (cur.trim()) out.push(cur.trim());
    return out;
  }
  function deepQuery(sel) {
    var parts = sel.split(/\s*>>>\s*/), root = D, el = null;
    for (var i = 0; i < parts.length; i++) {
      try { el = root.querySelector(parts[i]); } catch (e) { return null; }
      if (!el) return null;
      if (i < parts.length - 1) { root = el.shadowRoot; if (!root) return null; }
    }
    return el;
  }

  // ------------------------------------------------------------------ scriptlets
  var S = {};

  S['set'] = function (chain, value) {
    var trusted = this === true;
    var v = constValue(value === undefined ? 'undefined' : value, trusted);
    if (v === ABORT) return;
    trapChain(chain, function (owner, prop) {
      var d = getDesc(owner, prop);
      if (d && d.configurable === false) { try { owner[prop] = v; } catch (e) {} return; }
      defProp(owner, prop, { configurable: true, enumerable: true, get: function () { return v; }, set: function () {} });
    });
  };
  S['trusted-set'] = function (chain, value) { S['set'].call(true, chain, value); };

  S['aopr'] = function (chain) {
    trapChain(chain, function (owner, prop) {
      defProp(owner, prop, { configurable: true, get: function () { throw new ReferenceError(rid()); }, set: function () {} });
    });
  };
  S['aopw'] = function (chain) {
    trapChain(chain, function (owner, prop) {
      var cur = owner[prop];
      defProp(owner, prop, { configurable: true, get: function () { return cur; }, set: function () { throw new ReferenceError(rid()); } });
    });
  };
  S['acs'] = function (chain, needle, context) {
    var re = patRe(needle), ctx = patRe(context);
    var me = D.currentScript;
    trapChain(chain, function (owner, prop) {
      var d = getDesc(owner, prop);
      var value = d && !d.get ? d.value : owner[prop];
      var getter = d && d.get, setter = d && d.set;
      function check() {
        var s = D.currentScript;
        if (!s || s === me || s.tagName !== 'SCRIPT') return;
        if (ctx !== null && !ctx.test(s.src || 'inlineScript')) return;
        var text = s.src ? '' : s.textContent;
        if (s.src && re !== null) {
          var m = /^data:[^,]*,(.+)$/.exec(s.src);
          if (m) { try { text = atob(m[1]); } catch (e) { text = m[1]; } }
        }
        if (re === null || re.test(text)) throw new ReferenceError(rid());
      }
      defProp(owner, prop, {
        configurable: true,
        get: function () { check(); return getter ? getter.call(this) : value; },
        set: function (v) { check(); if (setter) setter.call(this, v); else value = v; }
      });
    });
  };
  S['aost'] = function (chain, needle) {
    var re = patRe(needle);
    trapChain(chain, function (owner, prop) {
      var d = getDesc(owner, prop);
      var value = d && !d.get ? d.value : owner[prop];
      var getter = d && d.get, setter = d && d.set;
      function check() { var st = String(new Error().stack || ''); if (re === null || re.test(st)) throw new ReferenceError(rid()); }
      defProp(owner, prop, {
        configurable: true,
        get: function () { check(); return getter ? getter.call(this) : value; },
        set: function (v) { check(); if (setter) setter.call(this, v); else value = v; }
      });
    });
  };

  function timerDefuser(name) {
    return function (needle, delay) {
      var m = matcher(needle);
      hookFn(W, name, function (target, self, args) {
        if (m(fnText(args[0])) && delayOk(args[1], delay)) args[0] = function () {};
        return Reflect.apply(target, self, args);
      });
    };
  }
  S['nostif'] = timerDefuser('setTimeout');
  S['nosiif'] = timerDefuser('setInterval');

  function timerBooster(name) {
    return function (needle, delay, boost) {
      var m = matcher(needle === undefined ? '' : needle);
      var d = delay === '*' ? null : (parseInt(delay, 10) || 1000);
      var b = parseFloat(boost);
      if (isNaN(b) || !isFinite(b)) b = 0.05;
      b = Math.min(50, Math.max(0.001, b));
      hookFn(W, name, function (target, self, args) {
        if ((d === null || Number(args[1]) === d) && m(fnText(args[0]))) args[1] = (Number(args[1]) || 0) * b;
        return Reflect.apply(target, self, args);
      });
    };
  }
  S['nano-sib'] = timerBooster('setInterval');
  S['nano-stb'] = timerBooster('setTimeout');

  S['aeld'] = function (type, needle) {
    var tm = matcher(type), nm = matcher(needle);
    hookFn(W.EventTarget.prototype, 'addEventListener', function (target, self, args) {
      var h = args[1], hs = '';
      try { hs = typeof h === 'function' ? fnText(h) : (h && typeof h.handleEvent === 'function' ? fnText(h.handleEvent) : String(h)); } catch (e) {}
      if (tm(String(args[0])) && nm(hs)) return undefined;
      return Reflect.apply(target, self, args);
    });
  };

  S['nowoif'] = function (needle) {
    var m = matcher(needle);
    hookFn(W, 'open', function (target, self, args) {
      if (m(String(args[0] || ''))) {
        var noop = function () {};
        return { closed: false, close: noop, focus: noop, blur: noop, postMessage: noop, opener: W,
          location: { href: '', assign: noop, replace: noop }, document: { write: noop, close: noop } };
      }
      return Reflect.apply(target, self, args);
    });
  };

  S['no-fetch-if'] = function (props, responseBody) {
    var pm = propsMatcher(props);
    var body = responseBody === 'emptyObj' ? '{}' : responseBody === 'emptyArr' ? '[]' : '';
    hookFn(W, 'fetch', function (target, self, args) {
      var det = fetchDetails(args[0], args[1]);
      if (pm(det)) {
        var r = new Response(body, { status: 200, statusText: 'OK' });
        try { defProp(r, 'url', { value: det.url }); } catch (e) {}
        return Promise.resolve(r);
      }
      return Reflect.apply(target, self, args);
    });
  };

  S['no-xhr-if'] = function (props, directive) {
    var pm = propsMatcher(props);
    var XP = W.XMLHttpRequest && W.XMLHttpRequest.prototype;
    if (!XP) return;
    var blocked = new WeakSet();
    hookFn(XP, 'open', function (target, self, args) {
      var url = '';
      try { url = new URL(String(args[1]), location.href).href; } catch (e) {}
      if (pm({ method: String(args[0] || 'GET').toUpperCase(), url: url })) blocked.add(self); else blocked.delete(self);
      return Reflect.apply(target, self, args);
    });
    hookFn(XP, 'send', function (target, self, args) {
      if (!blocked.has(self)) return Reflect.apply(target, self, args);
      var xhr = self;
      var text = directive === 'true' || !directive ? '' : String(directive);
      setT(function () {
        try {
          defProp(xhr, 'readyState', { value: 4, configurable: true });
          defProp(xhr, 'status', { value: 200, configurable: true });
          defProp(xhr, 'statusText', { value: 'OK', configurable: true });
          defProp(xhr, 'responseText', { value: text, configurable: true });
          defProp(xhr, 'response', { value: xhr.responseType === 'json' ? null : text, configurable: true });
        } catch (e) {}
        ['readystatechange', 'load', 'loadend'].forEach(function (t) {
          try { xhr.dispatchEvent(new Event(t)); } catch (e) {}
        });
      }, 1);
    });
  };

  S['json-prune'] = function (prune, needle) {
    var pr = makePruner(prune, needle);
    hookFn(JSON, 'parse', function (target, self, args) {
      var r = Reflect.apply(target, self, args);
      try { return pr(r); } catch (e) { return r; }
    });
    if (W.Response) hookFn(W.Response.prototype, 'json', function (target, self, args) {
      return Reflect.apply(target, self, args).then(function (o) { try { return pr(o); } catch (e) { return o; } });
    });
  };
  S['json-prune-fetch-response'] = function (prune, needle) {
    var va = varArgs([].slice.call(arguments, 2));
    fetchMods.push({ match: propsMatcher(va.propsToMatch), edit: jsonEditor(prune, needle) });
    installFetchHook();
  };
  S['json-prune-xhr-response'] = function (prune, needle) {
    var va = varArgs([].slice.call(arguments, 2));
    xhrMods.push({ match: propsMatcher(va.propsToMatch), edit: jsonEditor(prune, needle) });
    installXhrHook();
  };
  S['trusted-replace-fetch-response'] = function (pattern, replacement, props) {
    if (!pattern) return;
    fetchMods.push({ match: propsMatcher(props), edit: replacer(pattern, replacement) });
    installFetchHook();
  };
  S['trusted-replace-xhr-response'] = function (pattern, replacement, props) {
    if (!pattern) return;
    xhrMods.push({ match: propsMatcher(props), edit: replacer(pattern, replacement) });
    installXhrHook();
  };

  function nodeTextEditor(nodeName, edit) {
    var name = String(nodeName || '').toUpperCase();
    var nm = /^\/.*\/[a-z]*$/i.test(nodeName) ? patRe(nodeName, 'i') : null;
    function wanted(n) { return nm ? nm.test(n.nodeName) : n.nodeName === name; }
    function handle(n) {
      if (!wanted(n)) return;
      var t = n.textContent;
      if (!t) return;
      edit(n, t);
    }
    if (!MO) return;
    var mo = new MO(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) handle(added[j]);
      }
    });
    mo.observe(D, { childList: true, subtree: true });
    whenReady(function () {
      try {
        var walker = D.createTreeWalker(D.documentElement, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
        var n; while ((n = walker.nextNode())) handle(n);
      } catch (e) {}
      if (D.readyState === 'complete') mo.disconnect();
      else W.addEventListener('load', function () { setT(function () { mo.disconnect(); }, 5000); }, { once: true });
    });
  }
  S['rmnt'] = function (nodeName, needle) {
    var m = matcher(needle);
    nodeTextEditor(nodeName, function (n, t) { if (m(t)) { if (n.parentNode) n.textContent = ''; } });
  };
  S['rpnt'] = function (nodeName, pattern, replacement) {
    var va = varArgs([].slice.call(arguments, 3));
    var cond = va.condition ? matcher(va.condition) : null;
    var count = parseInt(va.sedCount, 10) || 0;
    var re = patRe(pattern);
    if (re === null) return;
    var m = /^\/(.+)\/([dgimsuvy]*)$/.exec(pattern);
    var flags = m ? m[2] : '';
    if (!count && flags.indexOf('g') < 0) flags += 'g';
    try { re = new RegExp(re.source, flags); } catch (e) {}
    var done = 0;
    nodeTextEditor(nodeName, function (n, t) {
      if (count && done >= count) return;
      if (cond && !cond(t)) return;
      var out = t.replace(re, replacement === undefined ? '' : replacement);
      if (out !== t) { n.textContent = out; done++; }
    });
  };

  S['ra'] = function (attrs, selector, behavior) {
    var list = String(attrs || '').split(/\s*\|\s*/).filter(Boolean);
    if (!list.length) return;
    var sel = selector || list.map(function (a) { return '[' + a + ']'; }).join(',');
    function run() {
      try { D.querySelectorAll(sel).forEach(function (el) { list.forEach(function (a) { el.removeAttribute(a); }); }); } catch (e) {}
    }
    whenReady(run);
    W.addEventListener('load', run, { once: true });
    if (String(behavior || '').indexOf('stay') >= 0) whenReady(function () { observe(run, true); });
  };
  S['rc'] = function (classes, selector, behavior) {
    var list = String(classes || '').split(/\s*\|\s*/).filter(Boolean);
    if (!list.length) return;
    var sel = selector || list.map(function (c) { return '.' + c; }).join(',');
    function run() {
      try { D.querySelectorAll(sel).forEach(function (el) { list.forEach(function (c) { el.classList.remove(c); }); }); } catch (e) {}
    }
    whenReady(run);
    W.addEventListener('load', run, { once: true });
    if (String(behavior || '').indexOf('stay') >= 0) whenReady(function () { observe(run, true); });
  };

  S['noeval'] = function () {
    hookFn(W, 'eval', function () { return undefined; });
  };
  S['noeval-if'] = function (needle) {
    var m = matcher(needle);
    hookFn(W, 'eval', function (target, self, args) {
      if (m(String(args[0]))) return undefined;
      return Reflect.apply(target, self, args);
    });
  };
  S['nowebrtc'] = function () {
    var Fake = function () {
      return { close: function () {}, createDataChannel: function () { return {}; }, createOffer: function () { return Promise.resolve({}); },
        setLocalDescription: function () { return Promise.resolve(); }, addEventListener: function () {}, removeEventListener: function () {} };
    };
    ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (n) { if (W[n]) try { W[n] = Fake; } catch (e) {} });
  };

  S['set-cookie'] = function (name, value, path) {
    if (!name) return;
    var v = String(value === undefined ? '' : value);
    if (!/^(accept|accepted|reject|rejected|allow|allowed|deny|denied|ok|true|false|yes|no|y|n|on|off|necessary|required|approved|disapproved|hide|hidden|essential|nonessential|dismiss|dismissed|checked|unchecked|undefined|null|-?\d{1,15})$/i.test(v) && v !== '') return;
    if (D.cookie.indexOf(encodeURIComponent(name) + '=' + v) >= 0) return;
    cookieSet(name, encodeURIComponent(v), null, path);
  };
  S['trusted-set-cookie'] = function (name, value, offset, path) {
    if (!name) return;
    var v = String(value === undefined ? '' : value)
      .replace('$now$', Date.now()).replace('$currentDate$', new Date().toUTCString())
      .replace('$currentISODate$', new Date().toISOString());
    var exp = null;
    if (offset) {
      var secs = /^\d+$/.test(offset) ? parseInt(offset, 10) : offset === '1day' ? 86400 : offset === '1year' ? 31536000 : 0;
      if (secs) exp = new Date(Date.now() + secs * 1000).toUTCString();
    }
    cookieSet(name, v, exp, path);
  };
  function storageSetter(store, trusted) {
    return function (key, value) {
      if (!key) return;
      try {
        var st = W[store];
        if (value === '$remove$') { st.removeItem(key); return; }
        var v = storageValue(String(value === undefined ? '' : value), trusted);
        if (v !== null) st.setItem(key, v);
      } catch (e) {}
    };
  }
  S['set-local-storage-item'] = storageSetter('localStorage', false);
  S['trusted-set-local-storage-item'] = storageSetter('localStorage', true);
  S['set-session-storage-item'] = storageSetter('sessionStorage', false);
  S['trusted-set-session-storage-item'] = storageSetter('sessionStorage', true);
  S['remove-cookie'] = function (needle) {
    var m = matcher(needle);
    function run() {
      D.cookie.split(';').forEach(function (c) {
        var name = c.split('=')[0].trim();
        if (!name || !m(name)) return;
        var exp = '=; expires=Thu, 01 Jan 1970 00:00:00 GMT';
        var host = location.hostname, parts = host.split('.');
        D.cookie = name + exp + '; path=/';
        for (var i = 0; i < parts.length - 1; i++) {
          var dom = parts.slice(i).join('.');
          D.cookie = name + exp + '; path=/; domain=' + dom;
          D.cookie = name + exp + '; path=/; domain=.' + dom;
        }
      });
    }
    run();
    W.addEventListener('beforeunload', run);
    whenReady(run);
  };

  S['href-sanitizer'] = function (selector, source) {
    if (!selector) return;
    source = source || 'text';
    function valid(u) { return /^https?:\/\//.test(u) ? u : ''; }
    function run() {
      var els;
      try { els = D.querySelectorAll(selector); } catch (e) { return; }
      els.forEach(function (el) {
        var u = '';
        if (source === 'text') u = (el.textContent || '').trim();
        else if (source.charAt(0) === '[') u = el.getAttribute(source.slice(1, -1)) || '';
        else if (source.charAt(0) === '?') { try { u = new URL(el.href).searchParams.get(source.slice(1)) || ''; } catch (e) {} }
        u = valid(u);
        if (u && el.href !== u) el.setAttribute('href', u);
      });
    }
    whenReady(function () { run(); observe(run); });
  };

  S['popads-dummy'] = function () {
    try { delete W.PopAds; delete W.popns; defProp(W, 'PopAds', { value: {} }); defProp(W, 'popns', { value: {} }); } catch (e) {}
  };
  S['nofab'] = function () {
    var Fab = function () {};
    var p = Fab.prototype;
    p.check = function () { return true; };
    p.clearEvent = function () {};
    p.emitEvent = function () { return this; };
    p.on = function (detected, fn) { if (!detected && typeof fn === 'function') fn(); return this; };
    p.onDetected = function () { return this; };
    p.onNotDetected = function (fn) { if (typeof fn === 'function') fn(); return this; };
    p.setOption = function () { return this; };
    p.options = { set: function () { return this; }, get: function () {} };
    var inst = new Fab();
    try {
      defProp(W, 'FuckAdBlock', { value: Fab }); defProp(W, 'BlockAdBlock', { value: Fab }); defProp(W, 'SniffAdBlock', { value: Fab });
      defProp(W, 'fuckAdBlock', { value: inst }); defProp(W, 'blockAdBlock', { value: inst }); defProp(W, 'sniffAdBlock', { value: inst });
    } catch (e) {}
  };
  S['nobab'] = function () {};

  S['trusted-click-element'] = function (selectors, extraMatch, delay) {
    var list = splitSelectors(String(selectors || ''));
    if (!list.length) return;
    if (extraMatch) {
      var conds = String(extraMatch).split(/\s*,\s*/).filter(Boolean);
      for (var i = 0; i < conds.length; i++) {
        var c = conds[i], neg = c.charAt(0) === '!';
        if (neg) c = c.slice(1);
        var m = /^(cookie|localStorage):(.+)$/.exec(c);
        if (!m) continue;
        var nm = matcher(m[2].split('=')[0]), found = false;
        if (m[1] === 'cookie') found = D.cookie.split(';').some(function (x) { return nm(x.split('=')[0].trim()); });
        else { try { for (var k = 0; k < localStorage.length; k++) if (nm(localStorage.key(k))) found = true; } catch (e) {} }
        if (found === neg) return;
      }
    }
    var wait = Math.max(0, parseInt(delay, 10) || 0);
    var idx = 0, deadline = Date.now() + 10000, mo = null;
    function step() {
      if (idx >= list.length || Date.now() > deadline) { if (mo) mo.disconnect(); return; }
      var el = deepQuery(list[idx]);
      if (!el) return;
      idx++;
      setT(function () { try { el.click(); } catch (e) {} step(); }, wait);
    }
    whenReady(function () {
      step();
      if (idx < list.length && MO) {
        mo = new MO(function () { step(); });
        mo.observe(D, { childList: true, subtree: true, attributes: true });
        setT(function () { if (mo) mo.disconnect(); }, 10000);
      }
    });
  };

  var ALIAS = {
    'set-constant': 'set', 'trusted-set-constant': 'trusted-set',
    'abort-on-property-read': 'aopr', 'abort-on-property-write': 'aopw',
    'abort-current-script': 'acs', 'abort-current-inline-script': 'acs', 'acis': 'acs',
    'abort-on-stack-trace': 'aost',
    'no-setTimeout-if': 'nostif', 'prevent-setTimeout': 'nostif', 'setTimeout-defuser': 'nostif',
    'no-setInterval-if': 'nosiif', 'prevent-setInterval': 'nosiif', 'setInterval-defuser': 'nosiif',
    'nano-setInterval-booster': 'nano-sib', 'adjust-setInterval': 'nano-sib',
    'nano-setTimeout-booster': 'nano-stb', 'adjust-setTimeout': 'nano-stb',
    'addEventListener-defuser': 'aeld', 'prevent-addEventListener': 'aeld',
    'window.open-defuser': 'nowoif', 'no-window-open-if': 'nowoif', 'prevent-window-open': 'nowoif',
    'prevent-fetch': 'no-fetch-if', 'prevent-xhr': 'no-xhr-if',
    'remove-attr': 'ra', 'remove-class': 'rc',
    'remove-node-text': 'rmnt', 'replace-node-text': 'rpnt', 'trusted-replace-node-text': 'rpnt', 'trusted-rpnt': 'rpnt',
    'trusted-rpfr': 'trusted-replace-fetch-response', 'trusted-rpxr': 'trusted-replace-xhr-response',
    'silent-noeval': 'noeval', 'prevent-eval-if': 'noeval-if', 'noeval-silent': 'noeval',
    'cookie-remover': 'remove-cookie', 'popads.net': 'popads-dummy',
    'fuckadblock-defuser': 'nofab', 'bab-defuser': 'nobab', 'nowebrtc': 'nowebrtc'
  };

  (data.sl || []).forEach(function (a) {
    var fn = S[ALIAS[a[0]] || a[0]];
    if (typeof fn !== 'function') return;
    try { fn.apply(null, a.slice(1)); } catch (e) {}
  });

  // ------------------------------------------------------------------ element hiding
  var sheet = null, sheet2 = null, extraCss = '', styleEl = null;
  function adopt(s) {
    try {
      var list = D.adoptedStyleSheets;
      if (list.indexOf(s) < 0) D.adoptedStyleSheets = list.concat([s]);
      return true;
    } catch (e) { return false; }
  }
  function fallbackStyle(text) {
    try {
      if (!styleEl) { styleEl = D.createElement('style'); styleEl.setAttribute('data-wtv', ''); }
      styleEl.textContent = text;
      var p = D.head || D.documentElement;
      if (p && styleEl.parentNode !== p) p.appendChild(styleEl);
    } catch (e) {}
  }
  var useSheets = typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in D && 'replaceSync' in CSSStyleSheet.prototype;
  function applyCss() {
    if (useSheets) {
      try {
        if (!sheet && data.css) { sheet = new CSSStyleSheet(); sheet.replaceSync(data.css); }
        if (!sheet2 && extraCss) sheet2 = new CSSStyleSheet();
        if (sheet2) sheet2.replaceSync(extraCss);
        if (sheet) adopt(sheet);
        if (sheet2) adopt(sheet2);
        return;
      } catch (e) { useSheets = false; }
    }
    if (data.css || extraCss) fallbackStyle((data.css || '') + extraCss);
  }
  function keepCss() {
    if (useSheets) { if (sheet) adopt(sheet); if (sheet2) adopt(sheet2); }
    else if (styleEl && !styleEl.isConnected) applyCss();
  }
  applyCss();
  if (!useSheets && !D.documentElement) whenReady(applyCss);

  // Generic rules keyed on class / id: ask the engine only for names that exist on the page.
  if (data.generic && typeof B.generic === 'function') {
    var seenC = new Set(), seenI = new Set(), qC = [], qI = [], timer = 0, first = true;
    var addEl = function (el) {
      var id = el.id;
      if (id && typeof id === 'string' && !seenI.has(id)) { seenI.add(id); qI.push(id); }
      var cl = el.classList;
      if (cl) for (var i = 0; i < cl.length; i++) { var c = cl[i]; if (!seenC.has(c)) { seenC.add(c); qC.push(c); } }
    };
    var scan = function (root) {
      if (!root || root.nodeType !== 1) return;
      addEl(root);
      if (root.firstElementChild) {
        var all = root.querySelectorAll('[id],[class]');
        for (var i = 0; i < all.length; i++) addEl(all[i]);
      }
    };
    var flush = function () {
      timer = 0;
      if (!qC.length && !qI.length) return;
      var css = '';
      try { css = B.generic(String(location.href), JStr(qC), JStr(qI)); } catch (e) {}
      qC = []; qI = [];
      if (css) { extraCss += css; applyCss(); }
    };
    var schedule = function () {
      if (timer) return;
      timer = setT(flush, first ? 0 : 60);
      first = false;
    };
    if (D.documentElement) { scan(D.documentElement); schedule(); }
    if (MO) {
      new MO(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (m.type === 'attributes') addEl(m.target);
          else for (var j = 0; j < m.addedNodes.length; j++) scan(m.addedNodes[j]);
        }
        if (qC.length || qI.length) schedule();
        keepCss();
      }).observe(D, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });
    }
    whenReady(function () { scan(D.documentElement); schedule(); });
  } else if (MO && (data.css || extraCss)) {
    new MO(keepCss).observe(D, { childList: true });
  }
  setT(function tick() { keepCss(); setT(tick, 2000); }, 1000);

  // ------------------------------------------------------------------ procedural filters
  var OPS = ['has-text', 'upward', 'remove', 'style', 'matches-path', 'min-text-length', 'matches-css',
    'matches-css-before', 'matches-css-after', 'watch-attr', 'remove-attr', 'remove-class', 'matches-attr', 'xpath', 'others', 'matches-media', 'matches-prop'];
  function parseProc(sel) {
    var steps = [], css = '', i = 0, depth = 0, q = '';
    while (i < sel.length) {
      var c = sel.charAt(i);
      if (q) { css += c; if (c === '\\') { css += sel.charAt(i + 1); i += 2; continue; } if (c === q) q = ''; i++; continue; }
      if (c === '"' || c === "'") { q = c; css += c; i++; continue; }
      if (c === '(' || c === '[') depth++;
      else if (c === ')' || c === ']') depth--;
      if (c === ':' && depth === 0) {
        var m = /^:([a-z-]+)\(/.exec(sel.slice(i));
        if (m && OPS.indexOf(m[1]) >= 0) {
          var j = i + m[0].length, d = 1, arg = '';
          while (j < sel.length && d > 0) {
            var cj = sel.charAt(j);
            if (cj === '\\') { arg += cj + sel.charAt(j + 1); j += 2; continue; }
            if (cj === '(') d++;
            else if (cj === ')') { d--; if (d === 0) break; }
            arg += cj; j++;
          }
          if (d !== 0) return null;
          if (css.trim() || (css && steps.length)) steps.push({ css: css });
          css = '';
          steps.push({ op: m[1], arg: arg });
          i = j + 1;
          continue;
        }
      }
      if (c === ':' && depth > 0) {
        var m2 = /^:([a-z-]+)\(/.exec(sel.slice(i));
        if (m2 && OPS.indexOf(m2[1]) >= 0) return null; // procedural inside :has()/:not(): unsupported
      }
      css += c; i++;
    }
    if (css.trim()) steps.push({ css: css });
    return steps.length ? steps : null;
  }
  function textRe(arg) {
    var m = /^\/(.+)\/([imsu]*)$/.exec(arg);
    if (m) { try { return new RegExp(m[1], m[2]); } catch (e) { return /(?!)/; } }
    return new RegExp(arg.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
  }
  var procs = [];
  (data.proc || []).forEach(function (s) {
    var steps = parseProc(s);
    if (!steps) return;
    for (var i = 0; i < steps.length; i++) {
      var st = steps[i];
      if (st.op === 'xpath' || st.op === 'others' || st.op === 'matches-media' || st.op === 'matches-prop' || st.op === 'matches-attr') return;
      if (st.op === 'has-text' || st.op === 'matches-path') st.re = textRe(st.arg);
    }
    procs.push(steps);
  });
  var HIDDEN = new WeakSet();
  function runProc(steps) {
    var els = null, action = 'hide', actArg = '';
    for (var i = 0; i < steps.length; i++) {
      var st = steps[i], next = [], k;
      if (st.css !== undefined) {
        var c = st.css;
        if (els === null) { try { els = Array.prototype.slice.call(D.querySelectorAll(c.trim())); } catch (e) { return; } continue; }
        var rel = /^\s*[>+~]/.test(c) || /^\s/.test(c);
        for (k = 0; k < els.length; k++) {
          try {
            if (rel) next = next.concat(Array.prototype.slice.call(els[k].querySelectorAll(':scope' + c)));
            else if (els[k].matches(c)) next.push(els[k]);
          } catch (e) { return; }
        }
        els = next; continue;
      }
      if (st.op === 'matches-path') {
        if (!st.re.test(location.pathname + location.search)) return;
        continue;
      }
      if (els === null) els = [D.documentElement];
      switch (st.op) {
        case 'has-text':
          els = els.filter(function (e) { return st.re.test(e.textContent || ''); }); break;
        case 'min-text-length':
          var n = parseInt(st.arg, 10) || 0;
          els = els.filter(function (e) { return (e.textContent || '').length >= n; }); break;
        case 'upward':
          var num = parseInt(st.arg, 10);
          for (k = 0; k < els.length; k++) {
            var p = els[k];
            if (!isNaN(num) && String(num) === st.arg.trim()) { for (var u = 0; u < num && p; u++) p = p.parentElement; }
            else { try { p = p.parentElement && p.parentElement.closest(st.arg); } catch (e) { p = null; } }
            if (p && p !== D.documentElement && p !== D.body && next.indexOf(p) < 0) next.push(p);
          }
          els = next; break;
        case 'matches-css': case 'matches-css-before': case 'matches-css-after':
          var pseudo = st.op === 'matches-css-before' ? '::before' : st.op === 'matches-css-after' ? '::after' : null;
          var ci = st.arg.indexOf(':');
          if (ci < 0) return;
          var prop = st.arg.slice(0, ci).trim(), vre = textRe(st.arg.slice(ci + 1).trim());
          els = els.filter(function (e) { try { return vre.test(getComputedStyle(e, pseudo)[prop] || getComputedStyle(e, pseudo).getPropertyValue(prop)); } catch (x) { return false; } });
          break;
        case 'remove': action = 'remove'; break;
        case 'style': action = 'style'; actArg = st.arg; break;
        case 'remove-attr': action = 'remove-attr'; actArg = st.arg; break;
        case 'remove-class': action = 'remove-class'; actArg = st.arg; break;
        case 'watch-attr': break;
      }
      if (!els.length) return;
    }
    if (!els) return;
    els.forEach(function (e) {
      if (!e || e === D.documentElement || e === D.body) return;
      try {
        if (action === 'remove') e.remove();
        else if (action === 'style') e.style.cssText += ';' + actArg;
        else if (action === 'remove-attr') { var re = textRe(actArg); for (var a = e.attributes.length - 1; a >= 0; a--) if (re.test(e.attributes[a].name)) e.removeAttribute(e.attributes[a].name); }
        else if (action === 'remove-class') { var rc = textRe(actArg); Array.prototype.slice.call(e.classList).forEach(function (c) { if (rc.test(c)) e.classList.remove(c); }); }
        else if (!HIDDEN.has(e) || e.style.getPropertyValue('display') !== 'none') { HIDDEN.add(e); e.style.setProperty('display', 'none', 'important'); }
      } catch (x) {}
    });
  }
  if (procs.length) {
    var procBusy = false, procLast = 0;
    var runAll = function () {
      procBusy = false; procLast = Date.now();
      for (var i = 0; i < procs.length; i++) { try { runProc(procs[i]); } catch (e) {} }
    };
    var procKick = function () {
      if (procBusy) return;
      procBusy = true;
      setT(runAll, Math.max(0, 250 - (Date.now() - procLast)));
    };
    whenReady(function () {
      runAll();
      if (MO) new MO(procKick).observe(D, { childList: true, subtree: true, characterData: true });
      W.addEventListener('load', procKick, { once: true });
    });
  }

  // ------------------------------------------------------------------ YouTube video ads
  // Safety net when an ad still slips through: mute it, jump to its end and press Skip.
  if (/(^|\.)youtube\.com$/.test(location.hostname) && W.top === W) {
    var ytAd = function () {
      try {
        var player = D.querySelector('.html5-video-player.ad-showing, .ad-showing .html5-video-player, #movie_player.ad-showing');
        if (player) {
          var v = player.querySelector('video');
          if (v) {
            if (!v.muted) { v.muted = true; v.__wtvMuted = true; }
            if (isFinite(v.duration) && v.duration > 0 && v.currentTime < v.duration - 0.2) v.currentTime = v.duration - 0.1;
            if (v.paused) { var pr = v.play(); if (pr && pr.catch) pr.catch(function () {}); }
          }
          var skip = D.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button');
          if (skip) skip.click();
        } else {
          var mv = D.querySelector('.html5-video-player:not(.ad-showing) video');
          if (mv && mv.__wtvMuted) { mv.muted = false; mv.__wtvMuted = false; }
        }
        var overlay = D.querySelector('.ytp-ad-overlay-close-button');
        if (overlay) overlay.click();
      } catch (e) {}
      setT(ytAd, 400);
    };
    whenReady(ytAd);
  }
})();
