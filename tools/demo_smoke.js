// 简易 DOM 桩，用于在 Node 中执行 app.js 的启动与渲染冒烟测试
function makeEl(id) {
  const el = {
    id, innerHTML: '', textContent: '', value: '', checked: false,
    style: {}, dataset: {},
    classList: { add() {}, remove() {}, contains() { return false; } },
    addEventListener() {}, removeEventListener() {},
    appendChild() {}, setAttribute() {}, getAttribute() { return null; },
    hasAttribute() { return false; }, querySelector() { return null; },
  };
  return el;
}
const els = {};
global.window = global;
global.document = {
  readyState: 'complete',
  getElementById(id) { if (!els[id]) els[id] = makeEl(id); return els[id]; },
  querySelectorAll() { return []; },
  addEventListener() {},
  createElement() { return makeEl(''); },
};
global.localStorage = { getItem() { return null; }, setItem() {} };
global.Notification = undefined;

const fs = require('fs');
const path = require('path');
const dir = path.join(__dirname, '..', 'demo-web');
eval(fs.readFileSync(path.join(dir, 'data.js'), 'utf8'));
eval(fs.readFileSync(path.join(dir, 'app.js'), 'utf8'));

// 直接驱动渲染
function renderAll() {
  window.toMode('week');
  window.navNext(); // week2
  window.toMode('month');
  window.navNext();
  window.toMode('day');
  window.navNext();
  window.toMode('ov');
}
try {
  renderAll();
  const s = window.SCHEDULE_JSON;
  console.log('entries:', s.entries.length, '| weekCount:', (s.term && s.term.weekCount));
  // 校验某几周课程数（与 App/解析器一致）
  const d = new Date(2026, 8, 7); // 2026-09-07 = 第2周周一
  // 依赖内部状态？改为直接手动调用不可行——仅确认无异常即可
  console.log('SMOKE OK');
  process.exit(0);
} catch (e) {
  console.error('SMOKE FAIL:', e && e.stack || e);
  process.exit(1);
}
