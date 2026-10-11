import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const source = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)]
  .map(match => match[1]).find(script => script.includes('function startSearch()'));
assert.ok(source, 'dashboard script must exist');

// The native bridge and browser DOM are mocked. Network, Android storage, and real
// playback remain separate verification requirements.
function dashboard(saved = {}, hasKey = true) {
  const nodes = new Map();
  const elements = [];
  class Element {
    constructor(tag = 'div') {
      this.tagName = tag; this.dataset = {}; this.style = {}; this.children = [];
      this.events = {}; this.queries = new Map(); this.classes = new Set();
      this.checked = false; this.value = ''; this.id = '';
      this.classList = {
        contains: value => this.classes.has(value),
        toggle: (value, force) => {
          const add = force ?? !this.classes.has(value);
          if (add) this.classes.add(value); else this.classes.delete(value);
          return add;
        },
        add: value => this.classes.add(value),
        remove: value => this.classes.delete(value)
      };
    }
    set className(value) { this.classes = new Set(String(value).split(/\s+/).filter(Boolean)); }
    get className() { return [...this.classes].join(' '); }
    set value(value) { this.inputValue = String(value); }
    get value() { return this.inputValue; }
    set innerHTML(value) { this.markup = value; this.children = []; this.queries.clear(); }
    get innerHTML() { return this.markup || ''; }
    set textContent(value) { this.text = String(value); this.children = []; }
    get textContent() { return this.text || ''; }
    get lastChild() { return this.children.at(-1); }
    appendChild(child) { child.parent = this; this.children.push(child); return child; }
    prepend(child) { child.parent = this; this.children.unshift(child); }
    replaceChildren(...children) { this.children = []; children.forEach(child => this.appendChild(child)); }
    remove() { if (this.parent) this.parent.children = this.parent.children.filter(child => child !== this); }
    querySelector(selector) {
      if (!this.queries.has(selector)) this.queries.set(selector, new Element());
      return this.queries.get(selector);
    }
    insertAdjacentHTML(_position, markup) { this.markup = (this.markup || '') + markup; }
    addEventListener(event, callback) { (this.events[event] ||= []).push(callback); }
    dispatch(event) {
      for (const callback of this.events[event] || []) callback({ target: this });
      this[`on${event}`]?.({ target: this });
    }
  }
  const markup = html.split('<script')[0];
  for (const match of markup.matchAll(/<([a-z][a-z0-9]*)\b([^>]*)>/gi)) {
    const el = new Element(match[1]);
    for (const attribute of match[2].matchAll(/([\w-]+)\s*=\s*(["'])(.*?)\2/g)) {
      const [, key, , value] = attribute;
      if (key === 'class') el.className = value;
      if (key === 'id') { el.id = value; nodes.set(value, el); }
      if (key === 'value') el.value = value;
      if (key.startsWith('data-')) el.dataset[key.slice(5)] = value;
    }
    elements.push(el);
  }
  const calls = [];
  let persisted = structuredClone(saved);
  const native = new Proxy({}, {
    get(_target, method) {
      if (method === 'getState') return () => typeof saved === 'string' ? saved : JSON.stringify(saved);
      if (method === 'saveState') return value => { persisted = JSON.parse(value); };
      if (method === 'hasApiKey') return () => hasKey;
      if (method === 'environment') return () => JSON.stringify({ online: true, battery: 75 });
      return (...args) => { calls.push({ method, args }); };
    }
  });
  let timerId = 0;
  const timers = new Map();
  const window = { ReconNative: native };
  const context = {
    window, document: {
      title: '',
      getElementById: id => { assert.ok(nodes.has(id), `missing control: ${id}`); return nodes.get(id); },
      createElement: tag => new Element(tag),
      querySelectorAll: selector => {
        if (selector === '.nav button') return elements.filter(el => el.dataset.view);
        if (selector === '.tab-btn') return elements.filter(el => el.classes.has('tab-btn'));
        if (selector === '.view') return elements.filter(el => el.classes.has('view'));
        throw new Error(`Unmocked selector: ${selector}`);
      }
    },
    confirm: () => true,
    setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
    clearTimeout: id => timers.delete(id), setInterval: () => 0, clearInterval: () => {},
    console
  };
  vm.runInNewContext(source, context, { filename: 'dashboard-under-test.js' });
  return {
    element: id => nodes.get(id), window, calls, timers,
    state: () => structuredClone(persisted),
    callsTo: method => calls.filter(call => call.method === method),
    input: (id, value) => { nodes.get(id).value = value; nodes.get(id).dispatch('input'); },
    click: id => { assert.equal(typeof nodes.get(id)?.onclick, 'function', `button ${id} must work`); nodes.get(id).onclick(); },
    mode: mode => elements.find(el => el.dataset.mode === mode).onclick(),
    runDelay: delay => {
      for (const [id, timer] of [...timers]) if (timer.delay === delay) { timers.delete(id); timer.callback(); }
    },
    reply: (kind, data) => window.receiveNative(kind, { ok: true, ...data })
  };
}

let count = 0;
function test(name, action) { action(); count++; console.log(`PASS ${name}`); }
const query = 'port:80 (DNVRS-Webs OR Hikvision-Webs OR web OR App-webs OR DVRDVS-Webs)';
const result = (ip, port = 80, product = 'Camera') => ({ ip, port, product, country: 'Local', org: 'Authorized lab' });

test('specified defaults are preserved and startup makes no network request', () => {
  const ui = dashboard();
  assert.deepEqual(ui.state().settings, { query, pages: 1, limit: 100, repeatThreshold: 0.5, delayMs: 1000,
    concurrency: 20, lanStart: '192.168.1.1', lanEnd: '192.168.1.10', ports: '80,443,554,8080' });
  assert.equal(ui.calls.length, 0);
});

test('malformed saved state recovers without requiring structuredClone in WebView', () => {
  const ui = dashboard('{broken-json');
  assert.equal(ui.state().settings.query, query);
  assert.equal(ui.state().favorites.length, 0); assert.equal(ui.calls.length, 0);
});

test('input changes, result cards, favorites and history survive dashboard reload', () => {
  const history = [{ id: 123, type: 'LAN', description: 'Authorized lab', started: new Date().toISOString(),
    status: 'complete', items: [result('192.168.1.2')] }];
  const ui = dashboard({ results: [result('192.168.1.2')], favorites: ['192.168.1.2:80'], history });
  ui.input('shodan-query', 'port:443'); ui.input('shodan-delay', 2345); ui.input('lan-workers', 3);
  const reloaded = dashboard(ui.state());
  assert.equal(reloaded.element('shodan-query').value, 'port:443');
  assert.equal(reloaded.element('shodan-delay').value, '2345');
  assert.equal(reloaded.element('lan-workers').value, '3');
  assert.equal(reloaded.state().results.length, 1); assert.equal(reloaded.state().favorites.length, 1);
  assert.deepEqual(reloaded.state().history, history);
});

test('Shodan discovery requires a user key and reports account errors', () => {
  const ui = dashboard({}, false); ui.click('start-search');
  assert.equal(ui.callsTo('searchShodanTagged').length, 0);
  assert.equal(ui.state().activeView, 'settings');
  ui.window.receiveNative('api-info', { ok: false, error: 'Shodan API: 401 unauthorized' });
  assert.equal(ui.element('api-chip').textContent, 'API error');
  assert.match(ui.element('toast').textContent, /401 unauthorized/);
});

test('results deduplicate by IP and port while preserving distinct services', () => {
  const ui = dashboard(); ui.click('start-search');
  const token = ui.callsTo('searchShodanTagged')[0].args[2];
  ui.reply('search', { token, matches: [result('192.168.1.2'), result('192.168.1.2'), result('192.168.1.2', 443)] });
  assert.equal(ui.state().results.length, 2);
  assert.equal(ui.state().history[0].status, 'complete');
});

test('pagination uses configured delay and repeat threshold ends repeated pages', () => {
  const ui = dashboard(); ui.input('shodan-pages', 3); ui.input('shodan-delay', 1234); ui.click('start-search');
  const token = ui.callsTo('searchShodanTagged')[0].args[2];
  ui.reply('search', { token, matches: [result('192.168.1.2'), result('192.168.1.3')] });
  assert.equal(ui.callsTo('searchShodanTagged').length, 1);
  ui.input('shodan-query', 'port:9999'); // In-flight search continues its original query.
  ui.runDelay(1234);
  assert.deepEqual(ui.callsTo('searchShodanTagged')[1].args.slice(0, 2), [query, 2]);
  ui.reply('search', { token, matches: [result('192.168.1.2'), result('192.168.1.4')] });
  ui.runDelay(1234);
  assert.equal(ui.callsTo('searchShodanTagged').length, 2);
  assert.equal(ui.state().history[0].status, 'complete'); assert.equal(ui.state().results.length, 3);
});

test('stop cancels delayed pagination and later stale responses cannot modify results', () => {
  const ui = dashboard(); ui.input('shodan-pages', 3); ui.click('start-search');
  const oldToken = ui.callsTo('searchShodanTagged')[0].args[2];
  ui.reply('search', { token: oldToken, matches: [result('192.168.1.2')] });
  ui.click('stop-search'); ui.runDelay(1000);
  assert.equal(ui.callsTo('searchShodanTagged').length, 1);
  assert.equal(ui.callsTo('stopSearch').length, 1); assert.equal(ui.state().history[0].status, 'stopped');
  ui.click('start-search'); const newToken = ui.callsTo('searchShodanTagged')[1].args[2];
  assert.notEqual(newToken, oldToken);
  ui.reply('search', { token: oldToken, matches: [result('192.168.1.99')] });
  assert.equal(ui.state().results.length, 0);
  ui.reply('search', { token: newToken, matches: [] });
  assert.equal(ui.state().history[0].status, 'complete');
});

test('selected IP scanning requires explicit scope authorization and omits public IPs', () => {
  const ui = dashboard({ ipList: ['192.168.1.2', '10.0.0.3', '8.8.8.8'], selectedIPs: [] });
  ui.click('select-private-ips'); ui.click('scan-selected-ips');
  assert.equal(ui.callsTo('scanAuthorized').length, 0);
  ui.click('start-search'); assert.equal(ui.callsTo('scanAuthorized').length, 0);
  ui.element('scan-authorized').checked = true; ui.input('shodan-delay', 2000); ui.click('start-search');
  const config = JSON.parse(ui.callsTo('scanAuthorized')[0].args[0]);
  assert.deepEqual(config.ips, ['192.168.1.2', '10.0.0.3']);
  assert.equal(config.authorized, true); assert.equal(config.delayMs, 2000); assert.equal(config.workers, 20);
});

test('private-network workers are capped to 20 and can be reduced', () => {
  const ui = dashboard({ ipList: ['192.168.1.10'], selectedIPs: ['192.168.1.10'] });
  ui.click('scan-selected-ips');
  ui.element('scan-authorized').checked = true;
  ui.input('lan-workers', 100);
  ui.click('start-search');
  assert.equal(JSON.parse(ui.callsTo('scanAuthorized')[0].args[0]).workers, 20);
});

test('CSV export neutralizes spreadsheet formula prefixes and quotes fields', () => {
  const ui = dashboard({ results: [result('192.168.1.2', 80, '=SUM(1,2)')] });
  ui.click('export-results'); const csv = ui.callsTo('exportCsv')[0].args[0];
  assert.match(csv, /"'=SUM\(1,2\)"/); assert.match(csv, /^IP,Port,Product,Country,Organization,Source\r\n/);
});

test('clear workspace restores clean defaults after favorites and history mutate', () => {
  const ui = dashboard(); ui.click('start-search');
  const token = ui.callsTo('searchShodanTagged')[0].args[2];
  ui.reply('search', { token, matches: [result('192.168.1.2')] });
  ui.element('results-preview').children[0].querySelector('.mark').onclick();
  assert.equal(ui.state().favorites.length, 1); assert.equal(ui.state().history.length, 1);
  ui.input('shodan-query', 'port:443'); ui.click('erase-state');
  assert.equal(ui.state().favorites.length, 0); assert.equal(ui.state().history.length, 0);
  assert.equal(ui.state().results.length, 0); assert.equal(ui.state().settings.query, query);
  assert.equal(ui.callsTo('deleteApiKey').length, 0);
});

test('save displayed Shodan results retains ports and provenance without connecting to cameras', () => {
  const ui = dashboard(); ui.click('start-search');
  const token = ui.callsTo('searchShodanTagged')[0].args[2];
  ui.reply('search', { token, matches: [result('192.168.1.2', 80), result('192.168.1.2', 443)] });
  ui.click('save-visible-ips');
  assert.deepEqual(ui.state().ipList, ['192.168.1.2']);
  assert.deepEqual(ui.state().ipDetails['192.168.1.2'].sources, ['shodan']);
  assert.deepEqual(ui.state().ipDetails['192.168.1.2'].ports, [80, 443]);
  assert.equal(ui.callsTo('scanAuthorized').length, 0);
});

test('saving the same IP from private scanning preserves both origins across reload', () => {
  const ui = dashboard({ ipList: ['192.168.1.2'], ipDetails: {
    '192.168.1.2': { sources: ['shodan'], ports: [443] }
  }, results: [{ ...result('192.168.1.2', 80), source: 'private-scan' }] });
  ui.click('save-visible-ips');
  const reloaded = dashboard(ui.state());
  assert.deepEqual(reloaded.state().ipDetails['192.168.1.2'].sources, ['shodan', 'private-scan']);
  assert.deepEqual(reloaded.state().ipDetails['192.168.1.2'].ports, [443, 80]);
  assert.equal(reloaded.state().ipList.length, 1);
});

test('TXT and CSV imports canonicalize addresses, reject invalid octets, and retain source', () => {
  const ui = dashboard();
  ui.reply('ip-import', { text: 'IP,Name\n192.168.001.002,Office\n999.2.3.4,Invalid\n10.0.0.3,Gate\n192.168.1.2,Duplicate' });
  assert.deepEqual(ui.state().ipList, ['192.168.1.2', '10.0.0.3']);
  assert.deepEqual(ui.state().ipDetails['10.0.0.3'].sources, ['imported']);
  assert.equal(ui.callsTo('scanAuthorized').length, 0);
});

test('sorting and source filters apply to displayed results and exported CSV', () => {
  const ui = dashboard({ results: [
    { ...result('192.168.1.100'), source: 'shodan' },
    { ...result('192.168.1.2'), source: 'shodan' },
    { ...result('10.0.0.1'), source: 'private-scan' }
  ] });
  ui.element('results-source').value = 'shodan'; ui.element('results-source').dispatch('change');
  ui.element('results-sort').value = 'ip'; ui.element('results-sort').dispatch('change');
  ui.click('export-results'); const csv = ui.callsTo('exportCsv')[0].args[0];
  assert.ok(csv.indexOf('192.168.1.2') < csv.indexOf('192.168.1.100'));
  assert.ok(!csv.includes('10.0.0.1'));
  assert.equal(ui.element('all-results').children.length, 2);
  assert.equal(dashboard(ui.state()).element('results-sort').value, 'ip');
});

test('selected IP opens registration using its saved port without copy/paste or a network request', () => {
  const ui = dashboard({ ipList: ['192.168.1.2'], selectedIPs: ['192.168.1.2'],
    ipDetails: { '192.168.1.2': { sources: ['private-scan'], ports: [554, 443] } } });
  const registrations = [];
  ui.window.CameraManager = { registerFromIp: (...args) => registrations.push(args) };
  ui.click('register-selected-ip');
  assert.deepEqual(registrations, [['192.168.1.2', 443, 'private-scan']]);
  assert.equal(ui.callsTo('scanAuthorized').length, 0);
});

test('IP List pagination makes every stored address reviewable', () => {
  const ips = Array.from({ length: 310 }, (_, i) => `10.0.${Math.floor(i / 256)}.${i % 256}`);
  const ui = dashboard({ ipList: ips });
  assert.equal(ui.element('ip-book').children.length, 100);
  ui.click('book-next'); ui.click('book-next'); ui.click('book-next');
  assert.equal(ui.element('ip-book').children.length, 10);
  assert.match(ui.element('book-page').textContent, /Page 4 of 4/);
});

console.log(`${count} dashboard behavior tests passed.`);
