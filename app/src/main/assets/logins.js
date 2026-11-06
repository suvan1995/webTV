/*
 * Web TV logins (top frame only). Talks to the app through window.wtvLogin, a WebMessageListener
 * the app only honours for the main frame, with the page's real origin attached by WebView.
 *  - ticks "Remember me / Keep me signed in" so sites issue long-lived sessions
 *  - offers to save a username + password when a login form is submitted
 *  - fills a saved login back in next time the site asks for it
 */
(function () {
  'use strict';
  if (window.__wtvLoginInit || window.top !== window) return;
  var L = window.wtvLogin;
  if (!L || typeof L.postMessage !== 'function') return;
  window.__wtvLoginInit = true;

  var creds = null, asked = false, lastSent = '', ticked = new WeakSet(), filledUser = new WeakSet(), filledPass = new WeakSet();
  var USER_RE = /user|email|e-mail|login|account|identifier|phone|mobile|member|signin|sign-in|logon/i;
  var REMEMBER_RE = /remember|keep me|keep_me|keepme|stay ?(signed|logged)|stay_?(signed|logged)|persist|trust (this|my) (device|computer|browser)|don.?t ask again|rememberme|keep_?(signed|logged)|keepsigned|keeploggedin/i;
  var NOT_REMEMBER_RE = /terms|newsletter|marketing|offers|agree|privacy|subscribe|promotion/i;

  function send(o) { try { L.postMessage(JSON.stringify(o)); } catch (e) {} }

  L.onmessage = function (ev) {
    try {
      var m = JSON.parse(ev.data);
      if (m && m.type === 'creds') { creds = m; fill(); }
    } catch (e) {}
  };

  function visible(el) {
    if (!el || el.disabled || el.readOnly) return false;
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var cs = getComputedStyle(el);
    return cs.visibility !== 'hidden' && cs.display !== 'none' && parseFloat(cs.opacity) > 0.05;
  }
  function textLike(t) {
    if (!t || t.tagName !== 'INPUT') return false;
    var ty = (t.getAttribute('type') || 'text').toLowerCase();
    return ty === 'text' || ty === 'email' || ty === 'tel' || ty === '';
  }
  function describe(t) {
    var lab = '';
    try { if (t.labels && t.labels[0]) lab = t.labels[0].textContent; } catch (e) {}
    return [t.name, t.id, t.getAttribute('autocomplete'), t.getAttribute('aria-label'), t.placeholder, lab].join(' ');
  }
  function isUserField(t) {
    if (!textLike(t)) return false;
    var ac = (t.getAttribute('autocomplete') || '').toLowerCase();
    if (ac.indexOf('username') >= 0 || ac === 'email') return true;
    var d = describe(t);
    if (/search|query|coupon|promo|captcha|otp|code|zip|postal/i.test(d)) return false;
    return (t.getAttribute('type') || '').toLowerCase() === 'email' || USER_RE.test(d);
  }
  function passwords() {
    return Array.prototype.filter.call(document.querySelectorAll('input[type=password]'), visible);
  }
  function userFieldFor(pw) {
    var scope = (pw && pw.form) || document;
    var inputs = Array.prototype.slice.call(scope.querySelectorAll('input'));
    var idx = pw ? inputs.indexOf(pw) : inputs.length;
    var fallback = null;
    for (var i = idx - 1; i >= 0; i--) {
      var t = inputs[i];
      if (!textLike(t) || !visible(t)) continue;
      if (isUserField(t)) return t;
      if (!fallback) fallback = t;
    }
    return fallback;
  }
  function standaloneUser() {
    var all = document.querySelectorAll('input');
    for (var i = 0; i < all.length; i++) if (isUserField(all[i]) && visible(all[i])) return all[i];
    return null;
  }
  function setValue(el, v) {
    try {
      el.focus({ preventScroll: true });
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      el.blur();
    } catch (e) {}
  }

  function fill() {
    if (!creds) return;
    var pws = passwords();
    if (pws.length && creds.pass) {
      var pw = pws[0];
      if (!pw.value && !filledPass.has(pw)) { filledPass.add(pw); setValue(pw, creds.pass); }
      var u = userFieldFor(pw);
      if (u && !u.value && creds.user && !filledUser.has(u)) { filledUser.add(u); setValue(u, creds.user); }
      return;
    }
    var su = standaloneUser();
    if (su && !su.value && creds.user && !filledUser.has(su)) { filledUser.add(su); setValue(su, creds.user); }
  }

  function tickRemember() {
    var boxes = document.querySelectorAll('input[type=checkbox], [role=checkbox], [role=switch]');
    for (var i = 0; i < boxes.length; i++) {
      var b = boxes[i];
      if (ticked.has(b)) continue;
      var lab = describe(b) + ' ' + (b.closest('label') ? b.closest('label').textContent : '') +
        ' ' + (b.parentElement ? b.parentElement.textContent : '');
      if (!REMEMBER_RE.test(lab) || NOT_REMEMBER_RE.test(lab)) continue;
      ticked.add(b);
      var on = b.tagName === 'INPUT' ? b.checked : b.getAttribute('aria-checked') === 'true';
      if (!on) { try { b.click(); } catch (e) {} }
    }
  }

  function scan() {
    var pws = passwords();
    var su = pws.length ? null : standaloneUser();
    if (!pws.length && !su) return;
    if (!asked) { asked = true; send({ type: 'need' }); } else fill();
    if (pws.length) tickRemember();
  }

  function capture(scopeEl) {
    var pws = passwords().filter(function (p) { return p.value; });
    if (!pws.length) {
      // Possibly the first step of a two-step login (username, then password on the next page)
      var su = standaloneUser();
      if (su && su.value && su.value.length < 200) send({ type: 'user', user: su.value });
      return;
    }
    // On "new password + confirm" forms keep the last one; on login forms there is just one.
    var pw = pws[pws.length - 1];
    var u = userFieldFor(pws[0]);
    var key = (u ? u.value : '') + '\u0000' + pw.value;
    if (key === lastSent) return;
    lastSent = key;
    send({ type: 'submit', user: u ? u.value : '', pass: pw.value });
  }

  document.addEventListener('submit', function (e) { capture(e.target); }, true);
  document.addEventListener('click', function (e) {
    var t = e.target && e.target.closest ? e.target.closest('button, input[type=submit], input[type=button], [role=button], a') : null;
    if (!t) return;
    var txt = (t.textContent || t.value || t.getAttribute('aria-label') || '') + ' ' + (t.id || '') + ' ' + (t.name || '');
    if (t.type === 'submit' || /log ?in|sign ?in|next|continue|submit|enter|weiter|connexion|iniciar/i.test(txt)) capture(t);
  }, true);
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') capture(e.target);
  }, true);
  window.addEventListener('pagehide', function () { capture(null); });

  var pending = false;
  function kick() {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; scan(); }, 400);
  }
  if (window.MutationObserver) {
    new MutationObserver(kick).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['type', 'style', 'class', 'hidden'] });
  }
  scan();
  setTimeout(scan, 1200);
  setTimeout(scan, 3500);
})();
