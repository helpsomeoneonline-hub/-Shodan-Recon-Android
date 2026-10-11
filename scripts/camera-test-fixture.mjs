import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync('app/src/main/assets/cameras.js', 'utf8');

// A small DOM fixture keeps these browser behavior checks dependency-free. It
// exercises the shipped script, native callbacks and rendered controls together.
export function cameraDashboard(initialState = {}) {
  const nodes = new Map(), storedSession = new Map(), calls = [], confirmations = [];
  let nativeState = structuredClone({cameras: [], credentials: [], passwordPresets: [], snapshots: [], history: [], ...initialState});
  let commandFailure = false;
  function selectorMatches(node, selector) {
    if (!(node instanceof Element)) return false;
    const checked = selector.includes(':checked');
    selector = selector.replace(':checked', '');
    if (checked && !node.checked) return false;
    const tag = selector.match(/^[a-z][\w-]*/i)?.[0];
    if (tag && node.tagName !== tag.toLowerCase()) return false;
    for (const match of selector.matchAll(/#([\w-]+)/g)) if (node.id !== match[1]) return false;
    for (const match of selector.matchAll(/\.([\w-]+)/g)) if (!node.classes.has(match[1])) return false;
    for (const match of selector.matchAll(/\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]/g)) {
      if (!node.attributes.has(match[1])) return false;
      if (match[2] !== undefined && node.attributes.get(match[1]) !== match[2]) return false;
    }
    return true;
  }
  function descendants(root) { return root.children.flatMap(child => [child, ...descendants(child)]); }
  function select(root, selector) {
    const parts = selector.trim().split(/\s+/);
    return descendants(root).filter(node => {
      if (!selectorMatches(node, parts.at(-1))) return false;
      let ancestor = node.parentElement;
      for (let i = parts.length - 2; i >= 0; i--) {
        while (ancestor && !selectorMatches(ancestor, parts[i])) ancestor = ancestor.parentElement;
        if (!ancestor) return false;
        ancestor = ancestor.parentElement;
      }
      return true;
    });
  }
  class Element {
    constructor(tag = 'div') {
      this.tagName = tag.toLowerCase(); this.attributes = new Map(); this.classes = new Set();
      this.children = []; this.dataset = {}; this.style = {}; this.events = {}; this.value = '';
      this.checked = false; this.disabled = false; this.text = ''; this.markup = ''; this.parentElement = null;
      this.classList = {
        contains: name => this.classes.has(name), add: name => this.classes.add(name),
        remove: name => this.classes.delete(name),
        toggle: (name, force) => { const add = force ?? !this.classes.has(name); if (add) this.classes.add(name); else this.classes.delete(name); return add; }
      };
    }
    set id(value) { this.attributes.set('id', value); nodes.set(value, this); }
    get id() { return this.attributes.get('id') || ''; }
    set className(value) { this.classes = new Set(String(value).split(/\s+/).filter(Boolean)); }
    get className() { return [...this.classes].join(' '); }
    get firstElementChild() { return this.children[0] || null; }
    get options() { return this.children.filter(child => child.tagName === 'option'); }
    get textContent() { return this.text + this.children.map(child => child.textContent).join(''); }
    set textContent(value) { this.text = String(value); this.children = []; this.markup = ''; }
    get innerHTML() { return this.markup; }
    set innerHTML(markup) {
      this.markup = String(markup); this.children = []; this.text = '';
      const stack = [this];
      for (const match of this.markup.matchAll(/<\/?([\w-]+)\b([^>]*)>/g)) {
        if (match[0].startsWith('</')) { if (stack.length > 1) stack.pop(); continue; }
        const child = new Element(match[1]);
        for (const attr of match[2].matchAll(/([\w-]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+)))?/g))
          child.setAttribute(attr[1], attr[2] ?? attr[3] ?? attr[4] ?? '');
        stack.at(-1).appendChild(child);
        if (!['input', 'img', 'br', 'hr', 'meta', 'link'].includes(child.tagName) && !match[0].endsWith('/>')) stack.push(child);
      }
    }
    setAttribute(key, value) {
      value = String(value); this.attributes.set(key, value);
      if (key === 'id') this.id = value;
      if (key === 'class') this.className = value;
      if (key === 'value') this.value = value;
      if (key === 'checked') this.checked = true;
      if (key === 'disabled') this.disabled = true;
      if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = value;
    }
    getAttribute(key) { return this.attributes.get(key) ?? null; }
    removeAttribute(key) { this.attributes.delete(key); }
    appendChild(child) {
      if (child.parentElement) child.parentElement.children = child.parentElement.children.filter(value => value !== child);
      child.parentElement = this; this.children.push(child);
      if (this.tagName === 'select' && child.tagName === 'option' && this.options.length === 1) this.value = child.value;
      return child;
    }
    add(child) { this.appendChild(child); }
    replaceChildren(...children) { this.children = []; if (this.tagName === 'select') this.value = ''; children.forEach(child => this.appendChild(child)); }
    querySelectorAll(selector) { return select(this, selector); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    addEventListener(kind, callback) { (this.events[kind] ||= []).push(callback); }
    dispatch(kind) { const event = {target: this, preventDefault() {}}; this['on' + kind]?.(event); for (const callback of this.events[kind] || []) callback(event); }
    click() { if (!this.disabled) this.dispatch('click'); }
    reset() { for (const input of descendants(this)) if (input.tagName === 'input') { input.value = input.attributes.get('value') || ''; input.checked = false; } }
    scrollIntoView() {}
    focus() {}
  }
  const root = new Element('body');
  root.innerHTML = '<div class="app"><div id="view-gallery"></div><div id="view-settings"><div class="layout"></div></div><div id="toast"></div><button id="diag-export"></button><nav class="nav"><button data-view="cameras"></button><button data-view="gallery"></button><button data-view="settings"></button></nav></div>';
  const document = {
    readyState: 'complete', hidden: false,
    getElementById: id => nodes.get(id) || null,
    createElement: tag => new Element(tag),
    querySelector: selector => root.querySelector(selector),
    querySelectorAll: selector => root.querySelectorAll(selector),
    addEventListener() {}
  };
  function Option(text, value) { const option = new Element('option'); option.textContent = text; option.value = String(value); return option; }
  const window = {ReconCameras: {
    state: () => JSON.stringify(nativeState),
    command(action, json) { if (commandFailure) throw new Error('Synthetic unavailable bridge'); calls.push({action, data: JSON.parse(json)}); }
  }};
  const context = {document, window, Option, console,
    sessionStorage: {getItem: key => storedSession.get(key) ?? null, setItem: (key, value) => storedSession.set(key, value)},
    confirm: text => { confirmations.push(text); return true; },
    setTimeout: () => 1, clearTimeout() {}
  };
  vm.runInNewContext(source, context, {filename: 'camera-dashboard-under-test.js'});
  return {
    window, document, calls, confirmations, storedSession,
    element(id) { const element = document.getElementById(id); assert.ok(element, `missing control: ${id}`); return element; },
    input(id, value) { this.element(id).value = String(value); this.element(id).dispatch('input'); },
    change(id, value) { this.element(id).value = String(value); this.element(id).dispatch('change'); },
    click(id) { assert.equal(typeof this.element(id).onclick, 'function', `button ${id} must work`); this.element(id).click(); },
    callsTo: action => calls.filter(call => call.action === action),
    setState(state) { nativeState = structuredClone(state); },
    reply(kind, data) { if (kind === 'camera-state') nativeState = structuredClone(data.state || data); window.receiveCameraNative(kind, data); },
    failCommands(value = true) { commandFailure = value; }
  };
}
