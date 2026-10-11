import assert from 'node:assert/strict';
import {cameraDashboard} from './camera-test-fixture.mjs';

const presets = () => Array.from({length: 6}, (_, i) => ({id: 'preset-' + (i + 1), name: 'Password preset ' + (i + 1), configured: i === 0, hasPassword: i === 0, unavailable: i === 2}));
const configured = () => ({cameras: [], snapshots: [], history: [], passwordPresets: presets(), sharedUsername: 'operator', usernameConfigured: true,
  credentials: [{id: 'preset-1', name: 'Password preset 1', hasPassword: true}, {id: 'legacy-camera-credential', name: 'Existing credential', hasPassword: true}]});
let count = 0;
function test(name, action) { action(); count++; console.log(`PASS ${name}`); }

test('settings display one username and exactly six status-only password fields with legacy credentials retained', () => {
  const ui = cameraDashboard(configured());
  assert.equal(ui.document.querySelectorAll('#cam-preset-section input[type="password"]').length, 6);
  assert.equal(ui.element('cam-preset-username').value, 'operator');
  for (let i = 1; i <= 6; i++) assert.equal(ui.element('cam-preset-' + i).value, '');
  assert.match(ui.element('cam-preset-status-1').textContent, /Configured/);
  assert.match(ui.element('cam-preset-status-2').textContent, /Not configured/);
  assert.match(ui.element('cam-preset-status-3').textContent, /Unavailable/);
  assert.equal(ui.element('cam-credential').options.filter(option => option.value === 'legacy-camera-credential').length, 1);
  assert.equal(ui.calls.length, 0);
});

test('saving sends one atomic six-preset command and blanks preserve the other saved passwords', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-username', 'replacement-operator');
  ui.input('cam-preset-2', 'fixture-only-second-secret');
  ui.click('cam-save-presets');
  const commands = ui.callsTo('save-presets');
  assert.equal(commands.length, 1);
  assert.deepEqual(commands[0].data, {username: 'replacement-operator', presets: Array.from({length: 6}, (_, i) => ({id: 'preset-' + (i + 1), password: i === 1 ? 'fixture-only-second-secret' : ''}))});
  assert.equal(ui.element('cam-save-presets').disabled, true);
  ui.click('cam-save-presets'); assert.equal(ui.callsTo('save-presets').length, 1);
  for (const value of ui.storedSession.values()) assert.equal(value.includes('fixture-only-second-secret'), false);
});

test('successful own save acknowledgement clears password inputs and allows another save', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-2', 'fixture-only-second-secret'); ui.click('cam-save-presets');
  ui.reply('camera-state', configured());
  assert.equal(ui.element('cam-save-presets').disabled, true);
  ui.reply('camera-notice', {ok: true, action: 'save-presets', message: 'Password presets saved securely.'});
  assert.equal(ui.element('cam-save-presets').disabled, false);
  for (let i = 1; i <= 6; i++) assert.equal(ui.element('cam-preset-' + i).value, '');
  ui.input('cam-preset-4', 'fixture-only-fourth-secret'); ui.click('cam-save-presets');
  assert.equal(ui.callsTo('save-presets').length, 2);
});

test('failed own save acknowledgement resets pending state and preserves edits for retry', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-username', 'replacement-operator'); ui.input('cam-preset-2', 'fixture-only-second-secret');
  ui.click('cam-save-presets');
  ui.reply('camera-notice', {ok: false, action: 'save-presets', error: 'Storage unavailable.'});
  assert.equal(ui.element('cam-save-presets').disabled, false);
  assert.equal(ui.element('cam-preset-2').value, 'fixture-only-second-secret');
  assert.equal(ui.element('cam-preset-username').value, 'replacement-operator');
  ui.click('cam-save-presets'); assert.equal(ui.callsTo('save-presets').length, 2);
});

test('an unrelated native notice cannot discard a pending password edit', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-2', 'fixture-only-second-secret'); ui.click('cam-save-presets');
  ui.reply('camera-notice', {ok: true, action: 'save-camera', message: 'Camera profile saved.'});
  assert.equal(ui.element('cam-save-presets').disabled, true);
  assert.equal(ui.element('cam-preset-2').value, 'fixture-only-second-secret');
  ui.reply('camera-notice', {ok: true, action: 'save-presets', message: 'Password presets saved securely.'});
  assert.equal(ui.element('cam-save-presets').disabled, false);
});

test('username-only edit sends six unchanged passwords and ordinary refresh preserves dirty edits', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-username', 'replacement-operator');
  ui.reply('camera-state', configured());
  assert.equal(ui.element('cam-preset-username').value, 'replacement-operator');
  ui.click('cam-save-presets');
  const saved = ui.callsTo('save-presets')[0].data;
  assert.equal(saved.username, 'replacement-operator');
  assert.equal(saved.presets.length, 6); assert.ok(saved.presets.every(preset => preset.password === ''));
});

test('single deletion sends only that preset and clear-all requests explicit shared setup deletion', () => {
  const ui = cameraDashboard(configured());
  ui.click('cam-delete-preset-1');
  assert.deepEqual(ui.callsTo('save-presets')[0].data, {presets: [{id: 'preset-1', delete: true}]});
  ui.click('cam-delete-all-presets');
  assert.deepEqual(ui.callsTo('save-presets')[1].data, {deleteUsername: true});
  assert.equal(ui.confirmations.length, 2);
  assert.ok(ui.confirmations[1].includes('legacy credentials will remain'));
});

test('missing username blocks a new preset and busy processing disables preset changes', () => {
  const ui = cameraDashboard();
  ui.input('cam-preset-1', 'fixture-only-first-secret'); ui.click('cam-save-presets');
  assert.equal(ui.callsTo('save-presets').length, 0);
  assert.match(ui.element('toast').textContent, /shared camera username/);
  ui.reply('camera-state', {...configured(), busy: true});
  assert.equal(ui.element('cam-save-presets').disabled, true);
  assert.equal(ui.element('cam-delete-preset-1').disabled, true);
  assert.equal(ui.element('cam-delete-all-presets').disabled, true);
});

test('bridge command failure retains edits and never leaves save pending', () => {
  const ui = cameraDashboard(configured());
  ui.input('cam-preset-2', 'fixture-only-second-secret'); ui.failCommands(); ui.click('cam-save-presets');
  assert.equal(ui.element('cam-save-presets').disabled, false);
  assert.equal(ui.element('cam-preset-2').value, 'fixture-only-second-secret');
  ui.failCommands(false); ui.click('cam-save-presets'); assert.equal(ui.callsTo('save-presets').length, 1);
});

console.log(`${count} camera settings behavior tests passed.`);
