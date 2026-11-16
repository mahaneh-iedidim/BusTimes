"""mkbus.py <israel-public-transportation.zip> <out bus.dat>: the timetable file of the bus app from the GTFS of
the Ministry of Transport.

Trips with the same stops (a "pattern") and the same minutes between the stops form a group: a group stores the
minute offsets once and each trip as its first departure + the set of days it runs. Days are bits over the feed
period (day 0 = the feed's first date).

Layout (big-endian; varint = unsigned LEB128, str = varint length + UTF-8):
  header 'ILBS' u16 version u16 sections; sections: id u8, offset u32, length u32
  1 meta:      u32 firstDate (yyyymmdd), u16 days, varint nDaysets, per dayset: u32 bits 0..31, u32 bits 32..63,
               varint nAgencies, str names, str built (text)
  2 stops:     varint n, varint perBlock, n/perBlock+1 x u32 block offsets (from the section start), blocks:
               per stop: i32 lat, i32 lon (micro-degrees), varint code, str name, str city, str street, str nameEn,
               varint nRefs, per ref: varint pattern, varint position, varint nWalks, per walk: varint stop, u8 minutes
  8 coords:    per station u16 (lat - 29N) / 100, u16 (lon - 34E) / 100 (micro-degrees)
  3 lines:     varint n, n+1 x u32 offsets (from the section start), per line: str short name, varint agency, u8 type (GTFS route_type), str from, str to,
               varint nPatterns, varint pattern ids (main direction first)   (sorted by number, then agency)
  4 patterns:  varint n, n x u32 offsets (from the section start), blobs:
               varint line, str headsign, varint nStops, varint stop ids, varint nGroups, per group:
               nStops-1 x varint minutes between consecutive stops, varint nTrips, per trip: varint start (delta,
               minutes from midnight, may pass 24:00), varint dayset
  5 names:     u32 headLen, head: varint nBlocks, per block: str first key, u32 offset (after the head); blocks:
               varint n, per entry: str normalized name (or its tail from a later word), varint stop
  7 codes:     u32 n, n x (u32 stop code, u16 stop), sorted by code
  6 grid:      stops by cell of 0.01 degrees: i32 lat0, i32 lon0, u16 rows, u16 cols, rows*cols+1 x u32 start,
               varint stop ids (rows major)"""
import sys, os, io, re, struct, zipfile, time, collections
import numpy as np, pandas as pd

def vint(b, v):
    while v >= 0x80:
        b.append((v & 0x7F) | 0x80); v >>= 7
    b.append(v)

def vstr(b, s):
    e = (s or '').encode('utf-8'); vint(b, len(e)); b.extend(e)

FINALS = {'ך': 'כ', 'ם': 'מ', 'ן': 'נ', 'ף': 'פ', 'ץ': 'צ'}
def norm(s):
    out = []
    for ch in s:
        o = ord(ch)
        if 0x591 <= o <= 0x5C7: continue
        ch = FINALS.get(ch, ch)
        if 0x5D0 <= ord(ch) <= 0x5EA or ch.isdigit() or ('a' <= ch.lower() <= 'z'):
            out.append(ch.lower())
        elif out and out[-1] != ' ':
            out.append(' ')
    return ''.join(out).strip()

def main():
    src, out = sys.argv[1:3]
    t0 = time.time()
    z = zipfile.ZipFile(src)
    rd = lambda n, **k: pd.read_csv(io.TextIOWrapper(z.open(n), encoding='utf-8-sig'), **k)
    agency = rd('agency.txt', dtype=str)
    stops = rd('stops.txt', dtype={'stop_id': str, 'stop_code': str, 'stop_name': str, 'stop_desc': str, 'parent_station': str})
    routes = rd('routes.txt', dtype=str)
    trips = rd('trips.txt', dtype=str)
    cal = rd('calendar.txt', dtype=str)
    tr = rd('translations.txt', dtype=str, keep_default_na=False)
    print('read small tables %.0fs' % (time.time() - t0))
    st = rd('stop_times.txt', usecols=['trip_id', 'departure_time', 'stop_id', 'stop_sequence'],
            dtype={'trip_id': str, 'departure_time': str, 'stop_id': str, 'stop_sequence': np.int32})
    st['trip_id'] = st.trip_id.astype('category')
    print('stop_times %d rows %.0fs' % (len(st), time.time() - t0))

    # ---- days
    first = min(cal.start_date); last = max(cal.end_date)
    import datetime
    d0 = datetime.date(int(first[:4]), int(first[4:6]), int(first[6:]))
    d1 = datetime.date(int(last[:4]), int(last[4:6]), int(last[6:]))
    ndays = min(64, (d1 - d0).days + 1)
    WD = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']
    svc_bits = {}
    for r in cal.itertuples(index=False):
        s = datetime.date(int(r.start_date[:4]), int(r.start_date[4:6]), int(r.start_date[6:]))
        e = datetime.date(int(r.end_date[:4]), int(r.end_date[4:6]), int(r.end_date[6:]))
        bits = 0
        for k in range(ndays):
            d = d0 + datetime.timedelta(days=k)
            if s <= d <= e and getattr(r, WD[d.weekday()]) == '1': bits |= 1 << k
        svc_bits[r.service_id] = bits
    dayset_ix = {}
    daysets = []
    def dayset(bits):
        if bits not in dayset_ix:
            dayset_ix[bits] = len(daysets); daysets.append(bits)
        return dayset_ix[bits]

    # ---- stops (boarding points only; stations of the rail are their own stops in this feed)
    stops = stops[stops.location_type.astype(str) == '0'].reset_index(drop=True)
    en = {}
    for r in tr.itertuples(index=False):
        if r.lang == 'EN': en[r.trans_id] = r.translation
    city_re = re.compile(r'עיר:\s*(.*?)\s*רציף:')
    street_re = re.compile(r'רחוב:\s*(.*?)\s*עיר:')
    S = len(stops)
    sid_ix = {sid: i for i, sid in enumerate(stops.stop_id)}
    s_lat = np.round(stops.stop_lat.values * 1e6).astype(np.int64)
    s_lon = np.round(stops.stop_lon.values * 1e6).astype(np.int64)
    s_name = [n.replace("''", '"').replace('_', ' ') for n in stops.stop_name.fillna('')]
    s_city, s_street = [], []
    for dsc in stops.stop_desc.fillna(''):
        m = city_re.search(dsc); s_city.append(m.group(1).strip() if m else '')
        m = street_re.search(dsc); s_street.append(m.group(1).strip() if m else '')
    s_code = [int(c) if str(c).isdigit() else 0 for c in stops.stop_code.fillna('0')]
    s_en = [en.get(n, '') for n in stops.stop_name.fillna('')]
    print('stops', S)

    # ---- trips: stop sequences and minutes
    st = st[st.stop_id.isin(list(sid_ix))]
    print('stop_times on stops', len(st))
    st = st.sort_values(['trip_id', 'stop_sequence'], kind='stable')
    parts = st.departure_time.str.split(':')
    hh = parts.str[0].astype(np.int32).values
    mm = parts.str[1].astype(np.int32).values
    ss = parts.str[2].astype(np.int32).values
    minutes = hh * 60 + mm + (ss >= 30)
    stop_ix = st.stop_id.map(sid_ix).astype(np.int32).values
    tcode = np.asarray(st.trip_id.cat.codes.values, dtype=np.int64)
    tcats = [str(x) for x in st.trip_id.cat.categories]
    tid = [tcats[c] for c in tcode]
    bounds = np.flatnonzero(np.r_[True, tcode[1:] != tcode[:-1], True])
    print('trips in stop_times', len(bounds) - 1, '%.0fs' % (time.time() - t0))
    trip_info = {r.trip_id: r for r in trips.itertuples(index=False)}
    route_info = {r.route_id: r for r in routes.itertuples(index=False)}

    patterns = {}        # (route_id, stops tuple) -> index
    pat_list = []        # [route_id, stops, headsign, groups dict offsets->list of (start, dayset)]
    for k in range(len(bounds) - 1):
        a, b = bounds[k], bounds[k + 1]
        if b - a < 2: continue
        info = trip_info.get(tid[a])
        if info is None or info.service_id not in svc_bits: continue
        bits = svc_bits[info.service_id]
        if bits == 0: continue
        stops_t = tuple(stop_ix[a:b].tolist())
        key = (info.route_id, stops_t)
        p = patterns.get(key)
        if p is None:
            p = patterns[key] = len(pat_list)
            pat_list.append([info.route_id, stops_t, info.trip_headsign.replace('_', ' ') if isinstance(info.trip_headsign, str) else '', collections.defaultdict(list)])
        m = minutes[a:b]
        offs = tuple(np.diff(m).tolist())
        if min(offs) < 0: offs = tuple(max(0, x) for x in offs)
        pat_list[p][3][offs].append((int(m[0]), dayset(bits)))
    print('patterns', len(pat_list), 'daysets', len(daysets), '%.0fs' % (time.time() - t0))

    # ---- lines: one per (agency, short name, cluster of route_desc), directions as patterns
    line_key = {}
    lines = []
    pat_line = [0] * len(pat_list)
    trips_of = [sum(len(v) for v in p[3].values()) for p in pat_list]
    for pi, p in enumerate(pat_list):
        r = route_info[p[0]]
        cluster = str(r.route_desc).split('-')[0]
        key = (r.agency_id, r.route_short_name, cluster)
        if key not in line_key:
            line_key[key] = len(lines)
            ln = str(r.route_long_name)
            ends = re.sub(r'-[0-9#]+$', '', ln).replace('_', ' ').split('<->')
            lines.append({'short': str(r.route_short_name), 'agency': r.agency_id, 'type': int(r.route_type),
                          'from': ends[0], 'to': ends[-1] if len(ends) > 1 else '', 'pats': []})
        li = line_key[key]
        pat_line[pi] = li
        lines[li]['pats'].append(pi)
    ag_ix = {a: i for i, a in enumerate(agency.agency_id)}
    def numkey(s):
        m = re.match(r'(\d+)(.*)', s)
        return (int(m.group(1)), m.group(2)) if m else (99999, s)
    order = sorted(range(len(lines)), key=lambda i: (numkey(lines[i]['short']), ag_ix.get(lines[i]['agency'], 0), lines[i]['from']))
    new_ix = {old: new for new, old in enumerate(order)}
    lines = [lines[i] for i in order]
    pat_line = [new_ix[x] for x in pat_line]
    for ln in lines:
        ln['pats'].sort(key=lambda p: -trips_of[p])
    print('lines', len(lines))

    # ---- stop -> (pattern, position)
    refs = [[] for _ in range(S)]
    for pi, p in enumerate(pat_list):
        for pos, s in enumerate(p[1]):
            refs[s].append((pi, pos))

    # walking transfers: stations within 350 m (minutes = 1.25 x straight line at 75 m/min)
    cell = collections.defaultdict(list)
    for s in range(S): cell[(int(s_lat[s]) // 4000, int(s_lon[s]) // 4000)].append(s)
    walks = [[] for _ in range(S)]
    for s in range(S):
        ca, co = int(s_lat[s]) // 4000, int(s_lon[s]) // 4000
        for da in (-1, 0, 1):
            for do in (-1, 0, 1):
                for o in cell.get((ca + da, co + do), ()):
                    if o == s: continue
                    dy = (int(s_lat[o]) - int(s_lat[s])) * 0.11132; dx = (int(s_lon[o]) - int(s_lon[s])) * 0.09491
                    d = (dx * dx + dy * dy) ** 0.5
                    if d <= 350: walks[s].append((o, max(1, int((d * 1.25 + 74) // 75))))
        walks[s].sort(key=lambda w: w[1]); del walks[s][16:]
    print('transfers', sum(len(w) for w in walks))

    secs = {}
    # meta
    b = bytearray(struct.pack('>IH', int(first), ndays))
    vint(b, len(daysets))
    for bits in daysets: b.extend(struct.pack('>II', bits & 0xFFFFFFFF, bits >> 32))
    vint(b, len(agency))
    for n in agency.agency_name: vstr(b, n)
    vstr(b, 'GTFS %s-%s, built %s' % (first, last, time.strftime('%Y-%m-%d')))
    secs[1] = bytes(b)
    # stops in blocks
    PER = 32
    blocks = []
    for s0 in range(0, S, PER):
        bb = bytearray()
        for s in range(s0, min(S, s0 + PER)):
            bb.extend(struct.pack('>ii', int(s_lat[s]), int(s_lon[s])))
            vint(bb, s_code[s]); vstr(bb, s_name[s]); vstr(bb, s_city[s]); vstr(bb, s_street[s]); vstr(bb, s_en[s])
            vint(bb, len(refs[s]))
            for pi, pos in refs[s]: vint(bb, pi); vint(bb, pos)
            vint(bb, len(walks[s]))
            for o, m in walks[s]: vint(bb, o); bb.append(m)
        blocks.append(bytes(bb))
    hb = bytearray(); vint(hb, S); vint(hb, PER)
    nb = len(blocks)
    base = len(hb) + 4 * (nb + 1)
    offs = []; o = base
    for x in blocks: offs.append(o); o += len(x)
    offs.append(o)
    secs[2] = bytes(hb) + b''.join(struct.pack('>I', x) for x in offs) + b''.join(blocks)
    # lines (with an offset table)
    recs = []
    for ln in lines:
        bb = bytearray()
        vstr(bb, ln['short']); vint(bb, ag_ix.get(ln['agency'], 0)); bb.append(ln['type'] & 255)
        vstr(bb, ln['from']); vstr(bb, ln['to']); vint(bb, len(ln['pats']))
        for p in ln['pats']: vint(bb, p)
        recs.append(bytes(bb))
    hb = bytearray(); vint(hb, len(recs))
    base = len(hb) + 4 * (len(recs) + 1)
    offs = []; o = base
    for x in recs: offs.append(o); o += len(x)
    offs.append(o)
    secs[3] = bytes(hb) + b''.join(struct.pack('>I', x) for x in offs) + b''.join(recs)
    # patterns
    blobs = []
    ntrips = 0
    for pi, p in enumerate(pat_list):
        bb = bytearray()
        vint(bb, pat_line[pi]); vstr(bb, p[2]); vint(bb, len(p[1]))
        for s in p[1]: vint(bb, s)
        groups = p[3]
        vint(bb, len(groups))
        for offs_, tl in groups.items():
            for x in offs_: vint(bb, x)
            tl.sort()
            vint(bb, len(tl)); prev = 0
            for st_, ds in tl:
                vint(bb, st_ - prev); prev = st_; vint(bb, ds); ntrips += 1
        blobs.append(bytes(bb))
    hb = bytearray(); vint(hb, len(blobs))
    base = len(hb) + 4 * len(blobs)
    offs = []; o = base
    for x in blobs: offs.append(o); o += len(x)
    secs[4] = bytes(hb) + b''.join(struct.pack('>I', x) for x in offs) + b''.join(blobs)
    print('trips stored', ntrips, 'pattern bytes %.1f MB' % (len(secs[4]) / 1e6), 'largest %d KB' % (max(len(x) for x in blobs) // 1024))
    # name index
    # names: every word start of the name and of "name city" can be searched: entries for the whole name and
    # for the name from each later word on (so "הרצל" finds "בן יהודה/הרצל")
    ent = set()
    for s in range(S):
        if not s_name[s]: continue
        words = norm(s_name[s]).split(' ')
        for k in range(len(words)):
            key = ' '.join(words[k:])
            if key and not key.isdigit(): ent.add((key, s))
    ent = sorted(ent, key=lambda e: (e[0], s_city[e[1]]))
    blocks, keys = [], []
    cur = bytearray(); n = 0
    for k, s in ent:
        if n == 0: keys.append(k)
        vstr(cur, k); vint(cur, s); n += 1
        if len(cur) > 1500:
            bb = bytearray(); vint(bb, n); bb.extend(cur); blocks.append(bytes(bb)); cur = bytearray(); n = 0
    if n:
        bb = bytearray(); vint(bb, n); bb.extend(cur); blocks.append(bytes(bb))
    head = bytearray(); vint(head, len(blocks))
    o = 0
    for k, x in zip(keys, blocks):
        vstr(head, k); head.extend(struct.pack('>I', o)); o += len(x)
    secs[5] = struct.pack('>I', len(head)) + bytes(head) + b''.join(blocks)
    print('names', len(ent), 'blocks', len(blocks), 'index %d KB' % (len(head) // 1024))
    # stop codes: sorted (u32 code, u16 stop)
    codes = sorted((s_code[s], s) for s in range(S) if s_code[s])
    secs[7] = struct.pack('>I', len(codes)) + b''.join(struct.pack('>IH', c, s) for c, s in codes)
    # grid
    lat0, lon0 = 29400000, 34200000
    rows, cols = 430, 160
    cells = collections.defaultdict(list)
    for s in range(S):
        r = (int(s_lat[s]) - lat0) // 10000; c = (int(s_lon[s]) - lon0) // 10000
        if 0 <= r < rows and 0 <= c < cols: cells[r * cols + c].append(s)
    b = bytearray(struct.pack('>iiHH', lat0, lon0, rows, cols))
    body = bytearray(); starts = []
    for k in range(rows * cols):
        starts.append(len(body))
        for s in cells.get(k, ()): vint(body, s)
    starts.append(len(body))
    base = len(b) + 4 * len(starts)
    b.extend(b''.join(struct.pack('>I', base + x) for x in starts)); b.extend(body)
    secs[6] = bytes(b)

    # coordinates of every station, 100 micro-degrees (about 11 m) from 29N 34E
    secs[8] = b''.join(struct.pack('>HH', max(0, min(65535, (int(s_lat[s]) - 29000000) // 100)), max(0, min(65535, (int(s_lon[s]) - 34000000) // 100))) for s in range(S))
    ids = sorted(secs)
    head = bytearray(b'ILBS' + struct.pack('>HH', 1, len(ids)))
    off = 8 + 9 * len(ids)
    for i in ids:
        head.extend(struct.pack('>BII', i, off, len(secs[i]))); off += len(secs[i])
    with open(out, 'wb') as f:
        f.write(head)
        for i in ids: f.write(secs[i])
    print('wrote %s %.1f MB (%.0fs)' % (out, os.path.getsize(out) / 1e6, time.time() - t0))
    for i in ids: print('  section', i, '%.2f MB' % (len(secs[i]) / 1e6))

main()
