// Runs in <head> before first paint: marks JS as available (for the load
// animation) and records the visitor's platform so the right download is
// offered first. Kept tiny on purpose.
(function () {
  'use strict';
  var root = document.documentElement;
  root.classList.add('js');

  var ua = navigator.userAgent || '';
  var hint = navigator.userAgentData && navigator.userAgentData.platform;
  var platform = 'other';
  if (/android/i.test(ua) || hint === 'Android') platform = 'android';
  else if (/iPhone|iPad|iPod/i.test(ua) || (/Macintosh/i.test(ua) && navigator.maxTouchPoints > 1)) platform = 'ios';
  else if (/Windows/i.test(ua) || hint === 'Windows') platform = 'windows';
  else if (/Macintosh|Mac OS X/i.test(ua) || hint === 'macOS') platform = 'mac';
  else if (/Linux|CrOS/i.test(ua)) platform = 'linux';
  root.setAttribute('data-platform', platform);

  // The home page's demos: both apps side by side on wide screens, otherwise
  // one app, Android on phones and Android devices. Set here, before the first
  // paint, so the layout never jumps; stage.js reuses this rule.
  function deskView() {
    if (!window.matchMedia) return 'dual';
    if (window.matchMedia('(min-width: 1212px)').matches) return 'dual';
    return platform === 'android' || window.matchMedia('(max-width: 671px)').matches ? 'android' : 'desktop';
  }
  window.VaultPassDeskView = deskView;
  root.setAttribute('data-desk-view', deskView());

  // The privacy page's first policy: #windows / #android (also deep links
  // like #windows-3-...), else the visitor's platform. privacy.js uses the
  // same rule; CSS shows only this policy until privacy.js has run.
  var hash = location.hash.slice(1);
  try { hash = decodeURIComponent(hash); } catch (e) { /* keep it encoded */ }
  var policy = /^(android|windows)\b/.exec(hash);
  root.setAttribute('data-policy', policy ? policy[1] : platform === 'windows' ? 'windows' : 'android');
})();
