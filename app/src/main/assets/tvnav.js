(function () {
  'use strict';
  if (window.__tv) return;

  var cur = null, textTarget = null, suppress = false, lastPointer = 0, prevHover = null;

  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,video,iframe,' +
    '[tabindex]:not([tabindex="-1"]),[role=button],[role=link],[role=tab],[role=menuitem],' +
    '[role=option],[role=checkbox],[role=switch],[role=radio],[onclick],' +
    '[contenteditable=""],[contenteditable=true],label[for]';
  var POINTER_TAGS = 'div,span,li,img,svg,p,h1,h2,h3,h4,figure,section,article';

  // Focus ring (an overlay, so we never touch the site's own classes/styles)
  var ring = document.createElement('div');
  ring.id = '__tv_ring';
  ring.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;display:none;z-index:2147483647;' +
    'pointer-events:none;box-sizing:border-box;border:4px solid #4F8CFF;border-radius:10px;' +
    'box-shadow:0 0 0 3px rgba(255,255,255,.9),0 0 18px 6px rgba(79,140,255,.6);';

  function mountRing() {
    var p = document.documentElement;
    if (p && ring.parentNode !== p) p.appendChild(ring);
  }

  function vp() {
    var v = window.visualViewport;
    return { w: v ? v.width : window.innerWidth, h: v ? v.height : window.innerHeight };
  }

  function isTextInput(el) {
    if (!el || !el.tagName) return false;
    var t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t === 'INPUT') {
      var ty = (el.type || 'text').toLowerCase();
      return ['text', 'search', 'email', 'url', 'tel', 'password', 'number', ''].indexOf(ty) >= 0;
    }
    return !!el.isContentEditable;
  }

  function inFixed(node) {
    var n = node, d = 0;
    while (n && n.nodeType === 1 && n !== document.documentElement && d < 40) {
      var p = getComputedStyle(n).position;
      if (p === 'fixed' || p === 'sticky') return true;
      n = n.parentElement; d++;
    }
    return false;
  }

  function collect(v) {
    var out = [], seen = new Set(), i;
    function push(el, ptr) {
      if (seen.has(el) || el === ring) return;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return;
      if (r.right < -v.w * 2 || r.left > v.w * 3 || r.bottom < -v.h * 10 || r.top > v.h * 10) return;
      if (ptr && r.width > v.w * 0.95 && r.height > v.h * 0.9) return;
      seen.add(el);
      out.push({ el: el, r: r, ptr: ptr, s: 0 });
    }
    var els = document.querySelectorAll(SEL);
    for (i = 0; i < els.length; i++) push(els[i], false);
    var pe = document.querySelectorAll(POINTER_TAGS);
    if (pe.length < 8000) for (i = 0; i < pe.length; i++) push(pe[i], true);
    return out;
  }

  // Lower score = better. -1 = not in that direction.
  function score(a, b, dir) {
    var acx = (a.left + a.right) / 2, acy = (a.top + a.bottom) / 2;
    var bcx = (b.left + b.right) / 2, bcy = (b.top + b.bottom) / 2;
    var prim, orth, off;
    // Skip wrappers that contain the current element
    if (b.left <= a.left && b.right >= a.right && b.top <= a.top && b.bottom >= a.bottom &&
        (b.width > a.width + 4 || b.height > a.height + 4)) return -1;
    if (dir === 'right') {
      if (bcx <= acx + 2 || b.right <= a.right + 2) return -1;
      prim = Math.max(0, b.left - a.right);
      orth = Math.max(0, b.top - a.bottom, a.top - b.bottom);
      off = Math.abs(bcy - acy);
    } else if (dir === 'left') {
      if (bcx >= acx - 2 || b.left >= a.left - 2) return -1;
      prim = Math.max(0, a.left - b.right);
      orth = Math.max(0, b.top - a.bottom, a.top - b.bottom);
      off = Math.abs(bcy - acy);
    } else if (dir === 'down') {
      if (bcy <= acy + 2 || b.bottom <= a.bottom + 2) return -1;
      prim = Math.max(0, b.top - a.bottom);
      orth = Math.max(0, b.left - a.right, a.left - b.right);
      off = Math.abs(bcx - acx);
    } else {
      if (bcy >= acy - 2 || b.top >= a.top - 2) return -1;
      prim = Math.max(0, a.top - b.bottom);
      orth = Math.max(0, b.left - a.right, a.left - b.right);
      off = Math.abs(bcx - acx);
    }
    return prim + orth * 3 + off * 0.35;
  }

  function initScore(r, v) {
    if (r.bottom <= 0 || r.top >= v.h || r.right <= 0 || r.left >= v.w) return -1;
    return Math.max(0, r.top + r.height / 2) + Math.abs((r.left + r.right) / 2 - v.w / 2) * 0.5;
  }

  function usable(c, v) {
    var el = c.el, cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.05 ||
        cs.pointerEvents === 'none' || el.disabled) return false;
    if (c.ptr) {
      if (cs.cursor !== 'pointer') return false;
      var p = el.parentElement;
      if (p && getComputedStyle(p).cursor === 'pointer') return false;
    }
    var cx = (c.r.left + c.r.right) / 2, cy = (c.r.top + c.r.bottom) / 2;
    if (cx >= 0 && cy >= 0 && cx <= v.w && cy <= v.h) {
      var top = document.elementFromPoint(cx, cy);
      if (top && top !== el && !el.contains(top) && !top.contains(el)) {
        if (inFixed(top) && !inFixed(el)) return false; // covered by a modal / sticky bar
      }
    }
    return true;
  }

  function fire(el, types) {
    try {
      var r = el.getBoundingClientRect();
      var x = r.left + r.width / 2, y = r.top + r.height / 2;
      for (var i = 0; i < types.length; i++) {
        el.dispatchEvent(new MouseEvent(types[i], {
          bubbles: types[i] !== 'mouseenter' && types[i] !== 'mouseleave',
          clientX: x, clientY: y, view: window
        }));
      }
    } catch (e) {}
  }

  function reveal(el) {
    try { el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'instant' }); }
    catch (e) { try { el.scrollIntoView(false); } catch (e2) {} }
    var r = el.getBoundingClientRect(), v = vp();
    var m = Math.min(140, v.h * 0.2), dy = 0;
    if (r.height >= v.h - 2 * m) dy = r.top - m;
    else if (r.top < m) dy = r.top - m;
    else if (r.bottom > v.h - m) dy = r.bottom - (v.h - m);
    if (dy) window.scrollBy(0, dy);
  }

  function place() {
    if (!cur || !document.contains(cur)) { ring.style.display = 'none'; return; }
    var r = cur.getBoundingClientRect();
    if (r.width < 1 || r.height < 1) { ring.style.display = 'none'; return; }
    var p = 3;
    ring.style.display = 'block';
    ring.style.left = (r.left - p) + 'px';
    ring.style.top = (r.top - p) + 'px';
    ring.style.width = (r.width + p * 2) + 'px';
    ring.style.height = (r.height + p * 2) + 'px';
  }

  function focusEl(el) {
    if (prevHover && prevHover !== el) fire(prevHover, ['pointerout', 'mouseout', 'mouseleave']);
    cur = el; prevHover = el;
    reveal(el);
    fire(el, ['pointerover', 'mouseover', 'mouseenter', 'mousemove']);
    place();
  }

  function scrollDir(dir) {
    var v = vp(), bx = window.scrollX, by = window.scrollY;
    if (dir === 'down') window.scrollBy(0, v.h * 0.7);
    else if (dir === 'up') window.scrollBy(0, -v.h * 0.7);
    else if (dir === 'right') window.scrollBy(v.w * 0.5, 0);
    else window.scrollBy(-v.w * 0.5, 0);
    return window.scrollX !== bx || window.scrollY !== by;
  }

  function move(dir, allowScroll) {
    mountRing();
    if (cur && !document.contains(cur)) cur = null;
    var v = vp();
    var from = cur ? cur.getBoundingClientRect() : null;
    if (from && (from.width < 1 || from.height < 1)) { cur = null; from = null; }
    var list = collect(v), scored = [], i, c, s;
    for (i = 0; i < list.length; i++) {
      c = list[i];
      if (c.el === cur) continue;
      s = from ? score(from, c.r, dir) : initScore(c.r, v);
      if (s >= 0) { c.s = s; scored.push(c); }
    }
    scored.sort(function (a, b) { return a.s - b.s; });
    var pick = null;
    for (i = 0; i < scored.length && i < 40; i++) {
      if (usable(scored[i], v)) { pick = scored[i]; break; }
    }
    if (pick) { focusEl(pick.el); return; }
    if (allowScroll && scrollDir(dir)) setTimeout(function () { move(dir, false); }, 160);
  }

  function tapPoint(el) {
    var r = el.getBoundingClientRect(), v = vp();
    var fr = [[.5, .5], [.3, .5], [.7, .5], [.5, .3], [.5, .7], [.15, .15], [.85, .85]];
    for (var i = 0; i < fr.length; i++) {
      var x = r.left + r.width * fr[i][0], y = r.top + r.height * fr[i][1];
      x = Math.min(Math.max(x, 2), v.w - 2);
      y = Math.min(Math.max(y, 2), v.h - 2);
      var h = document.elementFromPoint(x, y);
      if (h && (h === el || el.contains(h))) return { x: x, y: y };
    }
    return null;
  }

  // ----- typing -----
  function ask(el) {
    textTarget = el;
    var ty = (el.type || 'text').toLowerCase();
    var val = el.isContentEditable ? (el.innerText || '') : (el.value || '');
    var hint = el.getAttribute('aria-label') || el.placeholder || el.name || '';
    var blob = (el.name || '') + ' ' + (el.id || '') + ' ' + hint + ' ' +
      (el.form ? ((el.form.getAttribute('role') || '') + ' ' + (el.form.action || '')) : '');
    var isSearch = ty === 'search' || /search|query|(^|\W)q(\W|$)/i.test(blob);
    if (window.AndroidTV && AndroidTV.askText) {
      AndroidTV.askText(val, hint, el.tagName === 'TEXTAREA' ? 'multiline' : ty, isSearch);
    }
  }

  function fireKey(el, type, key, code, kc) {
    try {
      var ev = new KeyboardEvent(type, { key: key, code: code, bubbles: true, cancelable: true });
      Object.defineProperty(ev, 'keyCode', { get: function () { return kc; } });
      Object.defineProperty(ev, 'which', { get: function () { return kc; } });
      return el.dispatchEvent(ev);
    } catch (e) { return true; }
  }

  function setText(v, submit) {
    var el = textTarget;
    if (!el || !document.contains(el)) return;
    suppress = true;
    try { el.focus({ preventScroll: true }); } catch (e) {}
    try {
      if (el.isContentEditable) {
        el.textContent = v;
        el.dispatchEvent(new InputEvent('input', { bubbles: true, data: v, inputType: 'insertText' }));
      } else {
        var proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      }
    } catch (e) {}
    if (submit) {
      var notPrevented = fireKey(el, 'keydown', 'Enter', 'Enter', 13);
      fireKey(el, 'keypress', 'Enter', 'Enter', 13);
      fireKey(el, 'keyup', 'Enter', 'Enter', 13);
      if (notPrevented && el.form) {
        try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) {}
      }
    }
    suppress = false;
    if (window.AndroidTV && AndroidTV.hideKeyboard) {
      AndroidTV.hideKeyboard();
      setTimeout(function () { AndroidTV.hideKeyboard(); }, 300);
    }
  }

  // ----- media -----
  function video() {
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var inner = fs.querySelector && fs.querySelector('video');
      if (inner) return inner;
    }
    var vids = document.querySelectorAll('video'), best = null, ba = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect(), a = r.width * r.height;
      if (!vids[i].paused) a += 1e9;
      if (a > ba) { ba = a; best = vids[i]; }
    }
    return best;
  }

  function play(v) { var p = v.play(); if (p && p.catch) p.catch(function () {}); }

  function media(act) {
    var v = video();
    if (!v) return 'none';
    if (act === 'toggle') { if (v.paused) play(v); else v.pause(); }
    else if (act === 'play') play(v);
    else if (act === 'pause') v.pause();
    else if (act === 'ff') v.currentTime = Math.min(v.duration || 1e9, v.currentTime + 10);
    else if (act === 'rw') v.currentTime = Math.max(0, v.currentTime - 10);
    return 'ok';
  }

  function ok() {
    if (cur && !document.contains(cur)) cur = null;
    if (!cur) return 'none';
    var el = cur;
    if (isTextInput(el)) { ask(el); return 'text'; }
    if (el.tagName === 'VIDEO') { if (el.paused) play(el); else el.pause(); return 'video'; }
    var pt = tapPoint(el);
    if (pt && window.AndroidTV && AndroidTV.tap) { AndroidTV.tap(pt.x, pt.y, vp().w); return 'tap'; }
    try { el.click(); } catch (e) {}
    return 'click';
  }

  // Taps (cursor mode / touch) on a text field open the big native typing box too
  ['touchstart', 'mousedown', 'pointerdown'].forEach(function (t) {
    document.addEventListener(t, function () { lastPointer = Date.now(); }, true);
  });
  document.addEventListener('focusin', function (e) {
    if (suppress) return;
    var t = e.target;
    if (!isTextInput(t)) return;
    if (Date.now() - lastPointer > 800) return;
    try { t.blur(); } catch (x) {}
    ask(t);
  }, true);

  setInterval(place, 120);

  window.__tv = {
    nav: function (d) { move(d, true); },
    ok: ok,
    setText: setText,
    media: media,
    scroll: function (d) { scrollDir(d); }
  };
})();
