const $ = (id) => document.getElementById(id);
const surface = $('surface'), canvas = $('traces'), ctx = canvas.getContext('2d');
const contacts = new Map(), lines = [];
const colors = ['#65e2c4', '#ffcc83', '#a5a3ff', '#fa8fa8'];
let maxContacts = 0, starts = 0, nextColor = 0;
function resize() {
  const r = surface.getBoundingClientRect(), scale = devicePixelRatio || 1;
  canvas.width = Math.round(r.width * scale); canvas.height = Math.round(r.height * scale);
  ctx.setTransform(scale, 0, 0, scale, 0, 0);
}
new ResizeObserver(resize).observe(surface);
function log(line) {
  lines.push(line); if (lines.length > 25) lines.shift();
  $('event-log').textContent = lines.join('\n');
  $('event-log').scrollTop = $('event-log').scrollHeight;
}
function local(event) {
  const r = surface.getBoundingClientRect();
  return {x: event.clientX - r.left, y: event.clientY - r.top};
}
function render() {
  maxContacts = Math.max(maxContacts, contacts.size);
  $('contacts').textContent = `${contacts.size} / ${maxContacts}`;
  $('touch-count').textContent = starts;
  $('contact-info').textContent = [...contacts].map(([id, p]) => `ID ${id}: (${Math.round(p.x)}, ${Math.round(p.y)})`).join('  ·  ') || '全部触点已抬起';
}
surface.onpointerdown = (event) => {
  event.preventDefault(); surface.setPointerCapture(event.pointerId);
  const p = {...local(event), color: colors[nextColor++ % colors.length]};
  contacts.set(event.pointerId, p); ++starts;
  $('input-type').textContent = event.pointerType;
  $('input-type').className = `type-${event.pointerType}`;
  ctx.beginPath(); ctx.arc(p.x, p.y, 10, 0, Math.PI * 2); ctx.strokeStyle = p.color; ctx.lineWidth = 2; ctx.stroke();
  log(`DOWN ${event.pointerType} #${event.pointerId} (${Math.round(event.clientX)}, ${Math.round(event.clientY)})`);
  render();
};
surface.onpointermove = (event) => {
  const previous = contacts.get(event.pointerId); if (!previous) return;
  event.preventDefault(); const p = {...local(event), color: previous.color};
  ctx.beginPath(); ctx.moveTo(previous.x, previous.y); ctx.lineTo(p.x, p.y); ctx.strokeStyle = p.color; ctx.lineWidth = 3; ctx.stroke();
  contacts.set(event.pointerId, p); render();
};
function lift(event) {
  if (!contacts.has(event.pointerId)) return;
  contacts.delete(event.pointerId);
  log(`${event.type === 'pointercancel' ? 'CANCEL' : 'UP'} ${event.pointerType} #${event.pointerId}`); render();
}
surface.onpointerup = lift; surface.onpointercancel = lift; surface.onlostpointercapture = lift;
$('clear').onclick = () => { ctx.clearRect(0, 0, canvas.width, canvas.height); maxContacts = contacts.size; starts = 0; lines.length = 0; $('event-log').textContent = ''; render(); };
$('fullscreen').onclick = async () => {
  try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen(); }
  catch (error) { log('全屏未开启：' + error.message); }
};
document.addEventListener('fullscreenchange', () => { document.body.classList.toggle('fullscreen', !!document.fullscreenElement); $('fullscreen').textContent = document.fullscreenElement ? '退出全屏' : '全屏测试'; });
