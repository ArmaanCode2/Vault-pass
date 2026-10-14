// Privacy page: renders PRIVACY.md from each app's GitHub repository, with a
// table of contents built from its headings. Edit PRIVACY.md on GitHub and
// this page shows the new text on the next visit.
(function () {
  'use strict';

  var GH = window.VPGitHub;
  var MD = window.VPMarkdown;
  var tabs = Array.prototype.slice.call(document.querySelectorAll('[data-policy-tab]'));
  if (!tabs.length) return;

  function panelFor(id) {
    return document.getElementById('policy-' + id);
  }

  function select(id, focus) {
    tabs.forEach(function (tab) {
      var on = tab.getAttribute('data-policy-tab') === id;
      tab.setAttribute('aria-selected', String(on));
      tab.tabIndex = on ? 0 : -1;
      panelFor(tab.getAttribute('data-policy-tab')).hidden = !on;
      if (on && focus) tab.focus();
    });
  }

  tabs.forEach(function (tab, i) {
    tab.addEventListener('click', function () {
      var id = tab.getAttribute('data-policy-tab');
      select(id);
      if (history.replaceState) history.replaceState(null, '', '#' + id);
    });
    tab.addEventListener('keydown', function (e) {
      var next = null;
      if (e.key === 'ArrowRight') next = tabs[(i + 1) % tabs.length];
      else if (e.key === 'ArrowLeft') next = tabs[(i - 1 + tabs.length) % tabs.length];
      else if (e.key === 'Home') next = tabs[0];
      else if (e.key === 'End') next = tabs[tabs.length - 1];
      if (!next) return;
      e.preventDefault();
      next.click();
      next.focus();
    });
  });

  // privacy.html#windows opens the Windows policy; #windows-3-local-... deep links too
  function fromHash() {
    var hash = location.hash.slice(1);
    try { hash = decodeURIComponent(hash); } catch (e) { /* keep it encoded */ }
    var match = /^(android|windows)\b/.exec(hash);
    return match ? match[1] : null;
  }
  select(fromHash() || (document.documentElement.getAttribute('data-platform') === 'windows' ? 'windows' : 'android'));
  document.documentElement.classList.add('policy-ready');

  // The contents list: a sidebar that stays open on wide screens; on narrow
  // screens a closed "On this page" box, so filling it moves nothing
  var wide = window.matchMedia('(min-width: 961px)');
  function syncToc() {
    Array.prototype.forEach.call(document.querySelectorAll('.toc__box'), function (box) {
      var summary = box.querySelector('summary');
      box.open = wide.matches;
      // On wide screens the label is plain text, not a control
      if (wide.matches) {
        summary.tabIndex = -1;
        summary.setAttribute('aria-hidden', 'true');
      } else {
        summary.removeAttribute('tabindex');
        summary.removeAttribute('aria-hidden');
      }
    });
  }
  syncToc();
  if (wide.addEventListener) wide.addEventListener('change', syncToc);
  else if (wide.addListener) wide.addListener(syncToc);
  window.addEventListener('hashchange', function () {
    var id = fromHash();
    if (id) select(id);
  });

  function buildToc(panel, content) {
    var list = panel.querySelector('[data-toc]');
    if (!list) return;
    list.textContent = '';
    var headings = Array.prototype.slice.call(content.querySelectorAll('h2'));
    headings.forEach(function (h) {
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '#' + h.id;
      a.textContent = h.textContent.replace(/^\d+\.\s*/, '');
      li.appendChild(a);
      list.appendChild(li);
    });
    // visibility, not hidden: the box keeps its place, so nothing moves
    panel.querySelector('.toc').style.visibility = headings.length ? '' : 'hidden';

    if (!('IntersectionObserver' in window) || !headings.length) return;
    var links = Array.prototype.slice.call(list.querySelectorAll('a'));
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        links.forEach(function (link) {
          link.classList.toggle('is-current', link.getAttribute('href') === '#' + entry.target.id);
        });
      });
    }, { rootMargin: '-20% 0px -70% 0px' });
    headings.forEach(function (h) { observer.observe(h); });
  }

  var rendered = {};   // appId -> the markdown currently shown
  var scrolled = false;

  function statusText(info) {
    if (info.failed) return 'GitHub didn\u2019t answer, so this is a copy saved ' + GH.formatAge(info.ageMs || 0) + '.';
    if (info.fromCache) {
      return info.refreshing
        ? 'Saved copy; checking GitHub.'
        : 'Loaded from GitHub ' + GH.formatAge(info.ageMs || 0) + '.';
    }
    return 'Up to date with GitHub.';
  }

  function render(appId, entry) {
    var panel = panelFor(appId);
    var content = panel.querySelector('[data-policy-content]');
    var status = panel.querySelector('[data-policy-status]');
    var effective = panel.querySelector('[data-policy-date]');
    var info = entry.info || {};

    if (!entry.data) {
      content.textContent = '';
      var p = document.createElement('p');
      p.appendChild(document.createTextNode('The policy couldn\u2019t be loaded from GitHub. Read it there instead: '));
      var a = document.createElement('a');
      a.href = GH.blobUrl(appId, 'PRIVACY.md');
      a.textContent = 'PRIVACY.md on GitHub';
      a.target = '_blank';
      a.rel = 'noopener noreferrer';
      p.appendChild(a);
      p.appendChild(document.createTextNode('.'));
      content.appendChild(p);
      status.textContent = 'GitHub couldn\u2019t be reached.';
      panel.querySelector('.toc').style.visibility = 'hidden';
      effective.parentElement.style.visibility = 'hidden';
      return;
    }

    status.textContent = statusText(info);
    var source = String(entry.data);
    if (rendered[appId] === source) return; // same text: only the status changed

    var markdown = source;
    var date = /\*\*Effective Date:?\*\*:?[ \t]*([^\n]{1,80})/i.exec(markdown);
    effective.textContent = date ? date[1].trim() : '';
    effective.parentElement.style.visibility = date ? '' : 'hidden';
    if (date) markdown = markdown.replace(date[0], '');

    var frag;
    try {
      frag = MD.render(markdown, { baseUrl: GH.blobUrl(appId, ''), idPrefix: appId + '-' });
    } catch (e) {
      if (window.console) console.warn('VaultPass: the policy could not be shown.', e);
      return; // keep whatever is on screen
    }
    content.textContent = '';
    content.appendChild(frag);
    rendered[appId] = source;
    buildToc(panel, content);

    // A deep link to a heading can only scroll once the heading exists, and only once
    var target = location.hash.slice(1);
    if (!scrolled && target && target.indexOf(appId + '-') === 0) {
      var heading = document.getElementById(target);
      if (heading) {
        heading.scrollIntoView();
        scrolled = true;
      }
    }
  }

  // The tabs above work even if the GitHub or markdown helpers failed to load
  if (!GH || !MD) return;
  ['android', 'windows'].forEach(function (appId) {
    GH.onDocument(appId, 'PRIVACY.md', function (entry) { render(appId, entry); });
  });
})();
