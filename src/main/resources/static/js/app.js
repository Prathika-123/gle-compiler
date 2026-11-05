(() => {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const LH = 20, PAD = 10;

  const SAMPLES = [
    { name: 'Missing semicolon', code: `int main() {
int a = 10
return 0;
}
` },
    { name: 'Valid program (no errors)', code: `#include <iostream>
using namespace std;

int add(int a, int b) {
    return a + b;
}

int main() {
    int x = 5, y;
    int arr[3] = {1, 2, 3};
    cin >> y;
    if (x > 3 && y < 10) {
        cout << "small" << endl;
    } else if (x == 3) {
        x = x + 1;
    } else {
        x++;
    }
    for (int i = 0; i < 3; i++) {
        arr[i] = add(i, x) * 2;
    }
    while (x > 0)
        x--;
    switch (y) {
        case 1:
            cout << "one";
            break;
        default:
            cout << "other";
    }
    return 0;
}
` },
    { name: "for header with commas", code: `#include <iostream>
using namespace std;

int main()
{
    int x, y;
    for (x = 1, y < 10, x++)
        cin >> y;
    return 0;
}
` },
    { name: 'if condition closed too early', code: `#include <iostream>
using namespace std;

int main()
{
    int a, b;
    cin >> a >> b;
    if (a * b <= 200) || (b < 200)
        cout << "You win!" << endl;
    return 0;
}
` },
    { name: "Extra '}' closes the function early", code: `#include <iostream>
using namespace std;

int main()
{
    int x = 1;
    for (x = 1; x < 5; x++)
}
    cout << "x" << endl;
}
}
` },
    { name: "Missing '}' at the end", code: `#include <iostream>
using namespace std;

int recursive(int x) {
    if (x > 1) {
        return x * recursive(x - 1);
    } else {
        return 1;
    }
` },
    { name: 'Missing header and namespace', code: `int main() {
    int x = 3;
    cout << x << endl;
    return 0;
}
` },
    { name: "Missing 'using namespace std;'", code: `#include <iostream>

int main() {
    int x = 3;
    cout << x << endl;
    return 0;
}
` },
    { name: 'Several errors in different phases', code: `int main() {
    int x = 3;
    if (x > 2 {
        x = (x + 2;
    }
    switch (x) {
        case 1
            x = ;
            break;
    }
    int 5y = 3;
    x = 5 6;
    return 0;
}
` }
  ];

  const ta = $('code'), gutter = $('gutter'), hlInner = $('hlInner');
  let result = null, sampleIdx = -1, activeLine = 0, relatedLine = 0, errorLines = new Set(), selectedTab = 1, stale = false, activeErr = -1;

  // ---------------------------------------------------------------- editor
  function lineCount() { return ta.value.split('\n').length; }

  function renderGutter() {
    const n = lineCount();
    const frag = document.createDocumentFragment();
    for (let i = 1; i <= n; i++) {
      const d = document.createElement('div');
      d.className = 'ln' + (errorLines.has(i) ? ' err' : '') + (i === activeLine ? ' active' : '');
      d.textContent = i;
      frag.appendChild(d);
    }
    gutter.replaceChildren(frag);
    gutter.scrollTop = ta.scrollTop;
  }

  function renderBars() {
    hlInner.replaceChildren();
    const bar = (line, cls) => {
      const b = document.createElement('div');
      b.className = 'hl-bar ' + cls;
      b.style.top = (PAD + (line - 1) * LH) + 'px';
      hlInner.appendChild(b);
    };
    errorLines.forEach((l) => { if (l !== activeLine) bar(l, 'err'); });
    if (relatedLine && relatedLine !== activeLine) bar(relatedLine, 'related');
    if (activeLine) bar(activeLine, 'active');
    hlInner.style.transform = `translateY(${-ta.scrollTop}px)`;
  }

  function refreshMarks() { renderGutter(); renderBars(); }

  function updateCursor() {
    const before = ta.value.slice(0, ta.selectionStart).split('\n');
    $('cursorPos').textContent = `Ln ${before.length}, Col ${before[before.length - 1].length + 1}`;
  }

  function goTo(line, col, len, related) {
    if (!line) return;
    activeLine = line;
    relatedLine = related || 0;
    const lines = ta.value.split('\n');
    let off = 0;
    for (let i = 0; i < line - 1 && i < lines.length; i++) off += lines[i].length + 1;
    off += Math.max(0, (col || 1) - 1);
    ta.focus({ preventScroll: true });
    ta.setSelectionRange(off, off + (len || 0));
    const top = (line - 1) * LH;
    if (top < ta.scrollTop + LH || top > ta.scrollTop + ta.clientHeight - 3 * LH) ta.scrollTop = Math.max(0, top - 3 * LH);
    refreshMarks();
    updateCursor();
  }

  ta.addEventListener('scroll', () => { gutter.scrollTop = ta.scrollTop; hlInner.style.transform = `translateY(${-ta.scrollTop}px)`; });
  ta.addEventListener('input', () => {
    activeLine = 0; relatedLine = 0;
    if (result && !stale) { stale = true; document.querySelectorAll('.errors, .detail, .pipeline').forEach((e) => e.classList.add('stale')); setStatus('idle', 'Edited since last compile'); }
    refreshMarks(); updateCursor();
  });
  ['keyup', 'click', 'select'].forEach((ev) => ta.addEventListener(ev, updateCursor));
  ta.addEventListener('keydown', (e) => {
    if (e.key === 'Tab') { e.preventDefault(); ta.setRangeText('    ', ta.selectionStart, ta.selectionEnd, 'end'); ta.dispatchEvent(new Event('input')); }
    else if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); compile(); }
  });

  // ---------------------------------------------------------------- status
  function setStatus(kind, text) {
    const s = $('status');
    s.className = 'status ' + kind;
    $('statusText').textContent = text;
  }

  // ---------------------------------------------------------------- compile
  async function compile() {
    const code = ta.value;
    if (!code.trim()) { setStatus('bad', 'Nothing to compile — write or load some code first'); return; }
    $('btnCompile').disabled = true;
    setStatus('busy', 'Compiling…');
    try {
      const res = await fetch('/api/compiler/compile', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sourceCode: code }) });
      const data = await res.json();
      if (!res.ok) { setStatus('bad', data.message || 'Request rejected'); return; }
      result = data; stale = false; activeErr = -1; activeLine = 0; relatedLine = 0;
      document.querySelectorAll('.stale').forEach((e) => e.classList.remove('stale'));
      errorLines = new Set(data.errors.map((e) => e.line));
      
      renderAll();
      setStatus(data.success ? 'ok' : 'bad', data.success ? 'Compiled — no syntax errors' : `${data.errorCount} syntax error${data.errorCount === 1 ? '' : 's'} found`);
    } catch (e) {
      setStatus('bad', 'Cannot reach the compiler service. Is the Spring Boot app running?');
    } finally { $('btnCompile').disabled = false; }
  }

  // ---------------------------------------------------------------- rendering
  function el(tag, cls, text) { const e = document.createElement(tag); if (cls) e.className = cls; if (text !== undefined) e.textContent = text; return e; }

  function renderAll() { refreshMarks(); renderErrors(); renderPipeline(); renderTabs(); renderDetail(); }

  function phaseBadge(p) { return el('span', 'badge p' + p, p === 0 ? 'Lexical' : 'Phase ' + p); }

  function renderErrors() {
    const box = $('errors');
    box.replaceChildren();
    const n = result.errors.length;
    $('errCount').textContent = n;
    $('errCount').classList.toggle('has', n > 0);
    if (!n) { box.appendChild(el('p', 'empty', 'No syntax errors found. Every phase accepted the program.')); return; }
    result.errors.forEach((e, idx) => {
      const card = el('div', 'err-card' + (idx === activeErr ? ' active' : ''));
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      const head = el('div', 'err-title');
      head.append(el('span', 'err-id', 'E' + (idx + 1)), el('strong', null, e.title), phaseBadge(e.phase), el('span', 'err-loc', `line ${e.line}, col ${e.column}`));
      const dl = el('dl', 'fields');
      const row = (k, v, cls, isCode) => {
        if (v === undefined || v === null || v === '') return;
        const dd = el('dd', cls);
        if (isCode) dd.appendChild(el('code', null, v)); else dd.textContent = v;
        dl.append(el('dt', null, k), dd);
      };
      row('Error category', e.category.toLowerCase().replace(/_/g, ' '));
      row('Line / column', `${e.line} / ${e.column}`);
      row('Unexpected token', e.unexpectedToken, null, true);
      row('Expected', e.expected);
      row('Explanation', e.explanation);
      row('Suggested correction', e.suggestion, 'fix');
      if (e.relatedLine) {
        const dd = el('dd');
        const b = el('button', 'link', 'line ' + e.relatedLine);
        b.addEventListener('click', (ev) => { ev.stopPropagation(); goTo(e.relatedLine, 1, 0); });
        dd.appendChild(b);
        dl.append(el('dt', null, 'Related location'), dd);
      }
      row('Parser state', e.parserState, 'muted');
      card.append(head, dl);
      const act = () => { activeErr = idx; document.querySelectorAll('.err-card').forEach((c, i) => c.classList.toggle('active', i === idx)); goTo(e.line, e.column, tokenLen(e), e.relatedLine); };
      card.addEventListener('click', act);
      card.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); act(); } });
      box.appendChild(card);
    });
  }

  function tokenLen(e) {
    const line = ta.value.split('\n')[e.line - 1] || '';
    const t = e.unexpectedToken;
    return t && line.substr(e.column - 1, t.length) === t ? t.length : 0;
  }

  function renderPipeline() {
    const box = $('pipeline');
    box.replaceChildren();
    result.phases.forEach((p) => {
      const b = el('button', 'step ' + (p.status === 'PASSED' ? 'passed' : 'failed') + (selectedTab === p.phase ? ' sel' : ''));
      b.dataset.phase = p.phase;
      b.append(el('div', 's-name', `${p.phase}. ${p.name}`), el('div', 's-ast', p.astName));
      const st = el('div', 's-state');
      st.append(el('i'), el('span', null, p.status === 'PASSED' ? 'Passed' : `${p.errorCount} error${p.errorCount === 1 ? '' : 's'}`));
      b.appendChild(st);
      b.addEventListener('click', () => { selectedTab = p.phase; renderPipeline(); renderTabs(); renderDetail(); });
      box.appendChild(b);
    });
  }

  function renderTabs() {
    const box = $('tabs');
    box.replaceChildren();
    const tabs = [[1, 'Phase 1 · AST-A'], [2, 'Phase 2 · AST-B'], [3, 'Phase 3 · AST-C'], [0, 'Lexical analysis']];
    tabs.forEach(([id, label]) => {
      const b = el('button', 'tab' + (selectedTab === id ? ' sel' : ''), label);
      b.addEventListener('click', () => { selectedTab = id; renderPipeline(); renderTabs(); renderDetail(); });
      box.appendChild(b);
    });
  }

  function renderDetail() {
    const box = $('detail');
    box.replaceChildren();
    if (selectedTab === 0) { renderTokens(box); return; }
    const p = result.phases.find((x) => x.phase === selectedTab);
    box.appendChild(el('h3', null, `Phase ${p.phase}: ${p.name} (${p.astName})`));
    box.appendChild(el('p', 'desc', p.description));
    const errs = result.errors.filter((e) => e.phase === p.phase);
    box.appendChild(el('h4', null, errs.length ? `Errors found in this phase (${errs.length})` : 'No errors found in this phase'));
    if (errs.length) {
      const ul = el('ul', 'phase-errs');
      errs.forEach((e) => {
        const li = el('li'), b = el('button', null, `line ${e.line}:${e.column} — ${e.title}`);
        b.addEventListener('click', () => goTo(e.line, e.column, tokenLen(e), e.relatedLine));
        li.appendChild(b); ul.appendChild(li);
      });
      box.appendChild(ul);
    }
    box.appendChild(el('h4', null, 'What this phase found'));
    box.appendChild(el('pre', 'findings', p.summary.length ? p.summary.join('\n') : 'Nothing to report for this phase.'));
    box.appendChild(el('h4', null, 'Abstract syntax tree'));
    const tree = el('div', 'tree');
    tree.appendChild(astNode(p.ast, 0));
    box.appendChild(tree);
  }

  function astNode(n, depth) {
    const label = (parent) => {
      parent.append(el('span', 'kind', n.kind.toLowerCase().replace(/_/g, ' ')), document.createTextNode(n.label), el('span', 'loc', n.endLine > n.line ? `L${n.line}–${n.endLine}` : `L${n.line}`));
    };
    const meta = (parent) => {
      const attrs = Object.entries(n.attributes || {}).filter(([k]) => k !== 'context').map(([k, v]) => `${k}: ${v}`).join('   ');
      if (n.text && n.text !== n.label) parent.appendChild(el('div', 'meta txt', n.text));
      if (attrs) parent.appendChild(el('div', 'meta', attrs));
      if (n.attributes && n.attributes.context) parent.appendChild(el('div', 'meta', 'in ' + n.attributes.context));
    };
    if (!n.children.length) {
      const d = el('div');
      const row = el('div', 'row leaf');
      label(row);
      row.addEventListener('click', () => goTo(n.line, n.column, 0));
      d.appendChild(row); meta(d);
      return d;
    }
    const det = el('details');
    det.open = depth < 3;
    const sum = el('summary', 'row');
    label(sum);
    sum.addEventListener('click', () => goTo(n.line, n.column, 0));
    det.appendChild(sum);
    const kids = el('div', 'children');
    meta(kids);
    n.children.forEach((c) => kids.appendChild(astNode(c, depth + 1)));
    det.appendChild(kids);
    return det;
  }

  function renderTokens(box) {
    const lx = result.lexical;
    box.appendChild(el('h3', null, 'Lexical analysis'));
    box.appendChild(el('p', 'desc', `${lx.tokenCount} tokens in ${result.lineCount} lines, ${lx.errorCount} lexical error${lx.errorCount === 1 ? '' : 's'}. Each GLE phase reads from this token stream.`));
    const chips = el('div', 'chips');
    Object.entries(lx.stats).forEach(([k, v]) => chips.appendChild(el('span', 'chip', `${k.toLowerCase().replace(/_/g, ' ')}: ${v}`)));
    box.appendChild(chips);
    const table = el('table', 'tokens');
    const thead = el('thead'), hr = el('tr');
    ['#', 'Line:Col', 'Type', 'Text'].forEach((h) => hr.appendChild(el('th', null, h)));
    thead.appendChild(hr); table.appendChild(thead);
    const tb = el('tbody');
    lx.tokens.slice(0, 2000).forEach((t, i) => {
      const tr = el('tr', t.type === 'INVALID' ? 'bad' : '');
      [i + 1, `${t.line}:${t.column}`, t.type.toLowerCase().replace(/_/g, ' '), t.text].forEach((v) => tr.appendChild(el('td', null, String(v))));
      tr.addEventListener('click', () => goTo(t.line, t.column, t.text.length));
      tb.appendChild(tr);
    });
    table.appendChild(tb);
    box.appendChild(table);
    if (lx.tokens.length > 2000) box.appendChild(el('p', 'empty', `Showing the first 2000 of ${lx.tokens.length} tokens.`));
  }

  // ---------------------------------------------------------------- buttons
  $('btnCompile').addEventListener('click', compile);
  $('btnSample').addEventListener('click', () => {
    sampleIdx = (sampleIdx + 1) % SAMPLES.length;
    ta.value = SAMPLES[sampleIdx].code;
    $('sampleName').textContent = `Sample ${sampleIdx + 1}/${SAMPLES.length}: ${SAMPLES[sampleIdx].name}`;
    ta.scrollTop = 0; activeLine = 0; relatedLine = 0; refreshMarks(); updateCursor();
    if (result) { stale = true; document.querySelectorAll('.errors, .detail, .pipeline').forEach((e) => e.classList.add('stale')); }
    setStatus('idle', 'Sample loaded — press Compile');
  });
  $('btnClear').addEventListener('click', () => {
    ta.value = ''; result = null; stale = false; errorLines = new Set(); activeLine = 0; relatedLine = 0; activeErr = -1; sampleIdx = -1;
    $('sampleName').textContent = '';
    $('errors').replaceChildren(el('p', 'empty', 'Compile your code to see errors here. Click an error to jump to its line.'));
    $('errCount').textContent = '0'; $('errCount').classList.remove('has');
    ['pipeline', 'tabs'].forEach((id) => $(id).replaceChildren());
    $('detail').replaceChildren(el('p', 'empty', 'The three GLE phases run in order: functions first, then control structures, then statements and expressions. Results appear here after you compile.'));
    document.querySelectorAll('.stale').forEach((e) => e.classList.remove('stale'));
    refreshMarks(); updateCursor(); setStatus('idle', 'Ready'); ta.focus();
  });

  // initial state: load the first sample so the page is never empty
  $('btnSample').click();
  setStatus('idle', 'Ready');
})();
