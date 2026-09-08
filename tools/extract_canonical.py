#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Build canonical schedule JSON from the BIFF8 dump of 课表.xls.
Also emits a content-equivalent .xlsx twin (inline strings) to exercise the
xlsx import path of the app, and prints a verification summary.
"""
import sys, os, json, io, re, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

from read_xls import Ole2, XlsBook

SRC = os.path.join(ROOT, '课表.xls')
DUMP = os.path.join(ROOT, '_canon_dump.txt')

# ---------- 1. raw grid from the real file ----------
data = open(SRC, 'rb').read()
ole = Ole2(data)
book = XlsBook(ole.stream('Workbook'))
sheet = book.sheets[0]
cells = sheet['cells']          # (r,c)->(type,value)
merged = sheet['merged']

def cell(r, c):
    v = cells.get((r, c))
    if v is None: return ''
    typ, val = v
    if typ == 'n':
        return str(int(val)) if float(val).is_integer() else repr(val)
    return str(val)

def col_letters(n):
    s = ''
    while True:
        n, r = divmod(n, 26)
        s = chr(65 + r) + s
        if n == 0: return s

# ---------- 2. geometry discovery ----------
# Header line: contains 节次 then 星期 headers horizontally at row rH.
max_row = max(r for r, c in cells) 
max_col = max(c for r, c in cells)
# find header row: row with a cell exactly '节次' and weekday names to the right
def is_wd(s):
    s = (s or '').strip()
    return re.fullmatch(r'星期[一二三四五六日天]|周[一二三四五六日天]|[一二三四五六日天]', s) is not None

head_row = None
for r in range(max_row + 1):
    for c in range(max_col + 1):
        if (cell(r, c) or '').strip() == '节次':
            wds = [cell(r, cc).strip() for cc in range(c + 1, max_col + 1)]
            if any(is_wd(w) for w in wds):
                head_row = r
                break
    if head_row is not None:
        break
assert head_row is not None, 'header row (节次) not found'

slot_col = next(c for c in range(max_col + 1) if (cell(head_row, c) or '').strip() == '节次')
day_cols = []   # (colIndex, weekdayIndex 0..6, name)
for c in range(slot_col + 1, max_col + 1):
    name = cell(head_row, c).strip()
    if is_wd(name):
        m = re.search(r'[一二三四五六日天]', name)
        wd = '一二三四五六日'.index(m.group(0)) if m.group(0) != '天' else 6
        day_cols.append((c, wd, name))
assert day_cols, 'no weekday columns'
day_cols.sort(key=lambda t: t[1])

# slot rows: rows below header whose first column is integer 1..N
slot_rows = []
for r in range(head_row + 1, max_row + 1):
    v = cell(r, slot_col).strip()
    if v.isdigit() and 1 <= int(v) <= 40:
        slot_rows.append((r, int(v)))
slot_rows.sort(key=lambda t: t[1])
assert slot_rows, 'no slot rows'
# continuity assumption: slot n located at its own row (r3..r15 were slots 1..12)
slot_of_row = {r: n for r, n in slot_rows}

# info / title lines above header
title_cells = []
for r in range(0, head_row):
    vals = []
    for c in range(max_col + 1):
        v = cell(r, c).strip()
        if v:
            vals.append(v)
    if vals:
        title_cells.append(' | '.join(vals))

# ---------- 3. cell texts per (day col, slot) ----------
grid_text = {}   # (dayIdx, slot) -> raw text with newlines
for (c, d, name) in day_cols:
    for (r, n) in slot_rows:
        grid_text[(d, n)] = cell(r, c).rstrip()

# ---------- 4. split cell into course blocks ----------
def split_blocks(text):
    """text lines -> list of blocks; blank line separates blocks; also treat a
    name-like line that is immediately followed by teacher/room etc."""
    lines = [ln for ln in text.split('\n')]
    blocks, cur = [], []
    for ln in lines:
        if ln.strip() == '':
            if cur: blocks.append(cur); cur = []
        else:
            cur.append(ln)
    if cur: blocks.append(cur)
    return blocks

WEEK_RE = re.compile(r'【([^】]*?)(周)】')

def parse_weeks(raw):
    """raw like '1-9,11-16,18' | '2-8(双),12-16(双)' | '1-15(单)' | '17' -> sorted set"""
    out = set()
    for tok in raw.split(','):
        tok = tok.strip()
        if not tok: continue
        m = re.match(r'^(\d+)\s*[-~至]\s*(\d+)\s*(\(?[单双]?\)?)?$', tok)
        if m:
            a, b = int(m.group(1)), int(m.group(2))
            par = m.group(3) or ''
            if '单' in par:
                out.update(w for w in range(a, b + 1) if w % 2 == 1)
            elif '双' in par:
                out.update(w for w in range(a, b + 1) if w % 2 == 0)
            else:
                out.update(range(a, b + 1))
        else:
            m2 = re.match(r'^(\d+)$', tok)
            if m2:
                out.add(int(m2.group(1)))
            else:
                out.add(tok)   # unparsable token kept (string) -- flagged
    return out

def parse_block(blk):
    """blk: list of lines -> dict(name, teacher, weeksRaw, weeks(parsed or None), room, rawLines)"""
    lines = [ln.strip() for ln in blk if ln.strip()]
    name = lines[0]
    teacher = ''
    weeksRaw = ''
    room = ''
    week_line_idx = None
    for i, ln in enumerate(lines[1:], start=1):
        m = WEEK_RE.search(ln)
        if m and week_line_idx is None:
            week_line_idx = i
            teacher = ln[:m.start()].strip()
            weeksRaw = m.group(1)
    # room: last line that is not the name and not the weeks/teacher line, nonempty
    others = [ln for i, ln in enumerate(lines) if i != 0 and i != week_line_idx and ln]
    if others:
        room = others[-1]
    raw = '\n'.join(lines)
    return {
        'name': name,
        'teacher': teacher,
        'weeksText': ('【' + weeksRaw + '周】') if weeksRaw else '',
        'room': room,
        'raw': raw,
        '_weeks': parse_weeks(weeksRaw) if weeksRaw else None,
    }

# ---------- 5. per (day) courses with vertical contiguous spans ----------
# key = canonical block signature (name|teacher|weeksRaw|room) but merged vertically
# only if every intermediate row contains exactly same signature presence.
# Presence approach: for each day and each block instance signature s, rows where s occurs.
occurrences = []  # (dayIdx, sig, [rows...])
for (d, n) in sorted(grid_text):
    pass

# collect presence rows per signature per day
presence = {}  # (dayIdx, sigKey) -> list of slots where present
sig_of = {}    # sigKey -> parsed block dict
for (d, n), text in grid_text.items():
    for blk in split_blocks(text):
        pb = parse_block(blk)
        sig = json.dumps([pb['name'], pb['teacher'], pb['weeksText'], pb['room']], ensure_ascii=False)
        presence.setdefault((d, sig), []).append(n)
        sig_of[sig] = pb

# contiguous slot runs per signature
entries = []
for (d, sig), slots in sorted(presence.items()):
    slots = sorted(set(slots))
    if not slots: continue
    runs = []
    start = prev = slots[0]
    for n in slots[1:]:
        if n == prev + 1:
            prev = n
        else:
            runs.append((start, prev)); start = prev = n
    runs.append((start, prev))
    pb = sig_of[sig]
    for (a, b) in runs:
        entries.append({
            'day': d,
            'name': pb['name'],
            'teacher': pb['teacher'],
            'weeksText': pb['weeksText'],
            'weeks': sorted(pb['_weeks']) if isinstance(pb['_weeks'], set) else None,
            'weeksUnparsed': None if isinstance(pb['_weeks'], set) else pb['_weeks'],
            'room': pb['room'],
            'startSlot': a,
            'slotSpan': b - a + 1,
            'raw': pb['raw'],
        })

entries.sort(key=lambda e: (e['day'], e['startSlot'], e['slotSpan'], e['name']))

# ---------- 6. assemble canonical document ----------
week1 = '2026-08-31'
weekCount = max(max(e['weeks']) if isinstance(e['weeks'], list) else 0 for e in entries)
timePairs = [  # (slots pair label, 12h original text, 24h range)
    ('1-2节', '8:20-10:00', '8:20-10:00'),
    ('3-4节', '10:20-12:00', '10:20-12:00'),
    ('5-6节', '1:20-3:00', '13:20-15:00'),
    ('7-8节', '3:20-5:00', '15:20-17:00'),
    ('9-10节', '6:00-7:30', '18:00-19:30'),
]
doc = {
    'formatVersion': 1,
    'sourceFile': os.path.basename(SRC),
    'term': {
        'titleLines': title_cells,
        'week1Monday': week1,
        'weekCount': weekCount,
        'comment': '节次以每两节为一节课(保留节次序号)。12节次课 8:20-10:00; 34节次课 10:20-12:00; '
                   '56节次课 13:20-15:00; 78节次课 15:20-17:00; 910节次课 18:00-19:30。',
    },
    'weekdayNames': ['星期一', '星期二', '星期三', '星期四', '星期五', '星期六', '星期日'],
    'timeSlots': [{'slot': n, 'label': '第%d节' % n} for n in range(1, 13)],
    'timePairs': [{'label': p[0], 'time12h': p[1], 'time24h': p[2]} for p in timePairs],
    'entries': [],
}
for e in entries:
    doc['entries'].append({k: e[k] for k in
                           ('day', 'name', 'teacher', 'weeksText', 'weeks', 'weeksUnparsed',
                            'room', 'startSlot', 'slotSpan', 'raw')})

out_path = os.path.join(ROOT, 'samples', '课表-王梓俊.json')
os.makedirs(os.path.dirname(out_path), exist_ok=True)
with io.open(out_path, 'w', encoding='utf-8') as f:
    json.dump(doc, f, ensure_ascii=False, indent=1)
print('json ->', out_path, os.path.getsize(out_path), 'bytes')

# ---------- 7. xlsx content-equivalent twin (inline strings) ----------
def xlsxml(doc, sheetname):
    from xml.sax.saxutils import escape
    # rebuild grid: title rows, header, slots rows, entries placed at cells
    hdr = doc['term']['titleLines']
    ns = {}
    for ln in hdr:
        for key in ('年级', '院系', '专业', '姓名', '班级', '学号'):
            m = re.search(key + r'[：:]\s*([^　\s|]+)', ln)
            if m: ns[key] = m.group(1)
    cols = len(doc['weekdayNames']) + 1
    slot_rows = {}
    for e in doc['entries']:
        for k in range(e['slotSpan']):
            slot_rows.setdefault(e['startSlot'] + k, set()).add(e['day'])
    # build text map (dayIdx,slot) -> blocks text (labor etc. multiple blocks newline+blank)
    from collections import defaultdict
    textmap = defaultdict(list)
    for e in doc['entries']:
        raw = e['raw']
        for k in range(e['slotSpan']):
            textmap[(e['day'], e['startSlot'] + k)].append(raw)
    def pair_of(slot):
        return 1 + (slot - 1) // 2
    total_rows = len(slot_rows)
    rows_xml = []
    r = 0
    def esc(s): return escape(s or '')
    # title rows
    for i, t in enumerate(hdr):
        rows_xml.append('<row r="%d">' % (r + 1))
        rows_xml.append('<c r="A%d" t="inlineStr"><is><t>%s</t></is></c>' % (r + 1, esc(t)))
        rows_xml.append('</row>')
        r += 1
    # header
    rows_xml.append('<row r="%d">' % (r + 1))
    rows_xml.append('<c r="A%d" t="inlineStr"><is><t>%s</t></is></c>' % (r + 1, '节次'))
    for (ci, day) in enumerate(doc['weekdayNames']):
        col = col_letters(ci + 1)
        rows_xml.append('<c r="%s%d" t="inlineStr"><is><t>%s</t></is></c>' % (col, r + 1, esc(day)))
    rows_xml.append('</row>')
    hdr_row = r
    r += 1
    for slot in sorted(slot_rows):
        rows_xml.append('<row r="%d">' % (r + 1))
        rows_xml.append('<c r="A%d" t="inlineStr"><is><t>%d</t></is></c>' % (r + 1, slot))
        for d in range(len(doc['weekdayNames'])):
            texts = textmap.get((d, slot))
            if texts:
                val = '\n\n'.join(texts)
                col = col_letters(d + 1)
                rows_xml.append('<c r="%s%d" t="inlineStr"><is><t>%s</t></is></c>' % (col, r + 1, esc(val)))
        rows_xml.append('</row>')
        r += 1
    return ''.join(rows_xml)

def build_xlsx(doc, path):
    ss = []
    sh = xlsxml(doc, 'Sheet1')
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Default Extension="xml" ContentType="application/xml"/>'
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
        '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        '</Types>')
    rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        '</Relationships>')
    wb = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
        '<sheets><sheet name="课表" sheetId="1" r:id="rId1"/></sheets></workbook>')
    wbrels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
        '</Relationships>')
    sheetxml = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
        '<sheetData>' + sh + '</sheetData></worksheet>')
    with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', content_types)
        z.writestr('_rels/.rels', rels)
        z.writestr('xl/workbook.xml', wb)
        z.writestr('xl/_rels/workbook.xml.rels', wbrels)
        z.writestr('xl/worksheets/sheet1.xml', sheetxml)
    return path

xlsx_out = os.path.join(ROOT, 'samples', '课表-王梓俊.xlsx')
build_xlsx(doc, xlsx_out)
print('xlsx ->', xlsx_out, os.path.getsize(xlsx_out), 'bytes')

# ---------- 8. verification summary ----------
print()
print('== verification summary ==')
print('title lines:', title_cells)
print('weekCount:', weekCount, '| header row index:', head_row, '| slot rows:', [(r, n) for r, n in slot_rows])
print('day columns:', [(c, name, wd) for (c, wd, name) in day_cols])
courses = {}
for e in entries:
    key = (e['name'], e['teacher'])
    courses.setdefault(key, []).append(e)
print('distinct courses:', len(courses))
for (name, teacher), occ in sorted(courses.items(), key=lambda kv: kv[0][0]):
    print(' *', name, '|', teacher or '-', '->', len(occ), 'occurrence(s)')
    for o in occ:
        print('     day', o['day'], 'slots %d..%d' % (o['startSlot'], o['startSlot'] + o['slotSpan'] - 1),
              'weeks', o['weeks'] if o['weeks'] else o['weeksUnparsed'], 'room', o['room'])
print('total entries:', len(entries))
# week-occupancy sanity for a few weeks
def day_courses(doc, week, day):
    return [e for e in doc['entries'] if e['day'] == day and e['weeks'] and week in e['weeks']]
print('week1 Monday:', [(e['name'], e['startSlot']) for e in day_courses(doc, 1, 0)])
print('week17 Monday:', [(e['name'], e['startSlot']) for e in day_courses(doc, 17, 0)])
