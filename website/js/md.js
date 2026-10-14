// Small Markdown renderer for text fetched from GitHub (PRIVACY.md files and
// release notes). It builds DOM nodes with createElement and textContent only:
// nothing in the fetched text can become markup or script, so no sanitizer
// library is needed. Supported: headings (ATX and setext), paragraphs, hard
// breaks, bold, italics, inline code, fenced code, links, autolinks, bare
// URLs, block quotes, nested lists, tables and horizontal rules. Raw HTML is
// shown as text, except block-level tag lines, which are dropped.
//
// Hostile input is bounded: the text is capped, nesting is capped, and every
// scan for a closing marker looks a limited distance ahead, so a crafted file
// can't freeze the page.
(function () {
  'use strict';

  var MAX_INPUT = 200000;
  var MAX_DEPTH = 12;
  var MAX_SCAN = 2000; // how far ahead a closing marker is looked for
  var SAFE_PROTOCOL = /^(https?:|mailto:)/i;

  function slugify(text) {
    return String(text)
      .toLowerCase()
      .trim()
      .replace(/[^\p{L}\p{N}\s-]/gu, '')
      .replace(/\s+/g, '-');
  }

  // Resolve a link target: absolute http(s)/mailto stays, in-page anchors get
  // the heading prefix, repository-relative paths resolve against baseUrl.
  function resolveHref(raw, opts) {
    var href = String(raw || '').trim();
    if (!href) return null;
    if (href.charAt(0) === '#') return '#' + (opts.idPrefix || '') + slugify(href.slice(1));
    if (SAFE_PROTOCOL.test(href)) return href;
    if (/^[a-z][a-z0-9+.-]*:/i.test(href)) return null; // javascript:, data:, etc.
    if (!opts.baseUrl) return null;
    try {
      var url = new URL(href, opts.baseUrl);
      return SAFE_PROTOCOL.test(url.href) ? url.href : null;
    } catch (e) {
      return null;
    }
  }

  function makeLink(href) {
    var a = document.createElement('a');
    a.href = href;
    if (/^https?:/i.test(href)) {
      var external = true;
      try {
        external = new URL(href).origin !== window.location.origin;
      } catch (e) { /* keep external */ }
      if (external) {
        a.target = '_blank';
        a.rel = 'noopener noreferrer';
      }
    }
    return a;
  }

  // Called after a link's text is in place
  function finishLink(a) {
    if (a.target === '_blank') {
      var note = document.createElement('span');
      note.className = 'sr-only';
      note.textContent = ' (opens in a new tab)';
      a.appendChild(note);
    }
  }

  // ---------------------------------------------------------------
  // Inline
  // ---------------------------------------------------------------
  function findClosing(text, from, marker) {
    var limit = from + MAX_SCAN;
    var i = text.indexOf(marker, from);
    while (i !== -1 && i < limit && text.charAt(i - 1) === '\\') i = text.indexOf(marker, i + 1);
    return i !== -1 && i < limit ? i : -1;
  }

  // Index of the bracket that closes the one at `open`, or -1
  function matchBracket(text, open) {
    var depth = 0;
    var end = Math.min(text.length, open + MAX_SCAN);
    for (var i = open; i < end; i++) {
      var c = text.charAt(i);
      if (c === '\\') { i++; continue; }
      if (c === '[') depth++;
      else if (c === ']') {
        depth--;
        if (depth === 0) return i;
      }
    }
    return -1;
  }

  function isWordChar(c) {
    return !!c && /[\p{L}\p{N}_]/u.test(c);
  }

  function inline(text, parent, opts, depth) {
    if (depth > MAX_DEPTH) {
      parent.appendChild(document.createTextNode(text));
      return;
    }
    var buf = '';
    function flush() {
      if (buf) {
        parent.appendChild(document.createTextNode(buf));
        buf = '';
      }
    }
    function lastChar() {
      return buf.charAt(buf.length - 1);
    }

    var i = 0;
    while (i < text.length) {
      var c = text.charAt(i);
      var rest;
      var m;

      // Escaped punctuation
      if (c === '\\' && i + 1 < text.length && /[\\`*_{}\[\]()#+\-.!|<>~]/.test(text.charAt(i + 1))) {
        buf += text.charAt(i + 1);
        i += 2;
        continue;
      }

      // Hard line break: two spaces before a newline
      if (c === '\n') {
        if (buf.slice(-2) === '  ') {
          buf = buf.replace(/ +$/, '');
          flush();
          parent.appendChild(document.createElement('br'));
        } else {
          buf += ' ';
        }
        i++;
        continue;
      }

      // Inline code
      if (c === '`') {
        var n = 1;
        while (text.charAt(i + n) === '`' && n < 16) n++;
        var ticks = text.substr(i, n);
        var end = findClosing(text, i + n, ticks);
        if (end !== -1) {
          flush();
          var code = document.createElement('code');
          var inner = text.slice(i + n, end);
          if (inner.length > 2 && inner.charAt(0) === ' ' && inner.charAt(inner.length - 1) === ' ') inner = inner.slice(1, -1);
          code.textContent = inner;
          parent.appendChild(code);
          i = end + n;
          continue;
        }
      }

      // Autolink <https://...>
      if (c === '<') {
        rest = text.substr(i, MAX_SCAN);
        if ((m = /^<((?:https?:\/\/|mailto:)[^\s<>]+)>/i.exec(rest))) {
          flush();
          var auto = makeLink(m[1]);
          auto.textContent = m[1].replace(/^mailto:/i, '');
          finishLink(auto);
          parent.appendChild(auto);
          i += m[0].length;
          continue;
        }
      }

      // Image: show its alt text only (no remote images on this site)
      if (c === '!' && text.charAt(i + 1) === '[') {
        var imgClose = matchBracket(text, i + 1);
        if (imgClose !== -1 && text.charAt(imgClose + 1) === '(') {
          var imgEnd = text.indexOf(')', imgClose + 2);
          if (imgEnd !== -1 && imgEnd - imgClose < MAX_SCAN) {
            buf += text.slice(i + 2, imgClose);
            i = imgEnd + 1;
            continue;
          }
        }
      }

      // Link [text](url "title")
      if (c === '[') {
        var close = matchBracket(text, i);
        if (close !== -1 && text.charAt(close + 1) === '(') {
          var paren = /^\(\s*<?([^\s)>]*)>?(?:\s+"[^"]*")?\s*\)/.exec(text.substr(close + 1, MAX_SCAN));
          if (paren) {
            flush();
            var href = resolveHref(paren[1], opts);
            var label = text.slice(i + 1, close);
            if (href) {
              var a = makeLink(href);
              inline(label, a, opts, depth + 1);
              finishLink(a);
              parent.appendChild(a);
            } else {
              inline(label, parent, opts, depth + 1);
            }
            i = close + 1 + paren[0].length;
            continue;
          }
        }
      }

      // Bold
      if ((c === '*' || c === '_') && text.charAt(i + 1) === c) {
        var bEnd = findClosing(text, i + 2, c + c);
        if (bEnd > i + 2) {
          flush();
          var strong = document.createElement('strong');
          inline(text.slice(i + 2, bEnd), strong, opts, depth + 1);
          parent.appendChild(strong);
          i = bEnd + 2;
          continue;
        }
      }

      // Italic (underscores only at word boundaries)
      if (c === '*' || (c === '_' && !isWordChar(lastChar()))) {
        var iEnd = findClosing(text, i + 1, c);
        if (iEnd > i + 1 && text.charAt(i + 1) !== ' ' &&
            (c === '*' || !isWordChar(text.charAt(iEnd + 1)))) {
          flush();
          var em = document.createElement('em');
          inline(text.slice(i + 1, iEnd), em, opts, depth + 1);
          parent.appendChild(em);
          i = iEnd + 1;
          continue;
        }
      }

      // Bare URL (GitHub turns these into links)
      if ((c === 'h' || c === 'H') && !isWordChar(lastChar())) {
        rest = text.substr(i, MAX_SCAN);
        if ((m = /^https?:\/\/[^\s<>()]+(?:\([^\s<>()]*\)[^\s<>()]*)*/i.exec(rest))) {
          var url = m[0].replace(/[.,;:!?'"]+$/, '');
          flush();
          var bare = makeLink(url);
          bare.textContent = url;
          finishLink(bare);
          parent.appendChild(bare);
          i += url.length;
          continue;
        }
      }

      buf += c;
      i++;
    }
    flush();
  }

  // ---------------------------------------------------------------
  // Line tests (written to run in linear time)
  // ---------------------------------------------------------------
  function leadingSpaces(line) {
    var n = 0;
    while (line.charAt(n) === ' ') n++;
    return n;
  }

  // { level, text } for an ATX heading, else null
  function parseHeading(line) {
    var indent = leadingSpaces(line);
    if (indent > 3) return null;
    var i = indent;
    var level = 0;
    while (line.charAt(i) === '#' && level < 7) { level++; i++; }
    if (level < 1 || level > 6) return null;
    var after = line.charAt(i);
    if (after && after !== ' ' && after !== '\t') return null;
    var text = line.slice(i).trim();
    // Strip a closing run of #, when it is separated by a space
    var j = text.length;
    while (j > 0 && text.charAt(j - 1) === '#') j--;
    if (j === 0) text = '';
    else if (j < text.length && /[ \t]/.test(text.charAt(j - 1))) text = text.slice(0, j).trim();
    return { level: level, text: text };
  }

  // { marker, info } for a code fence, else null
  function parseFence(line) {
    if (leadingSpaces(line) > 3) return null;
    var t = line.trim();
    var ch = t.charAt(0);
    if (ch !== '`' && ch !== '~') return null;
    var n = 0;
    while (t.charAt(n) === ch) n++;
    if (n < 3) return null;
    var info = t.slice(n).trim();
    if (!/^[\w-]*$/.test(info)) return null;
    return { marker: t.slice(0, n) };
  }

  function isRule(line) {
    if (leadingSpaces(line) > 3) return false;
    var t = line.replace(/[ \t]/g, '');
    return t.length >= 3 && /^(-+|\*+|_+)$/.test(t);
  }

  function isTableSeparator(line) {
    return line.indexOf('-') !== -1 && /^[\s|:-]+$/.test(line) && (line.indexOf('|') !== -1 || /^\s*:?-+:?\s*$/.test(line));
  }

  var RE_LIST = /^( *)([-*+]|\d{1,9}[.)])[ \t]+(.*)$/;
  var RE_QUOTE = /^ {0,3}> ?(.*)$/;

  function isHtmlLine(line) {
    var t = line.trim();
    return t.charAt(0) === '<' && t.charAt(t.length - 1) === '>' && /^<\/?[a-zA-Z]/.test(t) && t.length < 400;
  }

  function isTableStart(line, next) {
    return line.indexOf('|') !== -1 && next !== undefined && isTableSeparator(next);
  }

  function isBlockStart(line, next) {
    return !!(parseFence(line) || parseHeading(line) || isRule(line) || RE_LIST.test(line) || RE_QUOTE.test(line) || isTableStart(line, next));
  }

  function splitRow(line) {
    var row = line.trim();
    if (row.charAt(0) === '|') row = row.slice(1);
    if (row.charAt(row.length - 1) === '|' && row.charAt(row.length - 2) !== '\\') row = row.slice(0, -1);
    var cells = [];
    var cur = '';
    for (var i = 0; i < row.length; i++) {
      var ch = row.charAt(i);
      if (ch === '\\' && row.charAt(i + 1) === '|') {
        cur += '|';
        i++;
      } else if (ch === '|') {
        cells.push(cur.trim());
        cur = '';
      } else {
        cur += ch;
      }
    }
    cells.push(cur.trim());
    return cells.slice(0, 20);
  }

  // ---------------------------------------------------------------
  // Blocks
  // ---------------------------------------------------------------
  function blocks(lines, parent, opts, depth) {
    if (depth > MAX_DEPTH) {
      var plain = document.createElement('p');
      plain.textContent = lines.join(' ');
      parent.appendChild(plain);
      return;
    }
    var i = 0;
    var para = [];

    function flushPara() {
      if (!para.length) return;
      var p = document.createElement('p');
      inline(para.join('\n').trim(), p, opts, 0);
      parent.appendChild(p);
      para = [];
    }

    function heading(level, text) {
      var h = document.createElement('h' + Math.min(6, level + (opts.headingOffset || 0)));
      inline(text.trim(), h, opts, 0);
      var base = (opts.idPrefix || '') + slugify(h.textContent);
      var id = base;
      var n = 1;
      while (Object.prototype.hasOwnProperty.call(opts.usedIds, id)) id = base + '-' + (n++);
      opts.usedIds[id] = true;
      h.id = id;
      parent.appendChild(h);
    }

    while (i < lines.length) {
      var line = lines[i];
      var next = lines[i + 1];
      var m;
      var h;
      var fence;

      if (!line.trim()) {
        flushPara();
        i++;
        continue;
      }

      // Setext heading: a paragraph line underlined with === or ---
      if (para.length && /^ {0,3}(=+|-+)[ \t]*$/.test(line)) {
        var text = para.join(' ');
        para = [];
        heading(line.trim().charAt(0) === '=' ? 1 : 2, text);
        i++;
        continue;
      }

      if ((fence = parseFence(line))) {
        flushPara();
        var body = [];
        i++;
        while (i < lines.length && lines[i].trim().indexOf(fence.marker) !== 0) {
          body.push(lines[i]);
          i++;
        }
        i++; // closing fence
        var pre = document.createElement('pre');
        var code = document.createElement('code');
        code.textContent = body.join('\n');
        pre.appendChild(code);
        parent.appendChild(pre);
        continue;
      }

      if ((h = parseHeading(line))) {
        flushPara();
        heading(h.level, h.text);
        i++;
        continue;
      }

      if (isRule(line)) {
        flushPara();
        parent.appendChild(document.createElement('hr'));
        i++;
        continue;
      }

      if (isHtmlLine(line)) {
        flushPara();
        i++;
        continue;
      }

      if (RE_QUOTE.test(line)) {
        flushPara();
        var quoted = [];
        while (i < lines.length && (m = RE_QUOTE.exec(lines[i]))) {
          quoted.push(m[1]);
          i++;
        }
        var bq = document.createElement('blockquote');
        blocks(quoted, bq, opts, depth + 1);
        parent.appendChild(bq);
        continue;
      }

      if (isTableStart(line, next)) {
        flushPara();
        var head = splitRow(line);
        var aligns = splitRow(next).map(function (cell) {
          var left = cell.charAt(0) === ':';
          var right = cell.charAt(cell.length - 1) === ':';
          return left && right ? 'center' : right ? 'right' : left ? 'left' : '';
        });
        i += 2;
        var wrap = document.createElement('div');
        wrap.className = 'table-scroll';
        var table = document.createElement('table');
        var thead = document.createElement('thead');
        var trh = document.createElement('tr');
        head.forEach(function (cell, ci) {
          var th = document.createElement('th');
          th.scope = 'col';
          if (aligns[ci]) th.style.textAlign = aligns[ci];
          inline(cell, th, opts, 0);
          trh.appendChild(th);
        });
        thead.appendChild(trh);
        table.appendChild(thead);
        var tbody = document.createElement('tbody');
        while (i < lines.length && lines[i].trim() && lines[i].indexOf('|') !== -1) {
          var tr = document.createElement('tr');
          splitRow(lines[i]).forEach(function (cell, ci) {
            if (ci >= head.length) return;
            var td = document.createElement('td');
            if (aligns[ci]) td.style.textAlign = aligns[ci];
            inline(cell, td, opts, 0);
            tr.appendChild(td);
          });
          tbody.appendChild(tr);
          i++;
        }
        table.appendChild(tbody);
        wrap.appendChild(table);
        parent.appendChild(wrap);
        continue;
      }

      if (RE_LIST.test(line)) {
        flushPara();
        i = list(lines, i, parent, opts, depth);
        continue;
      }

      // Paragraph text; a block start ends the paragraph without a blank line
      if (para.length && isBlockStart(line, next)) {
        flushPara();
        continue;
      }
      para.push(line);
      i++;
    }
    flushPara();
  }

  // Parses one list starting at lines[start]; returns the index after it.
  function list(lines, start, parent, opts, depth) {
    var first = RE_LIST.exec(lines[start]);
    var baseIndent = first[1].length;
    var ordered = /\d/.test(first[2]);
    var el = document.createElement(ordered ? 'ol' : 'ul');
    if (ordered) {
      var startNum = parseInt(first[2], 10);
      if (startNum !== 1) el.start = startNum;
    }
    var items = [];
    var i = start;
    var current = null;
    var sawBlank = false;

    while (i < lines.length) {
      var line = lines[i];
      var m = RE_LIST.exec(line);
      if (m && m[1].length === baseIndent && /\d/.test(m[2]) === ordered) {
        current = { lines: [m[3]], loose: sawBlank && items.length > 0 };
        items.push(current);
        sawBlank = false;
        i++;
        continue;
      }
      if (!line.trim()) {
        // A blank line continues the list only if indented content follows
        var nextLine = lines[i + 1];
        var nextItem = nextLine !== undefined ? RE_LIST.exec(nextLine) : null;
        if (nextLine !== undefined && ((nextLine.trim() && leadingSpaces(nextLine) > baseIndent) ||
            (nextItem && nextItem[1].length === baseIndent))) {
          sawBlank = true;
          if (current) current.lines.push('');
          i++;
          continue;
        }
        break;
      }
      if (leadingSpaces(line) > baseIndent && current) {
        current.lines.push(line.slice(Math.min(leadingSpaces(line), baseIndent + 2)));
        i++;
        continue;
      }
      if (m && m[1].length < baseIndent) break;
      // Lazy continuation of the item's paragraph
      if (current && !sawBlank && !isBlockStart(line, lines[i + 1])) {
        current.lines.push(line.trim());
        i++;
        continue;
      }
      break;
    }

    items.forEach(function (item) {
      var li = document.createElement('li');
      var hasNested = item.lines.some(function (l) { return RE_LIST.test(l) || !l.trim(); });
      if (!hasNested && !item.loose) {
        inline(item.lines.join('\n').trim(), li, opts, 0);
      } else {
        // Render the item body as blocks, then unwrap a lone first paragraph for tight lists
        blocks(item.lines, li, opts, depth + 1);
        if (!item.loose && li.firstChild && li.firstChild.tagName === 'P') {
          var p = li.firstChild;
          while (p.firstChild) li.insertBefore(p.firstChild, p);
          li.removeChild(p);
        }
      }
      el.appendChild(li);
    });
    parent.appendChild(el);
    return Math.max(i, start + 1);
  }

  // Public: render(markdown, { baseUrl, idPrefix, headingOffset }) -> DocumentFragment
  function render(markdown, options) {
    var opts = {
      baseUrl: (options && options.baseUrl) || '',
      idPrefix: (options && options.idPrefix) || '',
      headingOffset: (options && options.headingOffset) || 0,
      usedIds: Object.create(null)
    };
    var text = String(markdown || '')
      .slice(0, MAX_INPUT)
      .replace(/\r\n?|[\u2028\u2029\u0085]/g, '\n')
      .replace(/\t/g, '    ');
    var frag = document.createDocumentFragment();
    blocks(text.split('\n'), frag, opts, 0);
    return frag;
  }

  window.VPMarkdown = { render: render, slugify: slugify };
})();
