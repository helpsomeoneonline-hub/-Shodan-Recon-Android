(() => {
  'use strict';
  const DEFAULT_CHANNELS = Array.from({length: 16}, (_, n) => (n + 1) * 100 + 1);
  const $ = id => document.getElementById(id);
  const escapeHtml = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const storage = {read(key) { try { return sessionStorage.getItem(key); } catch (_) { return null; } }, write(key, value) { try { sessionStorage.setItem(key, value); } catch (_) {} }};
  let state = {cameras: [], credentials: [], passwordPresets: [], snapshots: [], history: [], storageBytes: 0, busy: false, batch: {}};
  let selectedId = storage.read('camera-selected') || '', activeTab = storage.read('camera-tab') || 'devices';
  let initialized = false, diagnostics = [], toastTimer, galleryPage = 0, thumbnailObserver, usernameDirty = false, presetSavePending = false, pendingCameraForm = null;
  let batchSelection = new Set();
  let batchCredentialMode = storage.read('camera-batch-credential-mode') === 'assigned' ? 'assigned' : 'saved-passwords';
  try { const saved = JSON.parse(storage.read('camera-batch-selection') || '[]'); if (Array.isArray(saved)) batchSelection = new Set(saved.map(String)); } catch (_) {}
  const thumbnailCache = new Map(), thumbnailRequested = new Set(), GALLERY_PAGE_SIZE = 48;
  const bridge = () => window.ReconCameras;
  function notify(message) {
    const el = $('toast');
    if (!el) return;
    el.textContent = message; el.style.display = 'block';
    clearTimeout(toastTimer); toastTimer = setTimeout(() => el.style.display = 'none', 4500);
  }
  function command(action, data = {}) {
    if (!bridge()) { notify('Camera services are unavailable. Open this page in the Android app.'); return false; }
    try { bridge().command(action, JSON.stringify(data)); return true; }
    catch (error) { notify('Could not start camera action: ' + error.message); return false; }
  }
  function addDiagnostic(message) {
    if (!message) return;
    diagnostics.unshift(new Date().toLocaleTimeString() + ' · ' + String(message));
    diagnostics = diagnostics.slice(0, 30);
    if ($('cam-diagnostics')) $('cam-diagnostics').textContent = diagnostics.join('\n');
  }
  function applyState(data) {
    if (!data || !Array.isArray(data.cameras)) return;
    const previousIds = new Set(state.cameras.map(camera => String(camera.id)));
    state = Object.assign({}, state, data);
    for (const field of ['cameras', 'credentials', 'passwordPresets', 'snapshots', 'history']) if (!Array.isArray(state[field])) state[field] = [];
    batchSelection = new Set([...batchSelection].filter(id => state.cameras.some(camera => String(camera.id) === id)));
    const savedCamera = pendingCameraForm && state.cameras.find(camera => pendingCameraForm.id ? String(camera.id) === pendingCameraForm.id : !previousIds.has(String(camera.id)) && camera.host === pendingCameraForm.host && Number(camera.port) === pendingCameraForm.port);
    if (savedCamera) { selectedId = String(savedCamera.id); pendingCameraForm = null; }
    if (!state.cameras.some(camera => String(camera.id) === selectedId)) selectedId = state.cameras.length ? String(state.cameras[0].id) : '';
    storage.write('camera-selected', selectedId);
    renderCameras(); renderCredentials(); renderSnapshots(); renderHistory(); renderStorage(); renderBatch(); renderBusy();
    if (savedCamera) loadCameraForm(savedCamera);
  }
  function refresh() {
    if (!bridge()) return;
    try { applyState(JSON.parse(bridge().state() || '{}')); }
    catch (error) { addDiagnostic('Unable to read saved cameras: ' + error.message); }
  }
  function field(label, id, value = '', extra = '') {
    return '<div class="field"><label for="' + id + '">' + label + '</label><input class="input" id="' + id + '" value="' + escapeHtml(value) + '" ' + extra + '></div>';
  }
  function init() {
    if (initialized || !document.querySelector('.app')) return;
    initialized = true;
    const view = document.createElement('div'); view.id = 'view-cameras'; view.className = 'view';
    view.innerHTML = `
      <div class="cam-tabs" role="tablist" aria-label="Camera workspace">
        <button class="btn ghost" role="tab" data-camera-tab="devices">Cameras</button>
        <button class="btn ghost" role="tab" data-camera-tab="gallery">Snapshots</button>
        <button class="btn ghost" role="tab" data-camera-tab="credentials">Credentials</button>
        <button class="btn ghost" role="tab" data-camera-tab="activity">Activity</button>
      </div>
      <div class="cam-status" id="cam-status" role="status" aria-live="polite" aria-busy="false"><span class="cam-status-text" id="cam-status-text">Ready. Connections start only when you request them.</span><button class="btn warn hidden" id="cam-cancel">Stop</button><progress class="hidden" id="cam-progress"></progress></div>
      <div class="cam-pane" id="cam-pane-devices"><div class="cam-layout">
        <section class="section"><h2>Registered cameras <span class="counter" id="cam-count">0</span></h2>
          <div class="field"><label for="cam-filter">Find a camera</label><input class="input" id="cam-filter" type="search" placeholder="Name, address or port"></div>
          <div class="actions"><button class="btn primary" id="cam-new">Add camera</button><button class="btn ghost" id="cam-refresh">Refresh</button></div>
          <div id="cam-list"></div>
          <div class="line"></div><h2 id="cam-form-heading">Add authorized camera</h2>
          <form id="cam-form" autocomplete="off"><input type="hidden" id="cam-id">
            ${field('Camera name', 'cam-name', '', 'required maxlength="100" placeholder="Front entrance"')}
            ${field('IP address or hostname', 'cam-host', '', 'required autocapitalize="none" spellcheck="false" placeholder="192.168.1.20"')}
            <div class="two"><div class="field"><label for="cam-scheme">Web protocol</label><select id="cam-scheme"><option value="http">HTTP</option><option value="https">HTTPS</option></select></div>${field('Web port', 'cam-port', '80', 'required type="number" min="1" max="65535"')}</div>
            <div class="field"><label for="cam-credential">Saved credential</label><select id="cam-credential"><option value="">No credential</option></select></div>
            <p class="desc">Choose a saved credential for manual viewing and assigned-credential checks. Automatic saved-password testing uses the shared username and configured presets in Settings.</p>
            ${field('Camera web interface path', 'cam-http-path', '/', 'placeholder="/" autocapitalize="none" spellcheck="false"')}
            ${field('Snapshot URL template', 'cam-snapshot-path', '/ISAPI/Streaming/channels/{channel}/picture', 'required autocapitalize="none" spellcheck="false"')}
            <p class="desc">Use {scheme}, {host}, {port} and {channel} to insert this camera's protocol, address, port and channel, for example {scheme}://{host}:{port}/ISAPI/Streaming/channels/{channel}/picture. A full URL must address this same camera. Keep passwords in Settings.</p>
            <details class="cam-advanced"><summary>Live stream paths</summary>
              ${field('MJPEG path (if available)', 'cam-mjpeg-path', '', 'placeholder="/video.mjpg" autocapitalize="none" spellcheck="false"')}
              ${field('RTSP path (if available)', 'cam-rtsp-path', '', 'placeholder="/Streaming/Channels/101" autocapitalize="none" spellcheck="false"')}
              ${field('RTSP port', 'cam-rtsp-port', '554', 'required type="number" min="1" max="65535"')}
              ${field('HLS path (if available)', 'cam-hls-path', '', 'placeholder="/live/index.m3u8" autocapitalize="none" spellcheck="false"')}
            </details>
            <label class="cam-check"><input type="checkbox" id="cam-authorized" required>I own this camera or have permission to administer it.</label>
            <div class="actions"><button class="btn primary fill" type="submit">Save camera</button><button class="btn ghost" type="button" id="cam-clear-form">Clear form</button></div>
          </form>
          <div class="line"></div><h2>Configuration transfer</h2><p>Import or export camera addresses, paths and channel selections. Credentials and passwords are excluded. Imported cameras must be authorized before connecting.</p>
          <div class="actions"><button class="btn ghost" id="cam-import-config">Import JSON</button><button class="btn ghost" id="cam-export-config">Export JSON</button></div>
        </section>
        <div class="cam-tools"><section class="section cam-batch-section"><h2>Automatic Camera Manager</h2>
          <p>Select the registered cameras you own or administer. The manager checks each camera, saves its configured snapshot channels after a successful connection, then moves to the next camera.</p>
          <div class="field"><label for="cam-batch-credential-mode">Credentials to use</label><select id="cam-batch-credential-mode"><option value="saved-passwords">Test saved passwords automatically</option><option value="assigned">Use each camera's assigned credential</option></select></div>
          <p class="desc" id="cam-batch-mode-help"></p><p class="cam-credential-note" id="cam-batch-setup-status"></p><div class="actions"><button class="btn ghost" id="cam-batch-credential-settings">Manage username and passwords</button></div>
          <div class="actions"><button class="btn ghost" id="cam-batch-select-all">Select all cameras</button><button class="btn ghost" id="cam-batch-clear">Clear selection</button><span class="counter" id="cam-batch-selection-count">0 selected</span></div>
          <div class="actions cam-batch-controls"><button class="btn primary fill" id="cam-batch-start">Start</button><button class="btn warn" id="cam-batch-stop" disabled>Stop</button></div>
          <div class="cam-batch-live" role="status" aria-live="polite" aria-atomic="true"><p id="cam-batch-current">No batch started.</p><p class="cam-batch-credential" id="cam-batch-credential"></p><p class="desc" id="cam-batch-stage"></p><progress id="cam-batch-progress" max="1" value="0" aria-label="Camera batch progress"></progress><div class="cam-batch-metrics"><div><strong id="cam-batch-completed">0 / 0</strong><span>Completed</span></div><div><strong id="cam-batch-successes">0</strong><span>Successful connections</span></div><div><strong id="cam-batch-failures">0</strong><span>Failures</span></div><div><strong id="cam-batch-snapshots">0</strong><span>Snapshots saved</span></div></div><p id="cam-batch-summary"></p></div>
          <div class="actions"><button class="btn ghost" id="cam-batch-export" disabled>Export checks CSV</button><button class="btn ghost" id="cam-batch-diagnostics">Diagnostic TXT</button></div>
          <details class="cam-advanced"><summary>Completed camera checks</summary><div id="cam-batch-results" class="cam-history"></div></details>
        </section><section class="section"><h2>Connect and capture</h2>
          <div class="field"><label for="cam-selected">Registered camera</label><select id="cam-selected"><option value="">Save a camera first</option></select></div>
          <div class="field"><label for="cam-method">Viewing method</label><select id="cam-method"><option value="http">HTTP / HTTPS interface</option><option value="snapshot">JPEG snapshot</option><option value="mjpeg">MJPEG live stream</option><option value="rtsp">RTSP live stream</option><option value="hls">HLS live stream</option></select></div>
          <p id="cam-method-help" class="desc"></p>
          <div class="actions"><button class="btn primary fill cam-network-action" id="cam-open">Open viewer</button><button class="btn ghost cam-network-action" id="cam-test">Test connection</button></div>
          <div class="line"></div><h2>Snapshot channels <span class="counter" id="cam-selected-count">1 selected</span></h2>
          <p>Capture uses the saved camera and its chosen credential. Select one channel for a single capture, or several for a batch.</p>
          <div class="actions"><button class="btn ghost" id="cam-select-all">Select all</button><button class="btn ghost" id="cam-select-none">Clear selection</button></div>
          <div class="cam-channels" id="cam-channels"></div>
          <div class="two"><div class="field"><label for="cam-custom-channel">Additional channel</label><input class="input" id="cam-custom-channel" type="number" min="1" max="65535" placeholder="Channel number"></div><button class="btn ghost cam-channel-add" id="cam-add-channel">Add channel</button></div>
          <div class="actions"><button class="btn ghost" id="cam-save-channels">Save channels for automatic capture</button></div>
          <div class="actions"><button class="btn primary fill cam-network-action" id="cam-capture">Capture selected</button><button class="btn ghost" id="cam-go-gallery">Gallery</button></div>
          <div class="line"></div><h2>Connection diagnostics</h2><div class="cam-diagnostics" id="cam-diagnostics" role="log">Save a camera, then test its connection. Network and authentication errors appear here.</div>
          <p>Basic and supported Digest authentication use your manually selected credential. HTTPS certificate errors are reported; camera access restrictions are respected.</p>
        </section></div>
      </div></div>
      <div class="cam-pane" id="cam-pane-gallery"><section class="section"><h2>Snapshot gallery <span class="counter" id="cam-snapshot-count">0</span></h2>
        <div class="cam-gallery-toolbar"><div class="field"><label for="cam-snapshot-filter">Search snapshots</label><input class="input" id="cam-snapshot-filter" type="search" placeholder="Camera, IP, channel or capture date"></div><label class="cam-check"><input type="checkbox" id="cam-only-flagged">Show images flagged for review</label></div>
        <div class="two"><div class="field"><label for="cam-gallery-camera">Camera address</label><select id="cam-gallery-camera"><option value="">All cameras</option></select></div><div class="field"><label for="cam-gallery-date">Capture date</label><input class="input" id="cam-gallery-date" type="date"></div></div>
        <div class="actions"><button class="btn ghost" id="cam-gallery-clear-filters">Clear gallery filters</button></div>
        <div class="actions"><button class="btn warn" id="cam-delete-flagged">Delete flagged images</button><button class="btn ghost" id="cam-gallery-refresh">Refresh gallery</button></div>
        <p class="cam-storage" id="cam-storage"></p><div class="actions"><button class="btn ghost" id="cam-select-storage">Choose export folder</button></div>
        <p>Downloaded images stay in this app's gallery. Choose an Android folder for exports, or export an individual image to a location you select. Exact duplicate, empty and corrupted downloads are flagged for review.</p>
        <div class="cam-gallery" id="cam-gallery"></div><div class="actions" style="margin-top:14px"><button class="btn ghost" id="cam-gallery-previous">Previous</button><span class="counter" id="cam-gallery-page"></span><button class="btn ghost" id="cam-gallery-next">Next</button></div>
      </section></div>
      <div class="cam-pane" id="cam-pane-credentials"><section class="section"><h2>Camera credentials</h2><p>Configure one shared username and up to six password presets in Settings. The Automatic Camera Manager can try the configured passwords in preset order, stopping at the first successful authentication. Assign a credential to each camera for manual viewing or assigned-credential checks. Existing credentials remain available to preserve your camera connections.</p><div class="actions"><button class="btn primary" id="cam-open-credential-settings">Manage six presets</button></div><div class="line"></div><h2>Saved credentials</h2><p class="cam-credential-note">Only configuration status is displayed. Secrets are encrypted with Android Keystore.</p><div id="cam-credential-list"></div></section></div>
      <div class="cam-pane" id="cam-pane-activity"><section class="section"><h2>Device history</h2><p>Connection tests, captures and device actions are stored locally. Passwords are never included.</p><div class="field"><label for="cam-history-filter">Filter device history</label><input class="input" id="cam-history-filter" type="search" placeholder="Camera, action or date"></div><div class="cam-history" id="cam-history"></div></section></div>`;
    document.querySelector('.app').appendChild(view);
    const galleryView = $('view-gallery');
    if (galleryView) { galleryView.appendChild($('cam-pane-gallery').firstElementChild); galleryView.classList.add('cam-workspace'); }
    const settings = document.createElement('section'); settings.id = 'cam-preset-section'; settings.className = 'section';
    settings.innerHTML = `<h2>Camera username and six password presets</h2><p>Enter the shared username once and save up to six passwords. Android Keystore encrypts saved credentials. Automatic saved-password testing tries configured presets from 1 to 6 for each selected authorized camera, stopping when authentication succeeds or all configured passwords fail. Assigned-credential checks and manual viewing use the credential chosen in the camera profile.</p><div class="field"><label for="cam-preset-username">Shared username</label><input class="input" id="cam-preset-username" maxlength="256" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="Camera username"><small id="cam-username-status">Not configured</small></div><div class="two cam-preset-grid">${Array.from({length:6},(_,i)=>'<div class="field"><label for="cam-preset-'+(i+1)+'">Password preset '+(i+1)+'</label><input class="input" id="cam-preset-'+(i+1)+'" type="password" maxlength="1024" autocomplete="new-password" placeholder="Blank keeps saved password"><div class="cam-preset-meta"><small id="cam-preset-status-'+(i+1)+'">Not configured</small><button class="btn warn" type="button" id="cam-delete-preset-'+(i+1)+'" disabled>Delete preset '+(i+1)+'</button></div></div>').join('')}</div><div class="actions"><button class="btn primary" id="cam-save-presets">Save username and presets</button><button class="btn warn" id="cam-delete-all-presets">Delete saved username and presets</button></div><p class="desc">Blank fields keep existing saved values. Delete removes a saved preset without reinstalling. Saved passwords are never displayed. Existing camera assignments remain available until you change or delete them.</p>`;
    const settingsLayout = document.querySelector('#view-settings .layout');
    if (settingsLayout) settingsLayout.appendChild(settings); else view.appendChild(settings);
    bindActions(view); resetCameraForm(true); refresh(); restoreDraft(); showTab(activeTab === 'gallery' && galleryView ? 'devices' : activeTab);
    if (document.querySelector('.nav [data-view="cameras"].active')) view.classList.add('active');
    if (!bridge()) { $('cam-status-text').textContent = 'Camera services require the Android app.'; }
  }
  function showTab(tab) {
    if (!['devices', 'gallery', 'credentials', 'activity'].includes(tab)) tab = 'devices';
    if (tab === 'gallery' && $('view-gallery')) {
      const nav = document.querySelector('.nav [data-view="gallery"]');
      if (nav) { nav.click(); renderSnapshots(); return; }
    }
    activeTab = tab; storage.write('camera-tab', tab);
    document.querySelectorAll('[data-camera-tab]').forEach(el => { const active = el.dataset.cameraTab === tab; el.classList.toggle('active', active); el.setAttribute('aria-selected', String(active)); });
    document.querySelectorAll('.cam-pane').forEach(el => el.classList.toggle('active', el.id === 'cam-pane-' + tab));
  }
  function cameraDisplay(camera) { return (camera.name || camera.host || 'Camera') + ' · ' + (camera.host || '') + ':' + (camera.port || (camera.scheme === 'https' ? 443 : 80)); }
  function selectedCamera() { return state.cameras.find(camera => String(camera.id) === selectedId); }
  function renderCameras() {
    const filter = $('cam-filter').value.toLowerCase(), list = $('cam-list'); list.replaceChildren();
    $('cam-count').textContent = state.cameras.length;
    const matching = state.cameras.filter(camera => cameraDisplay(camera).toLowerCase().includes(filter));
    for (const camera of matching) {
      const el = document.createElement('article'); el.className = 'cam-card' + (String(camera.id) === selectedId ? ' selected' : '');
      const preset = state.credentials.find(credential => String(credential.id) === String(camera.credentialId));
      el.innerHTML = '<label class="cam-check cam-batch-check"><input class="batch-select" type="checkbox"' + (batchSelection.has(String(camera.id)) ? ' checked' : '') + (!camera.authorized || state.busy ? ' disabled' : '') + '>Include in automatic checks</label><h3>' + escapeHtml(camera.name || 'Camera') + '</h3><small>' + escapeHtml((camera.scheme || 'http') + '://' + (camera.host || '') + ':' + camera.port) + '</small><small>' + (camera.authorized ? 'Registered as authorized' : 'Authorization required before connecting') + '</small><small>Credential: ' + escapeHtml(preset ? preset.name : camera.credentialId ? 'Unavailable saved credential' : 'No credential') + '</small><small>' + (Array.isArray(camera.channels) ? camera.channels.length : DEFAULT_CHANNELS.length) + ' configured snapshot channel(s) for automatic capture</small><div class="actions"><button class="btn primary use">Select</button><button class="btn ghost edit">Edit</button><button class="btn warn delete"' + (state.busy ? ' disabled' : '') + '>Delete</button></div>';
      el.querySelector('.batch-select').onchange = event => { const id = String(camera.id); if (event.target.checked) batchSelection.add(id); else batchSelection.delete(id); rememberBatchSelection(); renderBatch(); };
      el.querySelector('.batch-select').dataset.cameraId = String(camera.id);
      el.querySelector('.use').onclick = () => selectCamera(String(camera.id));
      el.querySelector('.edit').onclick = () => { loadCameraForm(camera); $('cam-form-heading').scrollIntoView({block: 'start', behavior: 'smooth'}); };
      el.querySelector('.delete').onclick = () => { if (confirm('Delete "' + (camera.name || camera.host) + '"? Saved snapshots will remain in the gallery.')) command('delete-camera', {id: camera.id}); };
      list.appendChild(el);
    }
    if (!matching.length) list.innerHTML = '<div class="empty">' + (state.cameras.length ? 'No matching cameras' : 'No cameras registered. Add an authorized camera below.') + '</div>';
    const select = $('cam-selected'); select.replaceChildren();
    if (!state.cameras.length) select.add(new Option('Save a camera first', ''));
    for (const camera of state.cameras) select.add(new Option(cameraDisplay(camera), String(camera.id)));
    select.value = selectedId;
    renderChannels(); updateMethod(); renderBusy();
  }
  function selectCamera(id) {
    selectedId = id; storage.write('camera-selected', id); renderCameras();
    const camera = selectedCamera();
    if (camera) addDiagnostic('Selected ' + cameraDisplay(camera) + '. No connection started.');
  }
  function selectedChannels() { return Array.from(document.querySelectorAll('#cam-channels input:checked'), el => Number(el.value)); }
  function renderChannels() {
    const camera = selectedCamera();
    let selected = DEFAULT_CHANNELS.slice(0, 1);
    try { const remembered = JSON.parse(storage.read('camera-channels-' + selectedId) || 'null'); if (Array.isArray(remembered)) selected = remembered; else if (camera && Array.isArray(camera.channels)) selected = camera.channels; } catch (_) {}
    const channels = [...new Set([...DEFAULT_CHANNELS, ...(camera && Array.isArray(camera.channels) ? camera.channels : []), ...selected])];
    $('cam-channels').innerHTML = channels.map(channel => '<label class="cam-check cam-channel"><input type="checkbox" value="' + Number(channel) + '"' + (selected.includes(Number(channel)) ? ' checked' : '') + '>' + Number(channel) + '</label>').join('');
    updateChannelCount();
  }
  function updateChannelCount() {
    const channels = selectedChannels(); $('cam-selected-count').textContent = channels.length + ' selected';
    storage.write('camera-channels-' + selectedId, JSON.stringify(channels));
  }
  function updateMethod() {
    const method = $('cam-method').value, camera = selectedCamera();
    storage.write('camera-view-method', method);
    const descriptions = {http:'Opens the saved camera interface in an Android viewer.', snapshot:'Displays a JPEG snapshot from the saved template. Capture below saves images to your gallery.', mjpeg:'Displays a compatible multipart MJPEG stream. Enter the MJPEG path in the camera profile.', rtsp:'Opens the native RTSP player. Enter an RTSP path in the profile. Use the existing external player if the camera codec is unsupported.', hls:'Opens a compatible HLS stream in the native player. Enter its playlist path in the profile.'};
    $('cam-method-help').textContent = descriptions[method];
    $('cam-test').classList.toggle('hidden', method === 'rtsp');
    const configured = !!camera && !!camera.authorized && (method === 'http' || method === 'snapshot' || !!camera[method + 'Path']);
    $('cam-open').disabled = !configured || !!state.busy;
    $('cam-test').disabled = !configured || !!state.busy || method === 'rtsp';
  }
  function resetCameraForm(preserveDraft = false) {
    $('cam-form').reset(); $('cam-id').value = ''; $('cam-form-heading').textContent = 'Add authorized camera';
    $('cam-name').value = ''; $('cam-host').value = ''; $('cam-port').value = '80'; $('cam-http-path').value = '/';
    $('cam-snapshot-path').value = '/ISAPI/Streaming/channels/{channel}/picture'; $('cam-rtsp-port').value = '554'; $('cam-authorized').checked = false;
    if (!preserveDraft) storage.write('camera-draft', '');
  }
  const profileFields = {id:'cam-id',name:'cam-name',host:'cam-host',port:'cam-port',scheme:'cam-scheme',httpPath:'cam-http-path',snapshotPath:'cam-snapshot-path',mjpegPath:'cam-mjpeg-path',rtspPath:'cam-rtsp-path',rtspPort:'cam-rtsp-port',hlsPath:'cam-hls-path',credentialId:'cam-credential'};
  function cameraFormData() {
    const data = {};
    for (const [name, id] of Object.entries(profileFields)) data[name] = $(id).value.trim();
    data.port = Number(data.port); data.rtspPort = Number(data.rtspPort); data.authorized = $('cam-authorized').checked;
    const existing = state.cameras.find(camera => String(camera.id) === data.id);
    data.channels = existing && Array.isArray(existing.channels) ? existing.channels : DEFAULT_CHANNELS;
    return data;
  }
  function loadCameraForm(camera) {
    for (const [name, id] of Object.entries(profileFields)) $(id).value = camera[name] == null ? '' : String(camera[name]);
    $('cam-authorized').checked = !!camera.authorized; $('cam-form-heading').textContent = 'Edit camera';
    persistDraft();
  }
  function persistDraft() { storage.write('camera-draft', JSON.stringify(cameraFormData())); }
  function restoreDraft() {
    try { const draft = JSON.parse(storage.read('camera-draft') || 'null'); if (draft && typeof draft === 'object') loadCameraForm(draft); } catch (_) {}
    const method = storage.read('camera-view-method'); if (method && ['http','snapshot','mjpeg','rtsp','hls'].includes(method)) $('cam-method').value = method;
    updateMethod();
  }
  function renderCredentials() {
    const chosen = $('cam-credential').value, list = $('cam-credential-list');
    $('cam-credential').replaceChildren(new Option('No credential', '')); list.replaceChildren();
    for (const credential of state.credentials) {
      $('cam-credential').add(new Option((credential.name || 'Credential') + (credential.unavailable ? ' (unavailable)' : ''), String(credential.id)));
      const el = document.createElement('div'); el.className = 'cam-card';
      const legacy = !/^preset-[1-6]$/.test(String(credential.id));
      el.innerHTML = '<h3>' + escapeHtml(credential.name || 'Credential') + '</h3><small>' + (credential.unavailable ? 'Saved credential unavailable; enter a replacement in Settings' : credential.hasPassword ? 'Password stored securely' : 'No saved password') + '</small><small>' + (legacy ? 'Existing credential · retained for current camera assignments. Enter its replacement in a password preset in Settings.' : 'Reusable password preset · edit in Settings') + '</small><div class="actions"><button class="btn ghost settings">Settings</button><button class="btn warn delete">Delete</button></div>';
      el.querySelector('.settings').onclick = openCredentialSettings;
      el.querySelector('.delete').onclick = () => { const associated = state.cameras.filter(camera => String(camera.credentialId) === String(credential.id)).length; if (confirm('Delete this saved credential?' + (associated ? ' It is selected by ' + associated + ' camera profile(s); those cameras will need another credential.' : ''))) command('delete-credential', {id: credential.id}); };
      list.appendChild(el);
    }
    for (let i=1;i<=6;i++) {
      const preset=state.passwordPresets.find(c=>String(c.id)==='preset-'+i) || state.credentials.find(c=>String(c.id)==='preset-'+i);
      const status=$('cam-preset-status-'+i);
      if(status) status.textContent=preset && preset.unavailable ? 'Unavailable · enter a replacement' : preset && (preset.configured || preset.hasPassword) ? 'Configured · saved securely' : 'Not configured';
      $('cam-delete-preset-'+i).disabled = !(preset && (preset.configured || preset.hasPassword || preset.unavailable)) || presetSavePending || !!state.busy;
    }
    if (!usernameDirty && !presetSavePending) $('cam-preset-username').value = state.sharedUsername || '';
    $('cam-username-status').textContent = state.usernameUnavailable ? 'Saved username unavailable · enter a replacement' : state.usernameConfigured ? 'Shared username configured' : 'Shared username not configured';
    $('cam-save-presets').disabled = presetSavePending || !!state.busy;
    $('cam-delete-all-presets').disabled = presetSavePending || !!state.busy || !(state.usernameConfigured || state.usernameUnavailable || state.passwordPresets.some(preset => preset.configured || preset.unavailable));
    if (!state.credentials.length) list.innerHTML = '<div class="empty">No device credentials saved</div>';
    if (state.credentials.some(credential => String(credential.id) === chosen)) $('cam-credential').value = chosen;
  }
  function openCredentialSettings() { const nav = document.querySelector('.nav [data-view="settings"]'); if (nav) nav.click(); $('cam-preset-section').scrollIntoView({block: 'start', behavior: 'smooth'}); }
  function bytes(value) { const size = Number(value) || 0; return size < 1024 ? size + ' B' : size < 1024 * 1024 ? (size / 1024).toFixed(1) + ' KB' : (size / 1024 / 1024).toFixed(1) + ' MB'; }
  function captureTime(snapshot) { const value = snapshot.capturedAt || snapshot.timestamp; const date = new Date(value); return Number.isNaN(date.valueOf()) ? String(value || 'Unknown time') : date.toLocaleString(); }
  function captureDate(snapshot) {
    const date = new Date(snapshot.capturedAt || snapshot.timestamp);
    if (Number.isNaN(date.valueOf())) return '';
    return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0');
  }
  function renderSnapshots() {
    const query = $('cam-snapshot-filter').value.toLowerCase(), flagged = $('cam-only-flagged').checked;
    const cameraFilter = $('cam-gallery-camera'), selectedHost = cameraFilter.value, date = $('cam-gallery-date').value;
    const hosts = [...new Set(state.snapshots.map(snapshot => String(snapshot.ip || snapshot.host || '')).filter(Boolean))].sort((a, b) => a.localeCompare(b, undefined, {numeric:true}));
    cameraFilter.replaceChildren(new Option('All cameras', ''));
    for (const host of hosts) cameraFilter.add(new Option(host, host));
    if (hosts.includes(selectedHost)) cameraFilter.value = selectedHost;
    const matching = state.snapshots.filter(snapshot => (!flagged || snapshot.flagged) && (!cameraFilter.value || String(snapshot.ip || snapshot.host || '') === cameraFilter.value) && (!date || captureDate(snapshot) === date) && (!query || [snapshot.cameraName,snapshot.ip,snapshot.channel,captureTime(snapshot),snapshot.capturedAt,snapshot.reason].join(' ').toLowerCase().includes(query)));
    $('cam-snapshot-count').textContent = matching.length + ' / ' + state.snapshots.length;
    $('cam-delete-flagged').disabled = !!state.busy || !state.snapshots.some(snapshot => snapshot.flagged);
    const list = $('cam-gallery'); list.replaceChildren();
    if (thumbnailObserver) thumbnailObserver.disconnect();
    const pages = Math.max(1, Math.ceil(matching.length / GALLERY_PAGE_SIZE)); galleryPage = Math.min(galleryPage, pages - 1);
    $('cam-gallery-page').textContent = 'Page ' + (galleryPage + 1) + ' of ' + pages;
    $('cam-gallery-previous').disabled = galleryPage === 0; $('cam-gallery-next').disabled = galleryPage + 1 >= pages;
    const visible = matching.slice(galleryPage * GALLERY_PAGE_SIZE, (galleryPage + 1) * GALLERY_PAGE_SIZE);
    const needsThumbnail = [];
    for (const snapshot of visible) {
      const el = document.createElement('article'); el.className = 'cam-snapshot' + (snapshot.flagged ? ' flagged' : '');
      el.dataset.snapshotId = String(snapshot.id);
      const thumbnail = snapshot.thumbnail || thumbnailCache.get(String(snapshot.id));
      const hasThumbnail = typeof thumbnail === 'string' && /^data:image\/(jpeg|png|webp);base64,/.test(thumbnail);
      const viewable = Number(snapshot.sizeBytes) > 0 && !['empty', 'corrupt', 'oversized'].includes(snapshot.reason);
      el.innerHTML = '<button class="cam-thumb" aria-label="Preview snapshot"' + (viewable ? '' : ' disabled') + '>' + (hasThumbnail ? '<img loading="lazy" alt="' + escapeHtml(snapshot.cameraName || snapshot.ip || 'Camera snapshot') + '" src="' + escapeHtml(thumbnail) + '">' : '<span>' + (viewable ? 'Tap to preview' : 'Image requires review') + '</span>') + '</button><div class="cam-snapshot-info"><h3>' + escapeHtml(snapshot.cameraName || 'Camera') + '</h3><small>' + escapeHtml(snapshot.ip) + ' · Channel ' + escapeHtml(snapshot.channel) + '</small><small>' + escapeHtml(captureTime(snapshot)) + '</small><small>' + bytes(snapshot.sizeBytes) + '</small>' + (snapshot.flagged ? '<div class="cam-flag">Review: ' + escapeHtml(snapshot.reason || 'flagged image') + '</div>' : '') + '<div class="actions"><button class="btn ghost preview"' + (viewable ? '' : ' disabled') + '>Preview</button><button class="btn ghost share"' + (viewable ? '' : ' disabled') + '>Share</button><button class="btn ghost export"' + (viewable ? '' : ' disabled') + '>Export</button><button class="btn warn delete">Delete</button></div></div>';
      el.querySelector('.cam-thumb').onclick = el.querySelector('.preview').onclick = () => command('preview-snapshot', {id: snapshot.id});
      el.querySelector('.share').onclick = () => command('share-snapshot', {id: snapshot.id});
      el.querySelector('.export').onclick = () => command('export-snapshot', {id: snapshot.id});
      el.querySelector('.delete').onclick = () => { if (confirm('Delete the saved snapshot of ' + (snapshot.cameraName || snapshot.ip || 'this camera') + ', channel ' + snapshot.channel + '?')) command('delete-snapshot', {id: snapshot.id}); };
      list.appendChild(el);
      if (viewable && !hasThumbnail && !thumbnailRequested.has(String(snapshot.id))) needsThumbnail.push(el);
    }
    if (!matching.length) list.innerHTML = '<div class="empty cam-empty">' + (state.snapshots.length ? 'No snapshots match your filter' : 'No snapshots captured. Select a saved camera and channels, then capture.') + '</div>';
    const requestThumbnail = el => { thumbnailRequested.add(el.dataset.snapshotId); command('thumbnail', {id: el.dataset.snapshotId}); };
    if ('IntersectionObserver' in window) {
      thumbnailObserver = new IntersectionObserver(entries => { for (const entry of entries) if (entry.isIntersecting) { requestThumbnail(entry.target); thumbnailObserver.unobserve(entry.target); } }, {rootMargin: '160px'});
      needsThumbnail.forEach(el => thumbnailObserver.observe(el));
    } else needsThumbnail.forEach(requestThumbnail);
  }
  function renderStorage() {
    $('cam-storage').textContent = 'Gallery storage: ' + bytes(state.storageBytes) + ' · ' + state.snapshots.length + ' image(s).' + (state.storageLocation ? ' Export folder: ' + state.storageLocation : ' No export folder selected.');
  }
  function renderHistory() {
    const query = $('cam-history-filter').value.toLowerCase(); const list = $('cam-history'); list.replaceChildren();
    const matching = state.history.filter(item => [item.cameraName,item.ip,item.host,item.action,item.message,item.timestamp,item.capturedAt,item.at].join(' ').toLowerCase().includes(query));
    for (const item of matching) {
      const el = document.createElement('div'); el.className = 'cam-card';
      const when = item.timestamp || item.at || item.capturedAt || item.time;
      el.innerHTML = '<strong>' + escapeHtml(item.action || item.type || 'Camera action') + '</strong><small>' + escapeHtml(item.cameraName || item.ip || item.host || '') + '</small><small>' + escapeHtml(when ? captureTime({timestamp: when}) : '') + '</small><div>' + escapeHtml(item.message || item.detail || '') + '</div>';
      list.appendChild(el);
    }
    if (!matching.length) list.innerHTML = '<div class="empty">No matching device history</div>';
  }
  function rememberBatchSelection() { storage.write('camera-batch-selection', JSON.stringify([...batchSelection])); }
  function batchPresets() {
    return Array.from({length: 6}, (_, index) => {
      const id = 'preset-' + (index + 1);
      return state.passwordPresets.find(preset => String(preset.id) === id) || state.credentials.find(credential => String(credential.id) === id);
    }).filter(Boolean);
  }
  function configuredBatchPresets() {
    return batchPresets().filter(preset => !preset.unavailable && (preset.configured || preset.hasPassword));
  }
  function batchSetupError(mode = batchCredentialMode) {
    if (mode !== 'saved-passwords') return '';
    if (!state.usernameConfigured || state.usernameUnavailable) return 'Save a shared username in Settings before testing saved passwords.';
    const unavailable = batchPresets().find(preset => preset.unavailable);
    if (unavailable) return 'Replace or delete unavailable password preset ' + String(unavailable.id).slice(-1) + ' in Settings before testing saved passwords.';
    if (!configuredBatchPresets().length) return 'Save at least one password preset in Settings before testing saved passwords.';
    return '';
  }
  function credentialLabel(id) {
    const preset = state.passwordPresets.find(item => String(item.id) === String(id)) || state.credentials.find(item => String(item.id) === String(id));
    return preset && preset.name || (/^preset-[1-6]$/.test(String(id)) ? 'Password preset ' + String(id).slice(-1) : String(id));
  }
  function savedBatchRows() {
    const batch = state.batch || {}, rows = Array.isArray(batch.results) ? batch.results.slice() : [];
    if (Array.isArray(state.batchHistory)) for (const run of state.batchHistory) {
      if (!run || (batch.runId && String(run.runId) === String(batch.runId))) continue;
      if (Array.isArray(run.results)) rows.push(...run.results);
      else if (run.cameraId) rows.push(run);
    }
    return rows;
  }
  function renderBatch() {
    const batch = state.batch || {}, running = !!batch.running;
    const selected = state.cameras.filter(camera => camera.authorized && batchSelection.has(String(camera.id)));
    const mode = running && batch.credentialMode ? batch.credentialMode : batchCredentialMode;
    $('cam-batch-credential-mode').value = mode;
    $('cam-batch-credential-mode').disabled = !!state.busy || running;
    $('cam-batch-mode-help').textContent = mode === 'saved-passwords' ? 'Try the shared username with each configured password from preset 1 to 6. Stop at the first successful authentication, capture snapshots, and continue to the next camera. No per-camera credential assignment is required.' : 'Check each camera with its assigned saved credential, then capture snapshots after a successful connection.';
    $('cam-batch-setup-status').textContent = mode === 'saved-passwords' ? batchSetupError(mode) || 'Shared username saved · ' + configuredBatchPresets().length + ' of 6 password presets ready.' : 'Set each camera\'s saved credential in its profile for this mode.';
    $('cam-batch-selection-count').textContent = selected.length + ' selected';
    $('cam-batch-start').disabled = !!state.busy || running || !selected.length || !!batchSetupError();
    $('cam-batch-stop').disabled = !running;
    $('cam-batch-select-all').disabled = $('cam-batch-clear').disabled = !!state.busy || running;
    const currentCamera = batch.currentCamera && (state.cameras.find(camera => String(camera.id) === String(batch.currentCamera.id)) || batch.currentCamera);
    $('cam-batch-current').textContent = currentCamera ? 'Current: ' + cameraDisplay(currentCamera) : running ? 'Preparing selected cameras…' : batch.message || 'Select registered cameras to run automatic checks.';
    const candidate = running && batch.currentCredential;
    $('cam-batch-credential').textContent = candidate && Number(candidate.index) > 0 && Number(candidate.total) > 0 ? 'Password attempt ' + Number(candidate.index) + ' of ' + Number(candidate.total) + (candidate.id ? ' · ' + credentialLabel(candidate.id) : '') : '';
    $('cam-batch-stage').textContent = running ? batch.message || '' : '';
    const total = Math.max(0, Number(batch.total) || 0), completed = Math.max(0, Number(batch.completed) || 0);
    $('cam-batch-progress').max = total || 1; $('cam-batch-progress').value = Math.min(total, completed);
    $('cam-batch-completed').textContent = completed + ' / ' + total;
    $('cam-batch-successes').textContent = Number(batch.successes) || 0;
    $('cam-batch-failures').textContent = Number(batch.failures) || 0;
    $('cam-batch-snapshots').textContent = Number(batch.snapshotsSaved) || 0;
    let summary = typeof batch.summary === 'string' ? batch.summary : batch.summary && batch.summary.message || '';
    if (!summary && total && !running) summary = (batch.status === 'cancelled' || batch.status === 'stopped' ? 'Stopped' : batch.status === 'interrupted' ? 'Interrupted' : 'Finished') + ': ' + completed + ' of ' + total + ' cameras checked; ' + (Number(batch.successes) || 0) + ' successful connections, ' + (Number(batch.failures) || 0) + ' failures, ' + (Number(batch.snapshotsSaved) || 0) + ' snapshots saved.';
    if (Number(batch.snapshotsFlagged) || Number(batch.snapshotFailures)) summary += ' ' + (Number(batch.snapshotsFlagged) || 0) + ' images flagged; ' + (Number(batch.snapshotFailures) || 0) + ' snapshot failures.';
    $('cam-batch-summary').textContent = summary;
    const rows = savedBatchRows(), list = $('cam-batch-results'); list.replaceChildren();
    $('cam-batch-export').disabled = !rows.length;
    for (const item of rows.slice().reverse()) {
      const el = document.createElement('article'); el.className = 'cam-card cam-batch-result';
      const rawStatus = item.connectionStatus || item.status || 'Completed';
      const status = ({success:'Connected','authentication-failed':'Authentication failed',forbidden:'Access denied',locked:'Locked', 'rate-limited':'Rate limited',unreachable:'Unreachable',timeout:'Timed out',cancelled:'Stopped','snapshot-failed':'Snapshot failed'})[rawStatus] || String(rawStatus).replace(/[_-]/g, ' ');
      const attempts = Number(item.credentialAttempts) || 0;
      const credentialResult = item.credentialMode === 'saved-passwords' ? attempts + ' password attempt(s)' + (item.workingCredentialId ? ' · Working credential: ' + credentialLabel(item.workingCredentialId) : '') : item.workingCredentialId ? 'Credential: ' + credentialLabel(item.workingCredentialId) : '';
      el.innerHTML = '<h3>' + escapeHtml(item.cameraName || item.host || 'Camera') + '</h3><small>' + escapeHtml(item.host || '') + '</small><strong>' + escapeHtml(status) + (item.httpCode ? ' · HTTP ' + escapeHtml(item.httpCode) : '') + '</strong><div>' + escapeHtml(item.message || '') + '</div>' + (credentialResult ? '<small class="cam-batch-credential-result">' + escapeHtml(credentialResult) + '</small>' : '') + '<small>' + escapeHtml(captureTime({timestamp:item.finishedAt || item.startedAt})) + ' · ' + (Number(item.snapshotsSaved) || 0) + ' snapshot(s) saved' + (Number(item.snapshotFailures) ? ' · ' + Number(item.snapshotFailures) + ' snapshot failure(s)' : '') + '</small>';
      list.appendChild(el);
    }
    if (!rows.length) list.innerHTML = '<div class="empty">Completed checks will stay here for review and CSV export.</div>';
  }
  function renderBusy() {
    $('cam-status').setAttribute('aria-busy', String(!!state.busy)); $('cam-cancel').classList.toggle('hidden', !state.busy);
    $('cam-capture').disabled = !!state.busy || !selectedCamera() || !selectedCamera().authorized;
    $('cam-save-channels').disabled = !!state.busy || !selectedCamera() || !selectedCamera().authorized;
    $('cam-save-presets').disabled = presetSavePending || !!state.busy;
    $('cam-list').querySelectorAll('.batch-select').forEach(input => { const camera = state.cameras.find(item => String(item.id) === input.dataset.cameraId); input.disabled = !!state.busy || !!(state.batch && state.batch.running) || camera && !camera.authorized; });
    renderBatch();
    updateMethod();
  }
  function authorizedAction(action, extra = {}) {
    const camera = selectedCamera();
    if (!camera) return notify('Save and select a camera first.');
    if (!camera.authorized) return notify('Edit this camera and confirm you are authorized to administer it.');
    if (state.busy) return notify('Stop the current camera task first.');
    command(action, Object.assign({id: camera.id}, extra));
  }
  function bindActions(view) {
    view.querySelectorAll('[data-camera-tab]').forEach(el => el.onclick = () => showTab(el.dataset.cameraTab));
    $('cam-filter').oninput = renderCameras; $('cam-selected').onchange = () => selectCamera($('cam-selected').value);
    $('cam-method').onchange = updateMethod;
    $('cam-new').onclick = $('cam-clear-form').onclick = () => { resetCameraForm(); $('cam-form-heading').scrollIntoView({block: 'start', behavior: 'smooth'}); };
    $('cam-form').addEventListener('input', persistDraft); $('cam-form').addEventListener('change', persistDraft);
    $('cam-scheme').addEventListener('change', () => { if ($('cam-scheme').value === 'https' && $('cam-port').value === '80') $('cam-port').value = '443'; else if ($('cam-scheme').value === 'http' && $('cam-port').value === '443') $('cam-port').value = '80'; persistDraft(); });
    $('cam-form').onsubmit = event => {
      event.preventDefault();
      const data = cameraFormData(); if (!data.authorized) return notify('Confirm camera authorization before saving.');
      if (/[:/?#@\s]/.test(data.host) && !/^\[[0-9a-f:]+\]$/i.test(data.host)) return notify('Enter only an IP address or hostname. Set the protocol, port and paths separately.');
      for (const key of ['httpPath','snapshotPath','mjpegPath','rtspPath','hlsPath']) if (/^\w+:\/\/[^/]*@/.test(data[key])) return notify('Keep usernames and passwords in Credentials, outside camera URLs.');
      if (command('save-camera', data)) pendingCameraForm = {id:data.id,host:data.host,port:data.port};
      persistDraft();
    };
    $('cam-open').onclick = () => authorizedAction('open-viewer', {method: $('cam-method').value});
    $('cam-test').onclick = () => authorizedAction('test-camera', {method: $('cam-method').value});
    $('cam-select-all').onclick = () => { $('cam-channels').querySelectorAll('input').forEach(el => el.checked = true); updateChannelCount(); };
    $('cam-select-none').onclick = () => { $('cam-channels').querySelectorAll('input').forEach(el => el.checked = false); updateChannelCount(); };
    $('cam-channels').onchange = updateChannelCount;
    $('cam-add-channel').onclick = () => {
      const channel = Number($('cam-custom-channel').value);
      if (!Number.isInteger(channel) || channel < 1 || channel > 65535) return notify('Enter a channel number from 1 to 65535.');
      const selected = [...new Set([...selectedChannels(), channel])];
      if (selected.length > 64) return notify('Select up to 64 channels per camera.');
      storage.write('camera-channels-' + selectedId, JSON.stringify(selected)); $('cam-custom-channel').value = ''; renderChannels();
    };
    $('cam-save-channels').onclick = () => {
      const camera = selectedCamera(), channels = selectedChannels();
      if (!camera || !camera.authorized) return notify('Select an authorized registered camera first.');
      if (state.busy) return notify('Stop the current task before changing snapshot channels.');
      if (!channels.length) return notify('Select at least one snapshot channel.');
      command('save-camera', Object.assign({}, camera, {channels}));
    };
    $('cam-capture').onclick = () => { const channels = selectedChannels(); if (!channels.length) return notify('Select at least one snapshot channel.'); authorizedAction('capture', {channels}); };
    $('cam-go-gallery').onclick = () => showTab('gallery');
    $('cam-cancel').onclick = () => command('cancel');
    $('cam-batch-select-all').onclick = () => { batchSelection = new Set(state.cameras.filter(camera => camera.authorized).map(camera => String(camera.id))); rememberBatchSelection(); renderCameras(); };
    $('cam-batch-clear').onclick = () => { batchSelection.clear(); rememberBatchSelection(); renderCameras(); };
    $('cam-batch-credential-mode').onchange = () => {
      if (state.busy || state.batch && state.batch.running) return;
      batchCredentialMode = $('cam-batch-credential-mode').value === 'assigned' ? 'assigned' : 'saved-passwords';
      storage.write('camera-batch-credential-mode', batchCredentialMode); renderBatch();
    };
    $('cam-batch-credential-settings').onclick = openCredentialSettings;
    $('cam-batch-start').onclick = () => {
      if (state.busy || state.batch && state.batch.running) return notify('A camera task is already running.');
      const cameraIds = state.cameras.filter(camera => camera.authorized && batchSelection.has(String(camera.id))).map(camera => camera.id);
      if (!cameraIds.length) return notify('Select at least one registered camera for automatic checks.');
      const setupError = batchSetupError();
      if (setupError) return notify(setupError);
      if (command('batch-start', {cameraIds,credentialMode:batchCredentialMode})) {
        state.busy = true; state.batch = {running:true,status:'running',credentialMode:batchCredentialMode,total:cameraIds.length,completed:0,successes:0,failures:0,snapshotsSaved:0,message:'Preparing selected cameras…',results:[]};
        $('cam-status-text').textContent = 'Automatic camera checks starting…'; renderBusy();
      }
    };
    $('cam-batch-stop').onclick = () => { if (command('cancel')) { $('cam-batch-stop').disabled = true; $('cam-status-text').textContent = 'Stopping automatic camera checks…'; } };
    $('cam-batch-export').onclick = () => command('batch-export');
    $('cam-batch-diagnostics').onclick = () => { const exportButton = $('diag-export'); if (exportButton) exportButton.click(); };
    $('cam-refresh').onclick = $('cam-gallery-refresh').onclick = () => { refresh(); command('refresh'); };
    $('cam-import-config').onclick = () => command('import-config'); $('cam-export-config').onclick = () => command('export-config');
    $('cam-snapshot-filter').oninput = $('cam-only-flagged').onchange = $('cam-gallery-camera').onchange = $('cam-gallery-date').onchange = () => { galleryPage = 0; renderSnapshots(); };
    $('cam-gallery-clear-filters').onclick = () => { $('cam-snapshot-filter').value = ''; $('cam-gallery-camera').value = ''; $('cam-gallery-date').value = ''; $('cam-only-flagged').checked = false; galleryPage = 0; renderSnapshots(); };
    $('cam-gallery-previous').onclick = () => { galleryPage = Math.max(0, galleryPage - 1); renderSnapshots(); };
    $('cam-gallery-next').onclick = () => { galleryPage++; renderSnapshots(); };
    $('cam-select-storage').onclick = () => command('select-storage');
    $('cam-delete-flagged').onclick = () => { const count = state.snapshots.filter(snapshot => snapshot.flagged).length; if (count && confirm('Permanently delete all ' + count + ' flagged snapshots, including exact duplicates and corrupted or empty downloads?')) command('delete-flagged'); };
    $('cam-open-credential-settings').onclick = openCredentialSettings;
    $('cam-preset-username').oninput = () => { usernameDirty = true; };
    $('cam-save-presets').onclick = () => {
      const username = $('cam-preset-username').value.trim();
      if (username.includes(':') || /[\r\n\u0000]/.test(username) || username.length > 256) return notify('Enter a username without colons or line breaks, up to 256 characters.');
      const presets = Array.from({length:6}, (_, i) => ({id:'preset-' + (i + 1), password:$('cam-preset-' + (i + 1)).value}));
      if (!username && !state.usernameConfigured) return notify('Enter the shared camera username.');
      if (!usernameDirty && !presets.some(preset => preset.password)) return notify('Enter a username change or a new password to save.');
      if (presets.some(preset => preset.password.length > 1024)) return notify('One password is too long.');
      if (command('save-presets', {username,presets})) { presetSavePending = true; renderCredentials(); }
    };
    for (let i = 1; i <= 6; i++) $('cam-delete-preset-' + i).onclick = () => {
      const id = 'preset-' + i, count = state.cameras.filter(camera => camera.credentialId === id).length;
      if (confirm('Delete password preset ' + i + '?' + (count ? ' ' + count + ' camera profile(s) will need another assigned credential.' : ''))) command('save-presets', {presets:[{id,delete:true}]});
    };
    $('cam-delete-all-presets').onclick = () => { if (confirm('Delete the saved shared username and all six password presets? Camera profiles and snapshots will remain. Existing legacy credentials will remain.')) { if (command('save-presets', {deleteUsername:true})) { usernameDirty = false; $('cam-preset-username').value = ''; for (let i=1;i<=6;i++) $('cam-preset-'+i).value = ''; } } };
    $('cam-history-filter').oninput = renderHistory;
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
    const nav = document.querySelector('.nav [data-view="cameras"]'); if (nav) nav.addEventListener('click', refresh);
    const galleryNav = document.querySelector('.nav [data-view="gallery"]'); if (galleryNav) galleryNav.addEventListener('click', () => { refresh(); renderSnapshots(); });
  }
  window.receiveCameraNative = (kind, data) => {
    if (!initialized) init();
    data = data || {};
    if (kind === 'batch-check' || kind === 'batch-snapshot-check') { addDiagnostic(data.error || data.message); return; }
    if (kind === 'camera-thumbnail') {
      if (typeof data.thumbnail === 'string' && /^data:image\/(jpeg|png|webp);base64,/.test(data.thumbnail)) {
        thumbnailCache.set(String(data.id), data.thumbnail);
        if (thumbnailCache.size > 150) thumbnailCache.delete(thumbnailCache.keys().next().value);
        const card = Array.from($('cam-gallery').children).find(el => el.dataset.snapshotId === String(data.id));
        if (card) { const img = document.createElement('img'); img.alt = 'Camera snapshot'; img.src = data.thumbnail; card.querySelector('.cam-thumb').replaceChildren(img); }
      }
      return;
    }
    if (kind === 'camera-state') {
      if (Array.isArray(data.cameras)) applyState(data); else if (data.state) applyState(data.state); else refresh();
      return;
    }
    if (kind === 'batch-progress') {
      state.batch = Object.assign({}, state.batch || {}, data);
      if (typeof data.running === 'boolean') state.busy = data.running;
      if (data.message) $('cam-status-text').textContent = data.message;
      renderBusy();
      if (!data.running && data.summary) addDiagnostic(typeof data.summary === 'string' ? data.summary : data.summary.message);
      return;
    }
    if (kind === 'camera-progress') {
      if (typeof data.busy === 'boolean') state.busy = data.busy;
      if (data.message) $('cam-status-text').textContent = data.message;
      const total = Number(data.total), completed = Number(data.completed == null ? data.current : data.completed);
      $('cam-progress').classList.toggle('hidden', !state.busy);
      if (total > 0) { $('cam-progress').max = total; $('cam-progress').value = Math.max(0, completed || 0); } else $('cam-progress').removeAttribute('value');
      renderBusy(); return;
    }
    const message = data.error || data.message || (data.ok === false ? 'Camera operation failed.' : 'Camera operation completed.');
    if (data.action === 'save-camera' && data.ok === false) pendingCameraForm = null;
    // Android may deliver a native acknowledgement after a state refresh. A successful
    // save always clears transient password input, even when the pending flag was
    // already reset by another callback; failed saves retain the unsaved edits.
    if (data.action === 'save-presets') {
      presetSavePending = false;
      if (data.ok !== false && !data.error) {
        usernameDirty = false;
        for (let i = 1; i <= 6; i++) $('cam-preset-' + i).value = '';
      }
    }
    $('cam-status-text').textContent = message; addDiagnostic(message); notify(message); refresh();
    if (!state.busy) $('cam-progress').classList.add('hidden');
  };
  window.CameraManager = {
    registerFromIp(host, port, source) {
      init();
      host = String(host || '').trim();
      if (!host) return notify('Select an address to register as a camera.');
      const selectedPort = Number(port);
      const nav = document.querySelector('.nav [data-view="cameras"]'); if (nav) nav.click();
      showTab('devices'); resetCameraForm();
      $('cam-name').value = 'Camera ' + host; $('cam-host').value = host;
      const rtsp = selectedPort === 554 || selectedPort === 8554;
      $('cam-port').value = !rtsp && Number.isInteger(selectedPort) && selectedPort > 0 && selectedPort <= 65535 ? String(selectedPort) : '80';
      $('cam-scheme').value = selectedPort === 443 || selectedPort === 8443 ? 'https' : 'http';
      if (rtsp) { $('cam-rtsp-port').value = String(selectedPort); $('cam-rtsp-path').value = '/Streaming/Channels/{channel}'; }
      $('cam-method').value = rtsp ? 'rtsp' : 'http'; updateMethod();
      $('cam-authorized').checked = false; persistDraft();
      addDiagnostic('Camera registration prepared from ' + String(source || 'IP List') + '. Review its connection settings and confirm authorization before saving.');
      $('cam-form-heading').scrollIntoView({block:'start',behavior:'smooth'}); $('cam-name').focus();
    },
    showGallery() { init(); showTab('gallery'); refresh(); },
    refresh
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();

