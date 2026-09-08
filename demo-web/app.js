/* 课程表 Web 演示（与 Android App 同款交互的桌面/手机预览）。
 * 数据：内置示例 = data.js 的 window.SCHEDULE_JSON；也可导入同结构 JSON。
 * 纯浏览器本地运行，无需服务器（除浏览器文件访问限制时用 http 打开）。
 */
(function () {
  'use strict';

  // ---------- 基础工具 ----------
  var $ = function (id) { return document.getElementById(id); };
  function toast(msg) {
    var t = $('toast');
    t.textContent = msg; t.style.display = 'block';
    clearTimeout(toast._h); toast._h = setTimeout(function () { t.style.display = 'none'; }, 2600);
  }
  function parseISO(s) {
    var p = s.split('-');
    return new Date(+p[0], +p[1] - 1, +p[2]);
  }
  function epoch(d) { return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000); }
  function fromEpoch(n) {
    var d = new Date(Date.UTC(1970, 0, 1));
    d.setUTCDate(d.getUTCDate() + n);
    return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate());
  }
  function addDays(d, n) { return fromEpoch(epoch(d) + n); }
  function isoOf(d) {
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }
  function dowIdx(d) { return (d.getDay() + 6) % 7; }
  var WEEK = ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'];
  var WEEK_S = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function fmtMD(d) { return (d.getMonth() + 1) + '月' + d.getDate() + '日'; }
  function mondayOf(d) { return addDays(d, -dowIdx(d)); }

  var PAIR_START = ['8:20', '10:20', '13:20', '15:20', '18:00'];
  var PAIR_END = ['10:00', '12:00', '15:00', '17:00', '19:30'];

  function slotLabel(e) {
    var a = e.startSlot, b = e.startSlot + e.slotSpan - 1;
    var label = (e.slotSpan === 1) ? ('第' + a + '节') : ('第' + a + '-' + b + '节');
    var p1 = Math.floor((a - 1) / 2), p2 = Math.floor((b - 1) / 2);
    var range = '';
    if (p1 >= 0 && p1 < PAIR_START.length) {
      var p2e = Math.min(Math.max(p2, p1), PAIR_END.length - 1);
      range = PAIR_START[p1] + '-' + PAIR_END[p2e];
    }
    return { label: label, range: range };
  }
  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function displayName(e) {
    var n = String(e.name || '').trim();
    while (n.endsWith('-')) n = n.slice(0, -1).trim();
    return n;
  }

  // ---------- 周次解析（与 App 同规则） ----------
  function parseWeeks(raw) {
    var weeks = [], unparsed = false;
    var t = String(raw || '').trim();
    if (t.indexOf('【') === 0) t = t.slice(1, -1);
    t = t.replace(/周$/g, '');
    if (!t) return { weeks: weeks, unparsed: unparsed };
    t.split(/[,，;；、\s]+/).forEach(function (tok) {
      tok = tok.replace(/^第/, '');
      if (!tok) return;
      var m = tok.match(/^(\d+)\s*[-~至到]\s*(\d+)\s*(\(?单\)?|\(?双\)?)?$/);
      if (m) {
        var a = +m[1], b = +m[2];
        var mode = m[3] || '';
        var lo = Math.min(a, b), hi = Math.max(a, b);
        for (var w = lo; w <= hi; w++) {
          if (mode.indexOf('单') >= 0 && w % 2 === 1) weeks.push(w);
          else if (mode.indexOf('双') >= 0 && w % 2 === 0) weeks.push(w);
          else if (!mode) weeks.push(w);
        }
      } else if (/^\d+$/.test(tok)) {
        weeks.push(+tok);
      } else {
        unparsed = true;
      }
    });
    return { weeks: weeks, unparsed: unparsed };
  }

  // ---------- 状态 ----------
  var S = null;          // 规范课表对象
  var termStart = null;  // Date(第1周周一)
  var mode = 'week';     // week | day | month | ov
  var date = new Date(); // 当前选中日期
  var calendarMonth = null; // 月视图所在月(1日 Date)

  function normDoc(doc) {
    var term = doc.term || {};
    var wd = Array.isArray(doc.weekdayNames) ? doc.weekdayNames.slice() : WEEK.slice();
    while (wd.length < 7) wd.push(WEEK[wd.length]);
    var entries = (doc.entries || []).map(function (e) {
      var weeks = Array.isArray(e.weeks) ? e.weeks.filter(function (x) { return typeof x === 'number' && x > 0; }) : null;
      var unparsed = !!e.unparsedWeekTokens;
      if (weeks === null) {
        var r = parseWeeks(e.weeksText || '');
        weeks = r.weeks; unparsed = unparsed || r.unparsed;
      }
      var hasAnn = e.hasWeekAnnotation !== undefined ? !!e.hasWeekAnnotation : !!(e.weeksText);
      return {
        day: e.day === undefined ? 0 : e.day,
        name: String(e.name || ''),
        teacher: String(e.teacher || ''),
        weeksText: String(e.weeksText || ''),
        weeks: weeks,
        hasWeekAnnotation: hasAnn,
        unparsedWeekTokens: unparsed,
        room: String(e.room || ''),
        startSlot: e.startSlot || 1,
        slotSpan: e.slotSpan || 1,
        raw: String(e.raw || '')
      };
    });
    return {
      weekdayNames: wd,
      entries: entries,
      week1Monday: String((term.week1Monday) || doc.week1Monday || '2026-08-31'),
      weekCount: +((term.weekCount) || doc.weekCount || 18),
      titleLines: (term.titleLines || doc.titleLines || []),
      comment: String(term.comment || doc.comment || '')
    };
  }
  function applySchedule(s) {
    S = s;
    termStart = parseISO(s.week1Monday);
    rebuildPalette();
    var tl = s.titleLines[0] || '';
    var info = s.titleLines.find(function (x) { return x.indexOf('年级') >= 0 || x.indexOf('院系') >= 0; }) || '';
    $('termLine').textContent = (tl ? tl + (info ? ' ｜ ' + info : '') : '课程表');
    $('aboutBody').innerHTML = '<p class="legend" style="text-align:left">' + esc(
      '节次以每两节为一节课(保留节次序号)。\n' +
      (s.comment || '1-2节 8:20-10:00\n3-4节 10:20-12:00\n5-6节 13:20-15:00\n7-8节 15:20-17:00\n9-10节 18:00-19:30')
    ).replace(/\n/g, '<br>') + '</p>';
  }

  // ---------- 配色：一门课一种互不重复的颜色（黄金角推进色相，与 Android 端一致） ----------
  var COURSE_KEYS = [];
  function rebuildPalette() {
    var seen = {};
    if (S) S.entries.forEach(function (e) { seen[keyOf(e)] = 1; });
    COURSE_KEYS = Object.keys(seen).sort();
  }
  function hashKey(s) {
    var h = 0;
    for (var i = 0; i < s.length; i++) { h = (h * 31 + s.charCodeAt(i)) | 0; }
    return Math.abs(h);
  }
  function courseIndex(key) {
    var i = COURSE_KEYS.indexOf(key);
    return i >= 0 ? i : hashKey(key) % 360;
  }
  function hslOf(i, sat, light) {
    var hue = ((i * 137.508) % 360 + 360) % 360;
    return 'hsl(' + Math.round(hue) + ', ' + sat + '%, ' + light + '%)';
  }
  function colorFor(e) {
    var i = courseIndex(keyOf(e));
    return { main: hslOf(i, 60, 46), bg: hslOf(i, 22, 97) };
  }
  function keyOf(e) { return e.name.trim() + '@' + e.teacher.trim(); }

  function activeOnWeek(e, w) {
    if (!e.hasWeekAnnotation) return true;
    if (e.unparsedWeekTokens && (!e.weeks || !e.weeks.length)) return true;
    return e.weeks.indexOf(w) >= 0;
  }
  function entriesOn(week, dayIdx) {
    return S.entries.filter(function (e) { return e.day === dayIdx && activeOnWeek(e, week); })
      .sort(function (a, b) { return a.startSlot - b.startSlot || a.slotSpan - b.slotSpan; });
  }
  function weekOf(d) {
    var diff = Math.round((epoch(d) - epoch(termStart)) / 7);
    var w = diff + 1;
    return (w >= 1 && w <= S.weekCount) ? w : null;
  }
  function mondayOfWeek(w) { return addDays(termStart, (w - 1) * 7); }

  // ---------- 渲染入口 ----------
  function go() {
    var main = $('main');
    if (!S) {
      main.innerHTML = '<div class="empty">尚未载入课表…</div>';
      return;
    }
    $('titleLbl').textContent = titleForMode();
    if (mode === 'week') renderWeek(main);
    else if (mode === 'day') renderDay(main);
    else if (mode === 'month') renderMonth(main);
    else renderOverview(main);
    renderFooter();
  }
  function titleForMode() {
    if (mode === 'week') {
      var w0 = weekOf(date);
      if (!w0) w0 = (epoch(date) < epoch(termStart)) ? 1 : S.weekCount;
      var m0 = mondayOfWeek(w0);
      return '第' + w0 + '周  ' + fmtMD(m0) + '-' + fmtMD(addDays(m0, 6));
    }
    if (mode === 'day') return date.getFullYear() + '年' + fmtMD(date);
    if (mode === 'month') return date.getFullYear() + '年' + (date.getMonth() + 1) + '月';
    return '全部课程';
  }

  function weekDays() {
    var hasWeekend = S.entries.some(function (e) { return e.day >= 5; });
    var arr = [];
    for (var d = 0; d < 7; d++) if (hasWeekend || d < 5) arr.push(d);
    return arr;
  }

  function weekRangeHTML(dayIdxs) {
    var w0 = weekOf(date);
    if (!w0) w0 = (epoch(date) < epoch(termStart)) ? 1 : S.weekCount;
    var m0 = mondayOfWeek(w0);
    var html = '';
    for (var i = 0; i < dayIdxs.length; i++) {
      var d = addDays(m0, dayIdxs[i]);
      var isToday = epoch(d) === epoch(new Date());
      var isSel = epoch(d) === epoch(date);
      html += '<div class="hcell ' + (isToday ? 'today' : '') + (isSel ? ' sel' : '') +
        '" data-iso="' + isoOf(d) + '">' +
        '<span class="d">' + WEEK_S[dayIdxs[i]] + '</span><div class="dd">' + fmtMD(d) + '</div></div>';
    }
    return html;
  }

  function renderWeek(main) {
    var dayIdxs = weekDays();
    var w0 = weekOf(date);
    if (!w0) w0 = (epoch(date) < epoch(termStart)) ? 1 : S.weekCount;
    var entries = S.entries.filter(function (e) { return activeOnWeek(e, w0); });
    var byDay = {};
    entries.forEach(function (e) { (byDay[e.day] = byDay[e.day] || []).push(e); });
    Object.keys(byDay).forEach(function (k) {
      byDay[k].sort(function (a, b) { return a.startSlot - b.startSlot || a.slotSpan - b.slotSpan; });
    });

    // 周课表已取消节次·时间栏：整行宽度只放天列
    var phoneW = Math.min(460, (window.innerWidth || 430) - 8);
    var colW = Math.max(62, Math.floor((phoneW - 12) / dayIdxs.length));
    var colsCss = 'grid-template-columns:' + Array(dayIdxs.length).fill(colW + 'px').join(' ');

    var head = weekRangeHTML(dayIdxs);
    var body = '';
    for (var lane = 1; lane <= 10; lane++) {
      for (var di = 0; di < dayIdxs.length; di++) {
        var d = dayIdxs[di];
        var cards = '';
        var list = byDay[d] || [];
        // 同一格多课并列时横向切分（轨道贪心）
        var tracks = [];
        var assign = {};
        list.forEach(function (e) {
          var t = -1;
          for (var ti = 0; ti < tracks.length; ti++) {
            var last = 0;
            tracks[ti].forEach(function (x) { last = Math.max(last, x.startSlot + x.slotSpan - 1); });
            if (last < e.startSlot) { t = ti; break; }
          }
          if (t < 0) { t = tracks.length; tracks.push([]); }
          tracks[t].push(e);
          assign[e] = t;
        });
        list.forEach(function (e) {
          if (e.startSlot > 10 || e.startSlot !== lane) return;
          var top = 2;                                  // 卡片起点 = 所在节次行的顶部
          var h = e.slotSpan * 52 - 4;                  // 高度随占用小节数跨行
          var tr = assign[e], tc = tracks.length;
          var lf = tr * (100 / tc) + 0.5, wd = (100 / tc) - 1;
          var col = colorFor(e);
          var meta = [e.teacher, e.room].filter(function (x) { return x; }).join('<br>');
          var m0 = mondayOfWeek(w0);
          cards += '<div class="card" style="top:' + top + 'px;height:' + h + 'px;left:' + lf + '%;width:' + wd +
            '%;background:' + col.bg + ';border-left-color:' + col.main + ';" ' +
            'data-iso="' + isoOf(addDays(m0, d)) + '">' +
            '<div class="n">' + esc(displayName(e)) + '</div>' +
            (meta ? '<div class="m">' + esc(meta) + '</div>' : '') +
            '</div>';
        });
        body += '<div class="cell' + (lane % 2 === 0 ? ' alt' : '') + '">' + cards + '</div>';
      }
    }
    main.innerHTML = '<div class="wg"><div class="grid" style="' + colsCss + '">' + head + body + '</div></div>';
  }

  function renderDay(main) {
    var w0 = weekOf(date);
    var dIdx = dowIdx(date);
    var list = w0 ? entriesOn(w0, dIdx) : [];
    var out = '';
    if (w0 === null) {
      out = '<div class="empty">假期 · 非本学期<br><button class="btn sec" style="max-width:220px;margin:12px auto" onclick="window.DEMO_TO_TERM()">回到本学期</button></div>';
    } else if (!list.length) {
      out = '<div class="empty">今天没有课程，好好休息吧</div>';
    } else {
      list.forEach(function (e) {
        var c = colorFor(e);
        var sl = slotLabel(e);
        var meta = [];
        if (e.weeksText) meta.push(e.weeksText);
        if (e.teacher) meta.push('教师：' + e.teacher);
        if (e.room) meta.push('教室：' + e.room);
        var rawExtra = (!e.weeksText && !e.teacher) ? e.raw : '';
        out += '<div class="daycard" style="border-left-color:' + c.main + '">' +
          '<div class="bd"><span class="time">' + esc(sl.label + (sl.range ? '  ' + sl.range : '')) + '</span>' +
          '<div class="n">' + esc(displayName(e)) + '</div>' +
          '<div class="m">' + esc(meta.join(' ｜ ')) + (rawExtra ? esc(' ｜ ' + rawExtra) : '') + '</div></div></div>';
      });
    }
    main.innerHTML = '<div class="daylist">' + out + '</div>';
  }

  function renderMonth(main) {
    var base = calendarMonth || new Date(date.getFullYear(), date.getMonth(), 1);
    var first = new Date(base.getFullYear(), base.getMonth(), 1);
    var dim = new Date(base.getFullYear(), base.getMonth() + 1, 0).getDate();
    var off = dowIdx(first);
    var dow = '<div class="dow">' + WEEK_S.map(function (x) { return '<span>' + x + '</span>'; }).join('') + '</div>';
    var cells = '';
    for (var i = 0; i < off; i++) cells += '<div class="day out"></div>';
    var today = new Date();
    for (var d = 1; d <= dim; d++) {
      var dt = new Date(base.getFullYear(), base.getMonth(), d);
      var w = weekOf(dt);
      var keys = [];
      if (w !== null) {
        var dl = entriesOn(w, dowIdx(dt));
        var seen = {};
        dl.forEach(function (e) { seen[keyOf(e)] = 1; });
        keys = Object.keys(seen);
      }
      var isT = epoch(dt) === epoch(today);
      var isSel = epoch(dt) === epoch(date);
      var dots = '';
      var n = Math.min(keys.length, 4);
      for (var k = 0; k < n; k++) {
        var e0 = S.entries.find(function (x) { return keyOf(x) === keys[k]; });
        dots += '<i style="background:' + (e0 ? colorFor(e0).main : '#ccc') + '"></i>';
      }
      if (keys.length > 4) dots += '<span class="more">+' + (keys.length - 4) + '</span>';
      cells += '<div class="day ' + (isT ? 'today' : '') + (isSel ? 'sel' : '') +
        '" data-iso="' + isoOf(dt) + '">' +
        (w !== null ? '<span class="wtag">W' + w + '</span>' : '') +
        '<span class="num">' + d + '</span><span class="dots">' + dots + '</span></div>';
    }
    var fill = (6 * 7) - (off + dim);
    for (var j = 0; j < fill; j++) cells += '<div class="day out"></div>';
    var wStart = weekOf(termStart);
    main.innerHTML = '<div class="monthwrap"><div class="mc">' + dow + '<div class="grid">' + cells + '</div></div>' +
      '<div class="legend">' + esc(S.titleLines[0] || '') + '<br>学期 ' + S.week1Monday + ' 起 · 共' + S.weekCount +
      '周（圆点 = 当天有课；W# = 第几周；点击日期查看当天）</div></div>';
  }

  function renderOverview(main) {
    var groups = {};
    S.entries.forEach(function (e) {
      var k = keyOf(e);
      (groups[k] = groups[k] || []).push(e);
    });
    var keys = Object.keys(groups).sort();
    var html = '<div class="ov"><div class="head"><h3>全部课程（' + keys.length + ' 门 · ' + S.entries.length + ' 次排课）</h3>' +
      '<p>' + esc(S.titleLines.join(' ｜ ')) + '</p></div>';
    keys.forEach(function (k) {
      var list = groups[k];
      var first = list[0];
      var teachers = [], rooms = [];
      list.forEach(function (e) {
        if (e.teacher && teachers.indexOf(e.teacher) < 0) teachers.push(e.teacher);
        if (e.room && rooms.indexOf(e.room) < 0) rooms.push(e.room);
      });
      var meta = [];
      if (teachers.length) meta.push('教师：' + teachers.join('、'));
      if (rooms.length) meta.push('教室：' + rooms.join('、'));
      var c = colorFor(first);
      html += '<div class="grp"><span class="dot" style="background:' + c.main + '"></span>' +
        '<span class="n">' + esc(displayName(first)) + '</span><span class="c">' + list.length + ' 次排课</span></div>' +
        '<div class="legend" style="text-align:left;padding:0 2px 2px 18px">' + esc(meta.join('　')) + '</div>';
      list.sort(function (a, b) { return a.day - b.day || a.startSlot - b.startSlot; });
      list.forEach(function (e) {
        var sl = slotLabel(e);
        var when = WEEK_SHORT_NAME(e.day) + ' ' + sl.label + (sl.range ? ' ' + sl.range : '');
        var m2 = [];
        if (e.weeksText) m2.push(e.weeksText);
        if (e.room) m2.push(e.room);
        if (!e.weeksText && !e.teacher && !e.room) m2.push(e.raw.replace(/\n/g, ' '));
        html += '<div class="occ"><div class="w">' + esc(when) + '</div>' +
          '<div class="m">' + esc(m2.join(' · ')) + '</div></div>';
      });
    });
    main.innerHTML = html + '</div>';
  }
  function WEEK_SHORT_NAME(d) { return WEEK_S[d]; }

  function renderFooter() {
    var modes = [['week', '周课表', '▦'], ['day', '日程', '☷'], ['month', '月视图', '▤'], ['ov', '全部课程', '☰']];
    var html = '';
    modes.forEach(function (m) {
      html += '<button class="' + (mode === m[0] ? 'on' : '') + '" data-mode="' + m[0] + '">' +
        '<span class="ic">' + m[2] + '</span>' + m[1] + '</button>';
    });
    $('footNav').innerHTML = html;
  }

  // ---------- 导航 ----------
  function navPrev() {
    if (mode === 'week') date = addDays(date, -7);
    else if (mode === 'day') date = addDays(date, -1);
    else if (mode === 'month') { calendarMonth = new Date(date.getFullYear(), date.getMonth() - 1, 1); date = calendarMonth; }
    else return;
    go();
  }
  function navNext() {
    if (mode === 'week') date = addDays(date, 7);
    else if (mode === 'day') date = addDays(date, 1);
    else if (mode === 'month') { calendarMonth = new Date(date.getFullYear(), date.getMonth() + 1, 1); date = calendarMonth; }
    else return;
    go();
  }
  function toMode(m) {
    mode = m;
    if (m === 'month' && !calendarMonth) calendarMonth = new Date(date.getFullYear(), date.getMonth(), 1);
    go();
  }

  // ---------- 事件委托 ----------
  document.addEventListener('click', function (ev) {
    var t = ev.target;
    while (t && t !== document && t.nodeType === 1) {
      if (t.getAttribute('data-mode')) { toMode(t.getAttribute('data-mode')); return; }
      if (t.hasAttribute('data-iso')) {
        var picked = parseISO(t.getAttribute('data-iso'));
        date = picked;
        if (t.closest && t.closest('.hcell')) { go(); }      // 周表头：仅选中该日（留在周视图）
        else if (t.closest && t.closest('.card')) { mode = 'day'; go(); } // 课程卡：打开当天
        else { mode = 'day'; go(); }                          // 月历格等
        return;
      }
      t = t.parentNode;
    }
  });

  // ---------- 面板 ----------
  function openSheet(id) { $('mask' + cap(id)).classList.add('on'); }
  function hideSheet() {
    ['Menu', 'Notify', 'Import', 'About'].forEach(function (k) { $('mask' + k).classList.remove('on'); });
  }
  function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }
  window.openSheet = openSheet;
  window.hideSheet = hideSheet;
  window.toMode = toMode;
  window.navPrev = navPrev;
  window.navNext = navNext;
  window.DEMO_TO_TERM = function () { date = new Date(); go(); };

  document.querySelectorAll('.mask').forEach(function (m) {
    m.addEventListener('click', function (ev) {
      if (ev.target === m) hideSheet();
    });
  });

  // ---------- 通知演示 ----------
  var prefs = { enabled: false, time: '07:30' };
  try { var saved = JSON.parse(localStorage.getItem('ct_demo') || 'null'); if (saved) prefs = saved; } catch (e) { }
  function savePrefs() { try { localStorage.setItem('ct_demo', JSON.stringify(prefs)); } catch (e) { } }
  $('swNotify').checked = prefs.enabled;
  $('notifyTime').value = prefs.time;
  $('swNotify').addEventListener('change', function () {
    prefs.enabled = this.checked;
    savePrefs(); updateNotifyPreview();
    if (prefs.enabled) {
      if (typeof Notification !== 'undefined' && Notification.permission === 'default') Notification.requestPermission();
      toast(prefs.enabled ? '已开启每日课程通知' : '已关闭');
    }
  });
  $('notifyTime').addEventListener('change', function () { prefs.time = this.value; savePrefs(); });
  $('btnTestNotify').addEventListener('click', function () {
    var text = todaySummary();
    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      new Notification('今日课程 · ' + WEEK_S[dowIdx(new Date())] + fmtMD(new Date()) + (weekOf(new Date()) ? ' 第' + weekOf(new Date()) + '周' : ''), { body: text || '今天没有安排课程，好好休息吧' });
    } else {
      $('banner').style.display = 'block';
      $('banner').textContent = '【每日课程通知·' + prefs.time + '】' + (text || '今天没有安排课程，好好休息吧');
      setTimeout(function () { $('banner').style.display = 'none'; }, 12000);
    }
    toast('已发送今天课程演示通知');
  });

  function todaySummary() {
    if (!S) return '';
    var d = new Date();
    var w = weekOf(d);
    var list = w ? entriesOn(w, dowIdx(d)) : [];
    if (!list.length) return '';
    return list.map(function (e) {
      var sl = slotLabel(e);
      var meta = [e.teacher, e.room].filter(function (x) { return x; }).join(' · ');
      return '[' + sl.label + ' ' + sl.range + '] ' + displayName(e) + (meta ? '\n' + meta : '');
    }).join('\n');
  }
  function updateNotifyPreview() {
    var t = todaySummary();
    $('notifyPreview').textContent = prefs.enabled ? ('明日 ' + prefs.time + ' 发送：' + (t || '今日无课')) : '未开启';
  }
  function checkFire() {
    if (prefs.enabled && S) {
      var now = new Date();
      var hm = now.getHours() * 60 + now.getMinutes();
      var p = prefs.time.split(':');
      var target = (+p[0]) * 60 + (+p[1]);
      if (hm === target || (hm > target - 1 && hm <= target)) {
        // 当天首次触达演示
        if (checkFire._last !== now.toDateString()) {
          checkFire._last = now.toDateString();
          var text = todaySummary();
          $('banner').style.display = 'block';
          $('banner').textContent = '【每日课程通知 ' + prefs.time + '】' + (text || '今天没有安排课程，好好休息吧');
          setTimeout(function () { $('banner').style.display = 'none'; }, 15000);
        }
      }
    }
    setTimeout(checkFire, 20000);
  }

  // ---------- 导入 ----------
  $('importFile').addEventListener('change', function (ev) {
    var f = ev.target.files[0];
    if (!f) return;
    var reader = new FileReader();
    reader.onload = function () {
      try {
        var doc = JSON.parse(reader.result);
        var s = normDoc(doc);
        if (!s.entries.length) throw new Error('entries 为空');
        applySchedule(s); hideSheet(); go();
        toast('导入成功：' + s.entries.length + ' 条排课');
      } catch (err) {
        toast('导入失败：' + err.message);
      }
    };
    reader.readAsText(f, 'utf-8');
  });
  function loadSample() {
    applySchedule(normDoc(window.SCHEDULE_JSON || {}));
    hideSheet(); go();
    toast('已载入内置示例课表');
  }
  window.loadSample = loadSample;

  // ---------- 节次·时间小浮层 ----------
  window.openTimeHint = function () {
    var p = $('timeHint');
    if (p.style.display === 'block') { p.style.display = 'none'; return; }
    var rows = $('timeHintRows');
    rows.innerHTML = '';
    for (var i = 0; i < PAIR_START.length; i++) {
      var a = i * 2 + 1;
      rows.innerHTML += '<div class="thr"><span>第' + a + '-' + (a + 1) + '节</span><b>' +
        PAIR_START[i] + ' - ' + PAIR_END[i] + '</b></div>';
    }
    p.style.display = 'block';
  };
  document.addEventListener('click', function (ev) {
    var p = $('timeHint');
    if (p.style.display === 'block' && ev.target && ev.target.closest &&
      !ev.target.closest('#timeHint') && !ev.target.closest('.railhint')) {
      p.style.display = 'none';
    }
  });

  // ---------- 启动 ----------
  function boot() {
    var src = window.SCHEDULE_JSON;
    if (src && src.entries) {
      applySchedule(normDoc(src));
    } else {
      $('main').innerHTML = '<div class="empty">数据未载入：请通过本地 http 服务打开（python -m http.server 8000 后访问 demo-web/），或点击下方载入示例。</div>';
    }
    updateNotifyPreview();
    checkFire();
    go();
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
