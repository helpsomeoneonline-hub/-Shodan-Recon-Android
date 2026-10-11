import assert from 'node:assert/strict';
import {cameraDashboard} from './camera-test-fixture.mjs';

const cameras = [
  {id:'front',name:'Front',host:'192.168.1.10',port:80,scheme:'http',authorized:true,credentialId:'preset-1',channels:[101],httpPath:'/',snapshotPath:'/snapshot/{channel}',rtspPort:554},
  {id:'imported',name:'Needs registration',host:'192.168.1.11',port:80,scheme:'http',authorized:false,channels:[101]},
  {id:'back',name:'Back',host:'192.168.1.12',port:443,scheme:'https',authorized:true,credentialId:'preset-2',channels:[201],httpPath:'/',snapshotPath:'/snapshot/{channel}',rtspPort:554}
];
const credentials = [{id:'preset-1',name:'Password preset 1',hasPassword:true},{id:'preset-2',name:'Password preset 2',hasPassword:true}];
const initial = {cameras,credentials,passwordPresets:[],history:[],snapshots:[],busy:false,batch:{}};
let passed = 0;
function test(name, work) { work(); passed++; console.log('PASS ' + name); }

test('individual and bulk selections include only authorized registered cameras', () => {
  const app = cameraDashboard(initial);
  assert.equal(app.element('cam-batch-start').disabled, true);
  const checks = app.element('cam-list').querySelectorAll('.batch-select');
  assert.equal(checks.length, 3);
  assert.equal(checks[1].disabled, true);
  checks[2].checked = true; checks[2].dispatch('change');
  assert.equal(app.element('cam-batch-selection-count').textContent, '1 selected');
  app.reply('camera-state', initial);
  assert.equal(app.element('cam-list').querySelectorAll('.batch-select')[2].checked, true);
  app.click('cam-batch-select-all');
  assert.equal(app.element('cam-batch-selection-count').textContent, '2 selected');
  app.click('cam-batch-clear');
  assert.equal(app.element('cam-batch-start').disabled, true);
});

test('Start sends one camera-ID batch and locks repeated Start/manual capture', () => {
  const app = cameraDashboard(initial);
  app.click('cam-batch-select-all'); app.click('cam-batch-start'); app.click('cam-batch-start');
  assert.equal(app.callsTo('batch-start').length, 1);
  assert.deepEqual(app.callsTo('batch-start')[0].data, {cameraIds:['front','back']});
  assert.equal(app.element('cam-capture').disabled, true);
  assert.equal(app.element('cam-batch-select-all').disabled, true);
  assert.equal(app.element('cam-batch-stop').disabled, false);
  assert.equal(app.element('cam-batch-completed').textContent, '0 / 2');
});

test('native sequential progress updates current camera and independent counters', () => {
  const app = cameraDashboard(initial);
  app.reply('batch-progress', {running:true,total:2,completed:1,successes:1,failures:0,snapshotsSaved:2,snapshotsFlagged:1,snapshotFailures:1,currentCamera:cameras[2],message:'Checking Back'});
  assert.match(app.element('cam-batch-current').textContent, /Back.*192\.168\.1\.12/);
  assert.equal(app.element('cam-batch-completed').textContent, '1 / 2');
  assert.equal(app.element('cam-batch-successes').textContent, '1');
  assert.equal(app.element('cam-batch-failures').textContent, '0');
  assert.equal(app.element('cam-batch-snapshots').textContent, '2');
  assert.equal(app.element('cam-batch-progress').value, 1);
  app.click('cam-batch-stop');
  assert.equal(app.callsTo('cancel').length, 1);
  assert.equal(app.element('cam-batch-stop').disabled, true);
});

test('completed and prior runs remain reviewable and CSV export uses native picker', () => {
  const result = {cameraId:'front',cameraName:'Front',host:'192.168.1.10',connectionStatus:'success',status:'success',httpCode:200,message:'Connected',finishedAt:'2026-10-11T10:00:00Z',snapshotsSaved:1};
  const batch = {running:false,status:'complete',runId:'latest',total:2,completed:2,successes:1,failures:1,snapshotsSaved:1,summary:'Finished two checks',results:[result,{...result,cameraId:'back',cameraName:'Back',connectionStatus:'authentication-failed',httpCode:401}]};
  const app = cameraDashboard({...initial,batch,batchHistory:[{runId:'earlier',results:[result]}]});
  assert.equal(app.element('cam-batch-results').children.length, 3);
  assert.equal(app.element('cam-batch-summary').textContent, 'Finished two checks');
  assert.equal(app.element('cam-batch-export').disabled, false);
  app.click('cam-batch-export');
  assert.deepEqual(app.callsTo('batch-export')[0].data, {});
  app.reply('batch-progress', {running:false,status:'cancelled',total:2,completed:1,successes:1,failures:0,snapshotsSaved:1,currentCamera:null,summary:''});
  assert.match(app.element('cam-batch-summary').textContent, /Stopped: 1 of 2/);
});

test('batch check diagnostic events do not trigger refreshes or toasts', () => {
  const app = cameraDashboard(initial);
  const toast = app.element('toast').textContent;
  app.reply('batch-check', {ok:false,message:'HTTP 401 authentication failed'});
  app.reply('batch-snapshot-check', {ok:true,message:'Snapshot saved'});
  assert.equal(app.element('toast').textContent, toast);
  assert.match(app.element('cam-diagnostics').textContent, /Snapshot saved/);
});

test('IP registration prefills HTTP HTTPS and RTSP settings without starting connections', () => {
  const app = cameraDashboard(initial);
  app.window.CameraManager.registerFromIp('192.168.1.20',8443,'Private scan');
  assert.equal(app.element('cam-host').value, '192.168.1.20');
  assert.equal(app.element('cam-port').value, '8443');
  assert.equal(app.element('cam-scheme').value, 'https');
  assert.equal(app.element('cam-authorized').checked, false);
  assert.equal(app.calls.length, 0);
  app.window.CameraManager.registerFromIp('192.168.1.21',8554,'Shodan');
  assert.equal(app.element('cam-port').value, '80');
  assert.equal(app.element('cam-rtsp-port').value, '8554');
  assert.equal(app.element('cam-method').value, 'rtsp');
  assert.match(app.element('cam-rtsp-path').value, /\{channel\}/);
  assert.equal(app.calls.length, 0);
});

test('gallery camera and capture-date filters compose and clear together', () => {
  const snapshots = [
    {id:'a',cameraName:'Front',ip:'192.168.1.10',capturedAt:'2026-10-10T12:00:00Z',channel:101,sizeBytes:100},
    {id:'b',cameraName:'Front',ip:'192.168.1.10',capturedAt:'2026-10-11T12:00:00Z',channel:101,sizeBytes:100},
    {id:'c',cameraName:'Back',ip:'192.168.1.12',capturedAt:'2026-10-11T12:00:00Z',channel:201,sizeBytes:100}
  ];
  const app = cameraDashboard({...initial,snapshots});
  assert.equal(app.element('view-gallery').children.length, 1);
  app.change('cam-gallery-camera','192.168.1.10');
  assert.equal(app.element('cam-snapshot-count').textContent, '2 / 3');
  app.change('cam-gallery-date','2026-10-11');
  assert.equal(app.element('cam-snapshot-count').textContent, '1 / 3');
  assert.equal(app.element('cam-gallery').children[0].dataset.snapshotId, 'b');
  app.click('cam-gallery-clear-filters');
  assert.equal(app.element('cam-snapshot-count').textContent, '3 / 3');
});

test('custom snapshot channels can be persisted on the selected camera for future batches', () => {
  const app = cameraDashboard(initial);
  app.click('cam-select-none');
  app.input('cam-custom-channel',77); app.click('cam-add-channel');
  assert.equal(app.element('cam-selected-count').textContent, '1 selected');
  app.click('cam-save-channels');
  assert.deepEqual(app.callsTo('save-camera')[0].data.channels, [77]);
  assert.equal(app.callsTo('save-camera')[0].data.id, 'front');
  assert.equal(app.callsTo('capture').length, 0);
});

console.log(passed + ' camera batch/gallery behavior tests passed.');
