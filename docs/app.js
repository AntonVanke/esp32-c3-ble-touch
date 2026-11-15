const $ = (id) => document.getElementById(id);
let port = null, reader = null, writer = null, readTask = null;
let phoneReady = false, serialOpening = false, closing = false;
let writeChain = Promise.resolve();
let activePointer = null, lastMove = 0, currentPoint = {x: 500, y: 500};
const logLines = [];
const supported = 'serial' in navigator && window.isSecureContext;
$('browser-warning').hidden = supported;
$('connect').disabled = !supported;
$('touchpad').dataset.ready = 'false';
const targetUrl = new URL('target.html', location.href).href;
$('target-url').textContent = targetUrl;

function log(message) {
  logLines.push(`${new Date().toLocaleTimeString()}  ${message}`);
  if (logLines.length > 150) logLines.shift();
  $('log').textContent = logLines.join('\n');
  $('log').scrollTop = $('log').scrollHeight;
}
function controls() {
  const live = !!writer && !closing;
  $('connect').disabled = !supported || !!port || serialOpening;
  $('disconnect').disabled = !port || closing;
  $('flash-button').disabled = !!port || serialOpening;
  $('variant').disabled = !!port || serialOpening;
  for (const id of ['send', 'status', 'unpair', 'release']) $(id).disabled = !live;
  for (const button of document.querySelectorAll('[data-command]')) button.disabled = !(live && phoneReady);
  $('touchpad').dataset.ready = String(live && phoneReady);
  $('pad-note').textContent = live && phoneReady ? '点击或拖动以发送触摸' : '连接手机后启用';
}
function send(command, quiet = false) {
  if (!writer) return Promise.reject(new Error('请先连接控制台'));
  const destination = writer;
  const job = writeChain.then(() => destination.write(new TextEncoder().encode(command + '\n')));
  writeChain = job.catch(() => {});
  if (!quiet) log('→ ' + command);
  return job;
}
function request(command, quiet = false) {
  return send(command, quiet).catch((error) => log('发送失败：' + error.message));
}
function receive(line) {
  try {
    const data = JSON.parse(line);
    if (data.event === 'status') {
      $('device-name').textContent = data.name;
      $('report-count').textContent = data.reports;
      $('ble-state').textContent = data.ready ? '已就绪 · 可发送触摸' : data.connected ? '已连接 · 等待加密 / HID 订阅' : '未连接 · 请在手机配对';
      $('ble-state').style.color = data.ready ? '#087f79' : '#607285';
      phoneReady = data.ready;
      controls();
      return;
    }
    if (data.event === 'ok') return;
    if (data.event === 'error' && data.message === 'phone_not_ready') line = '手机 HID 通道尚未就绪，请先在系统蓝牙设置中配对。';
  } catch { /* Boot output is plain text. */ }
  log(line);
}
async function readLoop() {
  const decoder = new TextDecoder();
  let pending = '';
  try {
    while (true) {
      const {value, done} = await reader.read();
      if (done) break;
      pending += decoder.decode(value, {stream: true});
      const lines = pending.split('\n');
      pending = lines.pop().slice(-4096);
      for (const line of lines) if (line.trim()) receive(line.trim());
    }
  } catch (error) { if (!closing) log('串口已中断：' + error.message); }
  finally {
    reader?.releaseLock(); reader = null;
    if (!closing) setTimeout(() => disconnect(false), 0);
  }
}
async function connect() {
  serialOpening = true; controls();
  try {
    port = await navigator.serial.requestPort();
    await port.open({baudRate: 115200});
    await port.setSignals({dataTerminalReady: true, requestToSend: false});
    writer = port.writable.getWriter();
    reader = port.readable.getReader();
    closing = false;
    readTask = readLoop();
    $('serial-state').textContent = '已连接 · 115200';
    log('控制台已连接，请在手机蓝牙设置中配对 C3-Touch-xxxx。');
    await send('status', true);
  } catch (error) {
    log('连接未完成：' + error.message);
    if (writer || reader) await disconnect(false);
    else { try { await port?.close(); } catch {} port = null; }
  } finally { serialOpening = false; controls(); }
}
async function disconnect(release = true) {
  if (closing) return;
  closing = true; controls();
  if (release && writer) { try { await send('release', true); } catch {} }
  activePointer = null; $('touch-dot').hidden = true;
  try { await reader?.cancel(); } catch {}
  try { await readTask; } catch {}
  try { await writeChain; writer?.releaseLock(); } catch {}
  writer = null; readTask = null;
  try { await port?.close(); } catch {}
  port = null; closing = false; phoneReady = false;
  $('serial-state').textContent = '未连接';
  $('ble-state').textContent = '等待设备';
  controls();
}
$('connect').onclick = connect;
$('disconnect').onclick = () => disconnect();
$('variant').onchange = () => $('installer').setAttribute('manifest', `firmware/manifest-${$('variant').value}.json`);
$('clear-log').onclick = () => { logLines.length = 0; $('log').textContent = ''; };
$('copy-target').onclick = async () => {
  try { await navigator.clipboard.writeText(targetUrl); log('已复制手机靶页链接'); }
  catch { log('请手动复制靶页链接：' + targetUrl); }
};
for (const button of document.querySelectorAll('[data-command]')) button.onclick = () => request(button.dataset.command);
$('status').onclick = () => request('status');
$('release').onclick = () => { stopPointer(); request('release'); };
$('unpair').onclick = () => {
  if (confirm('清除 C3 保存的蓝牙配对？之后也请在手机中忘记此设备，再重新配对。')) request('unpair');
};
$('command-form').onsubmit = (event) => {
  event.preventDefault();
  const value = $('command').value.trim();
  if (value) request(value);
};
function point(event) {
  const rect = $('touchpad').getBoundingClientRect();
  return {x: Math.round(Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width)) * 1000),
    y: Math.round(Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height)) * 1000)};
}
function showPoint(p) {
  currentPoint = p;
  $('coordinates').textContent = `X ${p.x} · Y ${p.y}`;
  $('touch-dot').hidden = false;
  $('touch-dot').style.left = `${p.x / 10}%`;
  $('touch-dot').style.top = `${p.y / 10}%`;
}
$('touchpad').onpointerdown = (event) => {
  if (!phoneReady || !writer || activePointer !== null || event.button !== 0) return;
  event.preventDefault();
  activePointer = event.pointerId;
  $('touchpad').setPointerCapture(event.pointerId);
  showPoint(point(event));
  request(`down 0 ${currentPoint.x} ${currentPoint.y}`, true);
};
$('touchpad').onpointermove = (event) => {
  if (event.pointerId !== activePointer) return;
  showPoint(point(event));
  if (performance.now() - lastMove >= 25) {
    lastMove = performance.now();
    request(`move 0 ${currentPoint.x} ${currentPoint.y}`, true);
  }
};
function stopPointer() {
  if (activePointer === null) return;
  activePointer = null;
  $('touch-dot').hidden = true;
  if (writer) request('release', true);
}
$('touchpad').onpointerup = (event) => {
  if (event.pointerId !== activePointer) return;
  showPoint(point(event));
  request(`move 0 ${currentPoint.x} ${currentPoint.y}`, true);
  stopPointer();
};
$('touchpad').onpointercancel = stopPointer;
$('touchpad').onlostpointercapture = stopPointer;
setInterval(() => { if (activePointer !== null && writer && phoneReady) request(`move 0 ${currentPoint.x} ${currentPoint.y}`, true); }, 500);
window.addEventListener('blur', () => { stopPointer(); if (writer) request('release', true); });
document.addEventListener('visibilitychange', () => { if (document.hidden) { stopPointer(); if (writer) request('release', true); } });
window.addEventListener('pagehide', () => { if (writer) request('release', true); });
controls();
