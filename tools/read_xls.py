#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Minimal OLE2 (CFB) + BIFF8 (.xls) reader that dumps every sheet's cells
with coordinates, values, types and merged ranges. Pure standard library."""
import struct, sys, json, io

class Ole2:
    def __init__(self, data):
        self.data = data
        assert data[:8] == b'\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1', "not an OLE2 file"
        self.sector_shift = struct.unpack('<H', data[30:32])[0]
        self.mini_shift   = struct.unpack('<H', data[32:34])[0]
        self.sector_size  = 1 << self.sector_shift
        self.mini_size    = 1 << self.mini_shift
        self.num_dir_sectors = struct.unpack('<I', data[40:44])[0]
        self.num_fat_sectors = struct.unpack('<I', data[44:48])[0]
        self.first_dir_sector = struct.unpack('<I', data[48:52])[0]
        self.mini_cutoff = struct.unpack('<I', data[56:60])[0]
        self.first_minifat_sector = struct.unpack('<I', data[60:64])[0]
        self.num_minifat_sectors  = struct.unpack('<I', data[64:68])[0]
        self.first_difat_sector   = struct.unpack('<I', data[68:72])[0]
        self.num_difat_sectors    = struct.unpack('<I', data[72:76])[0]
        difat = list(struct.unpack('<109I', data[76:76+436]))
        # read extra DIFAT sectors if any
        nxt = self.first_difat_sector
        while nxt not in (0xFFFFFFFE, 0xFFFFFFFF) and nxt < len(self.sector(nxt, raw=False)) and self.num_difat_sectors:
            if nxt == 0xFFFFFFFD: break
            sec = self.read_sector(nxt)
            vals = list(struct.unpack('<128I', sec[:512]))
            difat += vals[:127]
            nxt = vals[127]
        # build FAT
        self.fat = []
        for fs in difat:
            if fs in (0xFFFFFFFE, 0xFFFFFFFF, 0xFFFFFFFD) or fs == 0xFFFFFFFC:
                continue
            if fs >= (len(self.data) // self.sector_size):
                continue
            self.fat += list(struct.unpack('<%dI' % (self.sector_size//4), self.read_sector(fs)))
        self.FREE, self.ENDOFCHAIN = 0xFFFFFFFF, 0xFFFFFFFE
        # directory entries
        self.entries = []
        self._read_directory(self.first_dir_sector)
        # mini stream
        root = self.entries[0] if self.entries else None
        self.mini_stream = b''
        self.minifat = []
        if root and root['size'] > 0:
            self.mini_stream = self.read_chain(root['start'])
            # build minifat
            sec = self.first_minifat_sector
            while sec not in (self.FREE, self.ENDOFCHAIN):
                self.minifat += list(struct.unpack('<%dI' % (self.sector_size//4), self.read_sector(sec)))
                sec = self.fat[sec]

    def read_sector(self, n):
        off = (n + 1) * self.sector_size
        return self.data[off:off+self.sector_size]

    def sector(self, n, raw=True):
        return self.read_sector(n)

    def read_chain(self, start):
        out = b''
        cur = start
        while cur not in (self.FREE, self.ENDOFCHAIN, 0xFFFFFFFD):
            if cur >= len(self.fat): break
            out += self.read_sector(cur)
            cur = self.fat[cur]
        return out

    def read_mini_chain(self, start, size):
        out = b''
        cur = start
        guard = 0
        while cur not in (self.FREE, self.ENDOFCHAIN) and guard < 100000:
            off = cur * self.mini_size
            out += self.mini_stream[off:off+self.mini_size]
            if len(out) >= size: break
            cur = self.minifat[cur] if cur < len(self.minifat) else self.ENDOFCHAIN
            guard += 1
        return out[:size]

    def _read_directory(self, sec):
        seen = set()
        while sec not in (self.FREE, self.ENDOFCHAIN) and sec not in seen:
            seen.add(sec)
            blob = self.read_sector(sec)
            for i in range(self.sector_size // 128):
                e = blob[i*128:(i+1)*128]
                if not e or e == b'\x00' * 128: continue
                name_len = struct.unpack('<H', e[64:66])[0]
                name = e[:name_len-2].decode('utf-16-le', 'replace') if name_len >= 2 else ''
                otype = e[66]
                start = struct.unpack('<I', e[116:120])[0]
                size = struct.unpack('<Q', e[120:128])[0]
                ent = {'name': name, 'type': otype, 'start': start, 'size': size}
                self.entries.append(ent)
                if name == 'Root Entry' and otype == 5:
                    self.root = ent
            sec = self.fat[sec]

    def stream(self, name):
        for e in self.entries:
            if e['name'].lower() == name.lower():
                if e['size'] < self.mini_cutoff and e['start'] != self.ENDOFCHAIN:
                    return self.read_mini_chain(e['start'], e['size'])
                return self.read_chain(e['start'])
        return None

# ---------------- BIFF8 ----------------
class XlsBook:
    def __init__(self, wb_stream):
        self.wb = wb_stream
        self.sst = []          # shared strings
        self.sheets = []       # (name, cells: {..}, merged, bof_off)
        self._parse()

    def _iter_records(self, buf, start=0):
        i = start
        while i + 4 <= len(buf):
            op, ln = struct.unpack_from('<HH', buf, i)
            yield op, buf[i+4:i+4+ln]
            i += 4 + ln

    def _parse_unicode(self, b, off):
        cch = struct.unpack_from('<H', b, off)[0]; off += 2
        grbit = b[off]; off += 1
        fHigh = grbit & 1
        if fHigh:
            s = b[off:off+cch*2].decode('utf-16-le', 'replace')
        else:
            s = b[off:off+cch].decode('latin-1')
        return s

    def _parse_rich_unicode(self, b, off):
        # XLUnicodeRichExtendedString inside SST
        cch = struct.unpack_from('<H', b, off)[0]; off += 2
        flags = b[off]; off += 1
        fHigh = flags & 1
        fRich = flags & 8
        fExt  = flags & 4
        if fRich: off += 2
        if fExt:  off += 4
        if fHigh:
            s = b[off:off+cch*2].decode('utf-16-le', 'replace')
        else:
            s = b[off:off+cch].decode('latin-1')
        return s

    def _parse(self):
        records = list(self._iter_records(self.wb))
        i = 0
        nrec = len(records)
        globals_done = False
        current = None
        bound_sheets = []   # (offset, name)
        pending_sst = None

        # First pass: collect SST (with continuation), boundsheets
        # We need two passes since sheets appear after globals.
        raw = {}
        for idx, (op, data) in enumerate(records):
            raw.setdefault(op, []).append((idx, data))

        # build SST buffer incl CONTINUEs that immediately follow
        sst_idx = None
        for idx, (op, data) in enumerate(records):
            if op == 0x00FC:  # SST
                sst_idx = idx
                break
        if sst_idx is not None:
            buf = records[sst_idx][1]
            j = sst_idx + 1
            while j < nrec and records[j][0] == 0x003C:
                buf += records[j][1]; j += 1
            if len(buf) >= 8:
                total, unique = struct.unpack_from('<II', buf, 0)
                off = 8
                for _ in range(unique):
                    s = self._parse_rich_unicode(buf, off)
                    self.sst.append(s)
                    # advance off by consumed bytes: recompute
                    cch = struct.unpack_from('<H', buf, off)[0]
                    flags = buf[off+2]
                    consumed = 3 + (2 if flags & 8 else 0) + (4 if flags & 4 else 0) + (cch*2 if flags & 1 else cch)
                    off += consumed

        # collect boundsheets and their order of appearance = sheet order
        bounds = []
        if 0x0085 in raw:
            for idx, data in raw[0x0085]:
                if len(data) < 6: continue
                pos = struct.unpack_from('<I', data, 0)[0]
                name = self._parse_unicode(data, 6)
                bounds.append((pos, name))

        # Now scan substreams: workbook globals start at 0; each worksheet begins with BOF record 0x0809 whose data[2:4] is dt; globals dt=0x0005, worksheet dt=0x0010.
        stream_cells = {}   # bof_off -> {'name', 'cells': {...}}
        # determine sheet BOF offsets: they're equal to bounds pos (each sheet BOF is at its substream start, byte offset in workbook stream)
        bof_by_off = {p: nm for p, nm in bounds}

        current = None
        for op, data in records:
            if op == 0x0809:  # BOF
                dt = struct.unpack_from('<H', data, 2)[0] if len(data) >= 4 else 0
                if dt == 0x0010:  # worksheet
                    # find offset: we didn't record offsets; recompute by scanning to here
                    pass
            # simpler: single sequential pass computing offsets ourselves

        # sequential pass with offsets
        off = 0
        current = None
        for op, data in records:
            ln = len(data)
            if op == 0x0809 and len(data) >= 4 and struct.unpack_from('<H', data, 2)[0] == 0x0010:
                name = bof_by_off.get(off, 'Sheet?')
                current = {'name': name, 'cells': {}, 'merged': [], 'bof': off}
                stream_cells[off] = current
            if current is not None:
                if op == 0x00FD:  # LABELSST
                    r, c, xf, isst = struct.unpack_from('<HHHI', data, 0)
                    if isst < len(self.sst):
                        current['cells'][(r, c)] = ('s', self.sst[isst])
                elif op == 0x0204:  # LABEL (BIFF2-7 rare)
                    r, c = struct.unpack_from('<HH', data, 0)
                    s = self._parse_unicode(data, 6)
                    current['cells'][(r, c)] = ('s', s)
                elif op == 0x0203:  # NUMBER
                    r, c = struct.unpack_from('<HH', data, 0)
                    v = struct.unpack_from('<d', data, 6)[0]
                    current['cells'][(r, c)] = ('n', v)
                elif op == 0x027E:  # RK
                    r, c = struct.unpack_from('<HH', data, 0)
                    rk = struct.unpack_from('<I', data, 6)[0]
                    v = self._rk_value(rk)
                    current['cells'][(r, c)] = ('n', v)
                elif op == 0x00BD:  # MULRK
                    r, cfirst = struct.unpack_from('<HH', data, 0)
                    n = (len(data) - 6) // 6
                    for k in range(n):
                        rk = struct.unpack_from('<I', data, 6 + k*6)[0]
                        v = self._rk_value(rk)
                        current['cells'][(r, cfirst + k)] = ('n', v)
                elif op == 0x0006:  # FORMULA
                    r, c = struct.unpack_from('<HH', data, 0)
                    ctype = struct.unpack_from('<H', data, 6)[0]
                    if ctype == 0:
                        v = struct.unpack_from('<d', data, 8)[0]
                        current['cells'][(r, c)] = ('n', v)
                    elif ctype == 1:
                        current['cells'][(r, c)] = ('formula-string?', '')
                    elif ctype == 2:
                        current['cells'][(r, c)] = ('b', bool(data[8]))
                    else:
                        current['cells'][(r, c)] = ('err', '')
                elif op == 0x00E5:  # MERGEDCELLS
                    cnt = struct.unpack_from('<H', data, 0)[0]
                    for k in range(cnt):
                        r1, r2, c1, c2 = struct.unpack_from('<HHHH', data, 2 + k*8)
                        current['merged'].append((r1, r2, c1, c2))
                elif op == 0x000A:  # EOF
                    if current is not None:
                        current = None
            off += 4 + ln

        self.sheets = []
        # order sheets by bof offset (ascending) matching bounds order
        ordered = sorted(stream_cells.values(), key=lambda s: s['bof'])
        self.sheets = ordered

    def _rk_value(self, rk):
        if rk & 2:
            v = (rk >> 2) / 100.0
        else:
            v = struct.unpack('<i', struct.pack('<I', rk & 0xFFFFFFFC))[0] / 100.0 if rk & 1 == 0 else 0
        # RK: bits 0-1: 00 = integer, 01=100x, 10=number<<2
        if rk & 1 == 0:
            if rk & 2:
                # 100 * int
                v = ((rk >> 2) if rk < 0x80000000 else (rk >> 2) - 0x40000000) / 100.0
            else:
                v = float(struct.unpack('<i', struct.pack('<I', rk & 0xFFFFFFFC))[0] >> 2)
        else:
            v = struct.unpack('<d', struct.pack('<Q', (rk & 0xFFFFFFFC) << 32))[0]
        return v

    def dump(self):
        out = []
        for sh in self.sheets:
            out.append("=== SHEET: %s ===" % sh['name'])
            for (r, c) in sorted(sh['cells']):
                typ, v = sh['cells'][(r, c)]
                if typ == 'n':
                    if float(v).is_integer():
                        vs = str(int(v))
                    else:
                        vs = repr(v)
                else:
                    vs = str(v)
                out.append("R%dC%d: %s" % (r, c, vs))
            if sh['merged']:
                out.append("--MERGED--")
                for m in sh['merged']:
                    out.append("merge r%d..r%d c%d..c%d" % m)
            out.append("")
        return "\n".join(out)

if __name__ == '__main__':
    import io, os
    path = sys.argv[1]
    data = open(path, 'rb').read()
    ole = Ole2(data)
    wbs = ole.stream('Workbook')
    if not wbs:
        wbs = ole.stream('Book')  # BIFF5
    if not wbs:
        raise SystemExit('no Workbook stream')
    book = XlsBook(wbs)
    text = book.dump()
    outpath = sys.argv[2] if len(sys.argv) > 2 else (os.path.splitext(path)[0] + '.dump.txt')
    with io.open(outpath, 'w', encoding='utf-8') as f:
        f.write(text)
    print("wrote", outpath, "chars:", len(text))
