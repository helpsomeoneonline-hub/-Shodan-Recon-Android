(() => {
  'use strict';
  const DEFAULT_CHANNELS = Array.from({length: 16}, (_, n) => (n + 1) * 100 + 1);
  const $ = id => document.getElementById(id);
  const escapeHtml = value => String(value == null ? '' : value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const storage = {read(key) { try { return sessionStorage.getItem(key); } catch (_) { return null; } }, write(key, value) { try { sessionStorage.setItem(key, value); } catch (_) {} }};
  let state = {cameras: [], credentials: [], snapshots: [], history: [], storageBytes: 0, busy: false};
  let selectedId = storage.read('camera-selected') || '', activeTab = storage.read('camera-tab') || 'devices';
  let initialized = false, diagnostics = [], toastTimer, galleryPage = 0, thumbnailObserver;
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
    state = Object.assign({}, state, data);
    for (const field of ['cameras', 'credentials', 'snapshots', 'history']) if (!Array.isArray(state[field])) state[field] = [];
    if (!state.cameras.some(camera => String(camera.id) === selectedId)) selectedId = state.cameras.length ? String(state.cameras[0].id) : '';
    storage.write('camera-selected', selectedId);
    renderCameras(); renderCredentials(); renderSnapshots(); renderHistory(); renderStorage(); renderBusy();
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
            <p class="desc">Choose a saved credential manually. Create or edit it in the Credentials tab.</p>
            ${field('Camera web interface path', 'cam-http-path', '/', 'placeholder="/" autocapitalize="none" spellcheck="false"')}
            ${field('Snapshot URL template', 'cam-snapshot-path', '/ISAPI/Streaming/channels/{channel}/picture', 'required autocapitalize="none" spellcheck="false"')}
            <p class="desc">Use {channel} for channel selection, for example /ISAPI/Streaming/channels/{channel}/picture. A manual full URL must address this same camera. Keep passwords in Credentials.</p>
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
        <section class="section"><h2>Connect and capture</h2>
          <div class="field"><label for="cam-selected">Registered camera</label><select id="cam-selected"><option value="">Save a camera first</option></select></div>
          <div class="field"><label for="cam-method">Viewing method</label><select id="cam-method"><option value="http">HTTP / HTTPS interface</option><option value="snapshot">JPEG snapshot</option><option value="mjpeg">MJPEG live stream</option><option value="rtsp">RTSP live stream</option><option value="hls">HLS live stream</option></select></div>
          <p id="cam-method-help" class="desc"></p>
          <div class="actions"><button class="btn primary fill cam-network-action" id="cam-open">Open viewer</button><button class="btn ghost cam-network-action" id="cam-test">Test connection</button></div>
          <div class="line"></div><h2>Snapshot channels <span class="counter" id="cam-selected-count">1 selected</span></h2>
          <p>Capture uses the saved camera and its chosen credential. Select one channel for a single capture, or several for a batch.</p>
          <div class="actions"><button class="btn ghost" id="cam-select-all">Select all</button><button class="btn ghost" id="cam-select-none">Clear selection</button></div>
          <div class="cam-channels" id="cam-channels"></div>
          <div class="actions"><button class="btn primary fill cam-network-action" id="cam-capture">Capture selected</button><button class="btn ghost" id="cam-go-gallery">Gallery</button></div>
          <div class="line"></div><h2>Connection diagnostics</h2><div class="cam-diagnostics" id="cam-diagnostics" role="log">Save a camera, then test its connection. Network and authentication errors appear here.</div>
          <p>Basic and supported Digest authentication use your manually selected credential. HTTPS certificate errors are reported; camera access restrictions are respected.</p>
        </section>
      </div></div>
      <div class="cam-pane" id="cam-pane-gallery"><section class="section"><h2>Snapshot gallery <span class="counter" id="cam-snapshot-count">0</span></h2>
        <div class="cam-gallery-toolbar"><div class="field"><label for="cam-snapshot-filter">Search snapshots</label><input class="input" id="cam-snapshot-filter" type="search" placeholder="Camera, IP, channel or capture date"></div><label class="cam-check"><input type="checkbox" id="cam-only-flagged">Show images flagged for review</label></div>
        <div class="actions"><button class="btn warn" id="cam-delete-flagged">Delete flagged images</button><button class="btn ghost" id="cam-gallery-refresh">Refresh gallery</button></div>
        <p class="cam-storage" id="cam-storage"></p><div class="actions"><button class="btn ghost" id="cam-select-storage">Choose export folder</button></div>
        <p>Downloaded images stay in this app's gallery. Choose an Android folder for exports, or export an individual image to a location you select. Exact duplicate, empty and corrupted downloads are flagged for review.</p>
        <div class="cam-gallery" id="cam-gallery"></div><div class="actions" style="margin-top:14px"><button class="btn ghost" id="cam-gallery-previous">Previous</button><span class="counter" id="cam-gallery-page"></span><button class="btn ghost" id="cam-gallery-next">Next</button></div>
      </section></div>
      <div class="cam-pane" id="cam-pane-credentials"><section class="section">
        <h2>Six reusable credential presets</h2>
        <p>Set one username and up to six password presets for cameras you manage. They are stored encrypted in Android Keystore. Assign one preset to each registered camera; no automatic password-list testing occurs.</p>
        <div class="field"><label for="cam-preset-username">Shared username</label><input class="input" id="cam-preset-username" autocomplete="off" autocapitalize="none" spellcheck="false" placeholder="Camera username"></div>
        <div class="two">${Array.from({length:6},(_,i)=>'<div class="field"><label for="cam-preset-'+(i+1)+'">Password '+(i+1)+'</label><input class="input" id="cam-preset-'+(i+1)+'" type="password" autocomplete="new-password" placeholder="Leave empty to keep saved preset"><small id="cam-preset-status-'+(i+1)+'">Not saved</small></div>').join('')}</div>
        <div class="actions"><button class="btn primary" id="cam-save-presets">Save entered presets</button></div>
        <p class="desc">Leave a password field blank to keep its existing saved value. To remove a preset, delete it from Saved device credentials below. Select its credential in an authorized camera profile.</p>
      </section><div class="cam-layout"><section class="section"><h2>Saved device credentials</h2><p class="cam-credential-note">Secrets are encrypted with Android Keystore. Camera profiles choose credentials individually. This app never tries passwords on Shodan results.</p><div id="cam-credential-list"></div></section>
        <section class="section"><h2 id="cam-credential-heading">Add credential</h2><form id="cam-credential-form" autocomplete="off"><input type="hidden" id="cam-credential-id">
          ${field('Credential name', 'cam-credential-name', '', 'required maxlength="100" placeholder="Entrance camera account"')}
          ${field('Username', 'cam-credential-user', '', 'autocapitalize="none" spellcheck="false" autocomplete="off"')}
          ${field('Password', 'cam-credential-password', '', 'type="password" autocomplete="new-password"')}
          <p class="desc" id="cam-credential-edit-help">Enter the username and password assigned to your authorized camera.</p>
          <div class="actions"><button class="btn primary fill" type="submit">Save encrypted credential</button><button class="btn ghost" id="cam-new-credential" type="button">New</button></div>
        </form></section></div></div>
      <div class="cam-pane" id="cam-pane-activity"><section class="section"><h2>Device history</h2><p>Connection tests, captures and device actions are stored locally. Passwords are never included.</p><div class="field"><label for="cam-history-filter">Filter device history</label><input class="input" id="cam-history-filter" type="search" placeholder="Camera, action or date"></div><div class="cam-history" id="cam-history"></div></section></div>`;
    document.querySelector('.app').appendChild(view);
    bindActions(view); resetCameraForm(true); refresh(); restoreDraft(); showTab(activeTab);
    if (document.querySelector('.nav [data-view="cameras"].active')) view.classList.add('active');
    if (!bridge()) { $('cam-status-text').textContent = 'Camera services require the Android app.'; }
  }
  function showTab(tab) {
    if (!['devices', 'gallery', 'credentials', 'activity'].includes(tab)) tab = 'devices';
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
      el.innerHTML = '<h3>' + escapeHtml(camera.name || 'Camera') + '</h3><small>' + escapeHtml((camera.scheme || 'http') + '://' + (camera.host || '') + ':' + camera.port) + '</small><small>' + (camera.authorized ? 'Registered as authorized' : 'Authorization required before connecting') + '</small><div class="actions"><button class="btn primary use">Select</button><button class="btn ghost edit">Edit</button><button class="btn warn delete">Delete</button></div>';
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
    const channels = [...new Set([...DEFAULT_CHANNELS, ...(camera && Array.isArray(camera.channels) ? camera.channels : [])])];
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
      $('cam-credential').add(new Option(credential.name || 'Credential', String(credential.id)));
      const el = document.createElement('div'); el.className = 'cam-card';
      el.innerHTML = '<h3>' + escapeHtml(credential.name || 'Credential') + '</h3><small>' + (credential.hasPassword ? 'Password stored securely' : 'No saved password') + '</small><div class="actions"><button class="btn ghost edit">Edit</button><button class="btn warn delete">Delete</button></div>';
      el.querySelector('.edit').onclick = () => { $('cam-credential-id').value = credential.id; $('cam-credential-name').value = credential.name || ''; $('cam-credential-user').value = ''; $('cam-credential-password').value = ''; $('cam-credential-heading').textContent = 'Edit credential'; $('cam-credential-edit-help').textContent = 'Saved secrets are not displayed. Leave username or password blank to retain its saved value.'; };
      el.querySelector('.delete').onclick = () => { const associated = state.cameras.filter(camera => String(camera.credentialId) === String(credential.id)).length; if (confirm('Delete this saved credential?' + (associated ? ' It is selected by ' + associated + ' camera profile(s); those cameras will need another credential.' : ''))) command('delete-credential', {id: credential.id}); };
      list.appendChild(el);
    }
    for (let i=1;i<=6;i++) {
      const preset=state.credentials.find(c=>String(c.id)==='preset-'+i);
      const status=$('cam-preset-status-'+i);
      if(status) status.textContent=preset && preset.hasPassword?'Saved securely':'Not saved';
    }
    if (!state.credentials.length) list.innerHTML = '<div class="empty">No device credentials saved</div>';
    if (state.credentials.some(credential => String(credential.id) === chosen)) $('cam-credential').value = chosen;
  }
  function resetCredentialForm() {
    $('cam-credential-form').reset(); $('cam-credential-id').value = ''; $('cam-credential-heading').textContent = 'Add credential';
    $('cam-credential-edit-help').textContent = 'Enter the username and password assigned to your authorized camera.';
  }
  function bytes(value) { const size = Number(value) || 0; return size < 1024 ? size + ' B' : size < 1024 * 1024 ? (size / 1024).toFixed(1) + ' KB' : (size / 1024 / 1024).toFixed(1) + ' MB'; }
  function captureTime(snapshot) { const value = snapshot.capturedAt || snapshot.timestamp; const date = new Date(value); return Number.isNaN(date.valueOf()) ? String(value || 'Unknown time') : date.toLocaleString(); }
  function renderSnapshots() {
    const query = $('cam-snapshot-filter').value.toLowerCase(), flagged = $('cam-only-flagged').checked;
    const matching = state.snapshots.filter(snapshot => (!flagged || snapshot.flagged) && (!query || [snapshot.cameraName,snapshot.ip,snapshot.channel,captureTime(snapshot),snapshot.capturedAt,snapshot.reason].join(' ').toLowerCase().includes(query)));
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
  function renderBusy() {
    $('cam-status').setAttribute('aria-busy', String(!!state.busy)); $('cam-cancel').classList.toggle('hidden', !state.busy);
    $('cam-capture').disabled = !!state.busy || !selectedCamera() || !selectedCamera().authorized;
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
      command('save-camera', data); persistDraft();
    };
    $('cam-open').onclick = () => authorizedAction('open-viewer', {method: $('cam-method').value});
    $('cam-test').onclick = () => authorizedAction('test-camera', {method: $('cam-method').value});
    $('cam-select-all').onclick = () => { $('cam-channels').querySelectorAll('input').forEach(el => el.checked = true); updateChannelCount(); };
    $('cam-select-none').onclick = () => { $('cam-channels').querySelectorAll('input').forEach(el => el.checked = false); updateChannelCount(); };
    $('cam-channels').onchange = updateChannelCount;
    $('cam-capture').onclick = () => { const channels = selectedChannels(); if (!channels.length) return notify('Select at least one snapshot channel.'); authorizedAction('capture', {channels}); };
    $('cam-go-gallery').onclick = () => showTab('gallery');
    $('cam-cancel').onclick = () => command('cancel');
    $('cam-refresh').onclick = $('cam-gallery-refresh').onclick = () => { refresh(); command('refresh'); };
    $('cam-import-config').onclick = () => command('import-config'); $('cam-export-config').onclick = () => command('export-config');
    $('cam-snapshot-filter').oninput = $('cam-only-flagged').onchange = () => { galleryPage = 0; renderSnapshots(); };
    $('cam-gallery-previous').onclick = () => { galleryPage = Math.max(0, galleryPage - 1); renderSnapshots(); };
    $('cam-gallery-next').onclick = () => { galleryPage++; renderSnapshots(); };
    $('cam-select-storage').onclick = () => command('select-storage');
    $('cam-delete-flagged').onclick = () => { const count = state.snapshots.filter(snapshot => snapshot.flagged).length; if (count && confirm('Permanently delete all ' + count + ' flagged snapshots, including exact duplicates and corrupted or empty downloads?')) command('delete-flagged'); };
    $('cam-save-presets').onclick=()=>{
      const username=$('cam-preset-username').value.trim();
      if(!username || username.includes(':')) return notify('Enter a valid username before saving presets.');
      const entries=[];
      for(let i=1;i<=6;i++){
        const password=$('cam-preset-'+i).value;
        if(password) entries.push({id:'preset-'+i,name:'Password preset '+i,username,password});
      }
      if(!entries.length) return notify('Enter at least one new password to save.');
      if(entries.some(e=>e.password.length>1024)) return notify('One password is too long.');
      for(const entry of entries) command('save-credential',entry);
      for(let i=1;i<=6;i++) $('cam-preset-'+i).value='';
      notify('Saving '+entries.length+' encrypted preset(s). Assign one to each camera profile.');
    };
    $('cam-new-credential').onclick = resetCredentialForm;
    $('cam-credential-form').onsubmit = event => {
      event.preventDefault(); const id = $('cam-credential-id').value;
      const data = {id, name: $('cam-credential-name').value.trim(), username: $('cam-credential-user').value, password: $('cam-credential-password').value};
      if (!id && !data.username.trim()) return notify('Enter the authorized camera username.');
      if (command('save-credential', data)) { $('cam-credential-user').value = ''; $('cam-credential-password').value = ''; }
    };
    $('cam-history-filter').oninput = renderHistory;
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
    const nav = document.querySelector('.nav [data-view="cameras"]'); if (nav) nav.addEventListener('click', refresh);
  }
  window.receiveCameraNative = (kind, data) => {
    if (!initialized) init();
    data = data || {};
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
    if (kind === 'camera-progress') {
      if (typeof data.busy === 'boolean') state.busy = data.busy;
      if (data.message) $('cam-status-text').textContent = data.message;
      const total = Number(data.total), completed = Number(data.completed == null ? data.current : data.completed);
      $('cam-progress').classList.toggle('hidden', !state.busy);
      if (total > 0) { $('cam-progress').max = total; $('cam-progress').value = Math.max(0, completed || 0); } else $('cam-progress').removeAttribute('value');
      renderBusy(); return;
    }
    const message = data.error || data.message || (data.ok === false ? 'Camera operation failed.' : 'Camera operation completed.');
    $('cam-status-text').textContent = message; addDiagnostic(message); notify(message); refresh();
    if (!state.busy) $('cam-progress').classList.add('hidden');
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init); else init();
})();
