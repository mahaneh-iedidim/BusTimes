package bs;

import java.io.IOException;
import java.util.Vector;

/**
 * The timetable file bus.dat (tools/mkbus.py): stations, lines, patterns (lines' stop sequences with their trips),
 * the station name and code indexes and the location grid. Stations and patterns are read when needed and kept
 * in small caches.
 */
final class Dat {
    static final class Stop {
        int id, lat, lon, code;
        String name, city, street, en;
        int[] refP, refPos;
        int[] wStop;     // stations within a short walk
        byte[] wMin;     // and the minutes to each
    }

    static final class Line {
        int id, agency, type;
        String num, from, to;
        int[] pats;
    }

    static final class Pat {
        int id, line, use;
        String head;
        int[] stops;
        int[][] cum;     // per group: minutes from the first stop to each stop
        int[][] start;   // per group: first departure of each trip (minutes from midnight)
        int[][] ds;      // per group: day set of each trip
        int bytes;
    }

    final Card f;
    int firstDate, days;
    int[] dsLo, dsHi;
    String[] agencies;
    String built = "";
    int nStops, perBlock;
    int[] blockOff;
    int nLines;
    int nPats;
    int lineBase;

    Dat(Card f) throws IOException {
        this.f = f;
        byte[] m = f.read(f.sOff[1], f.sLen[1]);
        int[] p = {0};
        firstDate = Card.s32(m, 0);
        days = Card.u16(m, 4);
        p[0] = 6;
        int nd = Card.vint(m, p);
        dsLo = new int[nd];
        dsHi = new int[nd];
        for (int i = 0; i < nd; i++) {
            dsLo[i] = Card.s32(m, p[0]);
            dsHi[i] = Card.s32(m, p[0] + 4);
            p[0] += 8;
        }
        int na = Card.vint(m, p);
        agencies = new String[na];
        for (int i = 0; i < na; i++) agencies[i] = Card.str(m, p);
        built = Card.str(m, p);
        // stations: the block table
        byte[] h = f.read(f.sOff[2], 12);
        p[0] = 0;
        nStops = Card.vint(h, p);
        perBlock = Card.vint(h, p);
        int nb = (nStops + perBlock - 1) / perBlock;
        byte[] t = f.read(f.sOff[2] + p[0], (nb + 1) * 4);
        blockOff = new int[nb + 1];
        for (int i = 0; i <= nb; i++) blockOff[i] = Card.s32(t, i * 4);
        h = f.read(f.sOff[3], 6);
        p[0] = 0;
        nLines = Card.vint(h, p);
        lineBase = p[0];
        h = f.read(f.sOff[4], 6);
        p[0] = 0;
        nPats = Card.vint(h, p);
        patBase = p[0];
    }

    int patBase;

    /** does day set ds run on day d (0 = the first date of the data) */
    boolean on(int ds, int d) {
        if (d < 0 || d >= days) return false;
        return d < 32 ? (dsLo[ds] >>> d & 1) != 0 : (dsHi[ds] >>> (d - 32) & 1) != 0;
    }

    // ---------- stations ----------
    private final Stop[][] sBlock = new Stop[24][];
    private final int[] sBlockId = new int[24], sBlockUse = new int[24];
    private int clock;

    synchronized Stop stop(int id) throws IOException {
        int b = id / perBlock;
        int slot = -1;
        for (int i = 0; i < sBlock.length; i++) if (sBlock[i] != null && sBlockId[i] == b) slot = i;
        if (slot < 0) {
            stopLoads++;
            byte[] d = f.read(f.sOff[2] + blockOff[b], blockOff[b + 1] - blockOff[b]);
            int n = Math.min(perBlock, nStops - b * perBlock);
            Stop[] ss = new Stop[n];
            int[] p = {0};
            for (int k = 0; k < n; k++) {
                Stop s = new Stop();
                s.id = b * perBlock + k;
                s.lat = Card.s32(d, p[0]);
                s.lon = Card.s32(d, p[0] + 4);
                p[0] += 8;
                s.code = Card.vint(d, p);
                s.name = Card.str(d, p);
                s.city = Card.str(d, p);
                s.street = Card.str(d, p);
                s.en = Card.str(d, p);
                int nr = Card.vint(d, p);
                s.refP = new int[nr];
                s.refPos = new int[nr];
                for (int r = 0; r < nr; r++) {
                    s.refP[r] = Card.vint(d, p);
                    s.refPos[r] = Card.vint(d, p);
                }
                int nw = Card.vint(d, p);
                s.wStop = new int[nw];
                s.wMin = new byte[nw];
                for (int r = 0; r < nw; r++) {
                    s.wStop[r] = Card.vint(d, p);
                    s.wMin[r] = d[p[0]++];
                }
                ss[k] = s;
            }
            slot = 0;
            for (int i = 0; i < sBlock.length; i++) {
                if (sBlock[i] == null) {
                    slot = i;
                    break;
                }
                if (sBlockUse[i] < sBlockUse[slot]) slot = i;
            }
            sBlock[slot] = ss;
            sBlockId[slot] = b;
        }
        sBlockUse[slot] = ++clock;
        return sBlock[slot][id - b * perBlock];
    }

    // ---------- the planner's view of a station: only numbers ----------
    private final int[][][] lBlock = new int[64][][];
    private final int[] lBlockId = new int[64], lBlockUse = new int[64];

    /**
     * the lines and walks of a station without its texts: {nRefs, pattern, position, ..., nWalks, station,
     * minutes, ...}; kept in a small cache of blocks (a full station with its names takes many times more memory)
     */
    synchronized int[] links(int id) throws IOException {
        int b = id / perBlock;
        int slot = -1;
        for (int i = 0; i < lBlock.length; i++) if (lBlock[i] != null && lBlockId[i] == b) slot = i;
        if (slot < 0) {
            stopLoads++;
            byte[] d = f.read(f.sOff[2] + blockOff[b], blockOff[b + 1] - blockOff[b]);
            int n = Math.min(perBlock, nStops - b * perBlock);
            int[][] ls = new int[n][];
            int[] p = {0};
            for (int k = 0; k < n; k++) {
                p[0] += 8;
                Card.vint(d, p);
                for (int s = 0; s < 4; s++) {
                    int l = Card.vint(d, p);
                    p[0] += l;
                }
                int nr = Card.vint(d, p);
                int start = p[0];
                for (int r = 0; r < nr * 2; r++) Card.vint(d, p);
                int nw = Card.vint(d, p);
                int[] a = new int[2 + nr * 2 + nw * 2];
                p[0] = start;
                a[0] = nr;
                for (int r = 0; r < nr * 2; r++) a[1 + r] = Card.vint(d, p);
                Card.vint(d, p);
                a[1 + nr * 2] = nw;
                for (int r = 0; r < nw; r++) {
                    a[2 + nr * 2 + r * 2] = Card.vint(d, p);
                    a[3 + nr * 2 + r * 2] = d[p[0]++];
                }
                ls[k] = a;
            }
            slot = 0;
            for (int i = 0; i < lBlock.length; i++) {
                if (lBlock[i] == null) {
                    slot = i;
                    break;
                }
                if (lBlockUse[i] < lBlockUse[slot]) slot = i;
            }
            lBlock[slot] = ls;
            lBlockId[slot] = b;
        }
        lBlockUse[slot] = ++clock;
        return lBlock[slot][id - b * perBlock];
    }

    // ---------- lines ----------
    synchronized Line line(int id) throws IOException {
        byte[] o = f.read(f.sOff[3] + lineBase + id * 4, 8);
        int a = Card.s32(o, 0), b = Card.s32(o, 4);
        byte[] d = f.read(f.sOff[3] + a, b - a);
        int[] p = {0};
        Line l = new Line();
        l.id = id;
        l.num = Card.str(d, p);
        l.agency = Card.vint(d, p);
        l.type = d[p[0]++] & 255;
        l.from = Card.str(d, p);
        l.to = Card.str(d, p);
        int n = Card.vint(d, p);
        l.pats = new int[n];
        for (int i = 0; i < n; i++) l.pats[i] = Card.vint(d, p);
        return l;
    }

    // line numbers and agencies of the lines (for badges), read once on demand in pieces
    private String[] lNum = new String[0];
    private byte[] lAg, lType;
    private final int[] lineCacheKey = new int[64];
    private final Line[] lineCache = new Line[64];

    /** a line from a small cache */
    Line lineC(int id) throws IOException {
        int h = id & 63;
        if (lineCache[h] != null && lineCacheKey[h] == id) return lineCache[h];
        Line l = line(id);
        lineCache[h] = l;
        lineCacheKey[h] = id;
        return l;
    }

    /** lines whose number is num (or starts with it when prefix), by binary search over the sorted table */
    Vector findLines(String num, boolean prefix) throws IOException {
        Vector v = new Vector();
        int lo = 0, hi = nLines - 1, first = nLines;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            if (cmpNum(lineC(m).num, num) < 0) lo = m + 1;
            else {
                first = m;
                hi = m - 1;
            }
        }
        for (int i = first; i < nLines && v.size() < 120; i++) {
            Line l = lineC(i);
            if (l.num.equals(num) || (prefix && l.num.startsWith(num) && numPart(l.num) == numPart(num))) v.addElement(l);
            else break;
        }
        return v;
    }

    static int numPart(String s) {
        int n = 0, i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            n = n * 10 + s.charAt(i) - '0';
            i++;
        }
        return i == 0 ? 99999 : n;
    }

    /** the order of mkbus.py: the number, then the rest */
    static int cmpNum(String a, String b) {
        int x = numPart(a), y = numPart(b);
        if (x != y) return x < y ? -1 : 1;
        return rest(a).compareTo(rest(b));
    }

    static String rest(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return s.substring(i);
    }

    // ---------- patterns ----------
    private final Pat[] pc = new Pat[40];
    private int pcBytes;

    int patLoads, stopLoads;

    synchronized Pat pat(int id) throws IOException {
        for (int i = 0; i < pc.length; i++) if (pc[i] != null && pc[i].id == id) {
            pc[i].use = ++clock;
            return pc[i];
        }
        patLoads++;
        byte[] o = f.read(f.sOff[4] + patBase + id * 4, id + 1 < nPats ? 8 : 4);
        int a = Card.s32(o, 0), b = id + 1 < nPats ? Card.s32(o, 4) : f.sLen[4];
        byte[] d = f.read(f.sOff[4] + a, b - a);
        int[] p = {0};
        Pat t = new Pat();
        t.id = id;
        t.line = Card.vint(d, p);
        t.head = Card.str(d, p);
        int n = Card.vint(d, p);
        t.stops = new int[n];
        for (int i = 0; i < n; i++) t.stops[i] = Card.vint(d, p);
        int ng = Card.vint(d, p);
        t.cum = new int[ng][];
        t.start = new int[ng][];
        t.ds = new int[ng][];
        int bytes = 64 + n * 4;
        for (int g = 0; g < ng; g++) {
            int[] c = new int[n];
            for (int i = 1; i < n; i++) c[i] = c[i - 1] + Card.vint(d, p);
            int nt = Card.vint(d, p);
            int[] s = new int[nt], ds = new int[nt];
            int prev = 0;
            for (int k = 0; k < nt; k++) {
                prev += Card.vint(d, p);
                s[k] = prev;
                ds[k] = Card.vint(d, p);
            }
            t.cum[g] = c;
            t.start[g] = s;
            t.ds[g] = ds;
            bytes += n * 4 + nt * 8 + 32;
        }
        t.bytes = bytes;
        // the cache: least recently used out while over budget
        int budget = Math.max(120000, Math.min(1500000, (int) Math.min(64000000L, Runtime.getRuntime().totalMemory()) / 8));
        while (true) {
            int total = t.bytes, v = -1, free = -1;
            for (int i = 0; i < pc.length; i++) {
                if (pc[i] == null) {
                    if (free < 0) free = i;
                    continue;
                }
                total += pc[i].bytes;
                if (v < 0 || pc[i].use < pc[v].use) v = i;
            }
            if (free >= 0 && (total <= budget || v < 0)) {
                t.use = ++clock;
                pc[free] = t;
                return t;
            }
            if (v < 0) return t;
            pc[v] = null;
        }
    }

    void dropPats() {
        for (int i = 0; i < pc.length; i++) pc[i] = null;
        for (int i = 0; i < sBlock.length; i++) sBlock[i] = null;
        for (int i = 0; i < lBlock.length; i++) lBlock[i] = null;
        for (int i = 0; i < lineCache.length; i++) lineCache[i] = null;
    }

    // ---------- search ----------
    String[] nKeys;
    int[] nOffs;
    int nBlocks;

    void nameIndex() throws IOException {
        if (nKeys != null) return;
        int s = f.sOff[5];
        int hl = Card.s32(f.read(s, 4), 0);
        byte[] h = f.read(s + 4, hl);
        int[] p = {0};
        int n = Card.vint(h, p);
        String[] k = new String[n];
        int[] o = new int[n];
        for (int i = 0; i < n; i++) {
            k[i] = Card.str(h, p);
            o[i] = Card.s32(h, p[0]);
            p[0] += 4;
        }
        nBlocks = s + 4 + hl;
        nOffs = o;
        nKeys = k;
    }

    /** stations whose name (or a later part of it) starts with the normalized query; at most max, no repeats */
    Vector findStops(String q, int max) throws IOException {
        nameIndex();
        Vector v = new Vector();
        int lo = 0, hi = nKeys.length - 1, b = 0;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            if (nKeys[m].compareTo(q) < 0) {
                b = m;
                lo = m + 1;
            } else hi = m - 1;
        }
        int total = f.sLen[5] - (nBlocks - f.sOff[5]);
        boolean done = false;
        for (; b < nKeys.length && !done; b++) {
            int end = b + 1 < nOffs.length ? nOffs[b + 1] : total;
            byte[] d = f.read(nBlocks + nOffs[b], end - nOffs[b]);
            int[] p = {0};
            int n = Card.vint(d, p);
            for (int r = 0; r < n; r++) {
                String k = Card.str(d, p);
                int s = Card.vint(d, p);
                int c = k.compareTo(q);
                if (c < 0) continue;
                if (!k.startsWith(q)) {
                    done = true;
                    break;
                }
                Integer x = new Integer(s);
                if (!v.contains(x)) v.addElement(x);
                if (v.size() >= max) {
                    done = true;
                    break;
                }
            }
        }
        return v;
    }

    /** the station with this sign code, or -1 */
    int byCode(int code) throws IOException {
        int s = f.sOff[7];
        int n = Card.s32(f.read(s, 4), 0);
        int lo = 0, hi = n - 1;
        while (lo <= hi) {
            int m = (lo + hi) >> 1;
            byte[] r = f.read(s + 4 + m * 6, 6);
            int c = Card.s32(r, 0);
            if (c < code) lo = m + 1;
            else if (c > code) hi = m - 1;
            else return Card.u16(r, 4);
        }
        return -1;
    }

    // ---------- the grid ----------
    int gLat0, gLon0, gRows, gCols;
    boolean gridRead;

    /** stations within radius metres of the point, nearest first (at most max) */
    Vector near(int lat, int lon, int radius, int max) throws IOException {
        int s = f.sOff[6];
        if (!gridRead) {
            byte[] h = f.read(s, 12);
            gLat0 = Card.s32(h, 0);
            gLon0 = Card.s32(h, 4);
            gRows = Card.u16(h, 8);
            gCols = Card.u16(h, 10);
            gridRead = true;
        }
        int dr = radius / 1100 + 1, dc = radius / 940 + 1;
        int r0 = (lat - gLat0) / 10000, c0 = (lon - gLon0) / 10000;
        Vector ids = new Vector();
        int[] dist = new int[256];
        int n = 0;
        for (int r = r0 - dr; r <= r0 + dr; r++) {
            if (r < 0 || r >= gRows) continue;
            int ca = Math.max(0, c0 - dc), cb = Math.min(gCols - 1, c0 + dc);
            byte[] o = f.read(s + 12 + (r * gCols + ca) * 4, (cb - ca + 2) * 4);
            int a = Card.s32(o, 0), e = Card.s32(o, (cb - ca + 1) * 4);
            if (e <= a) continue;
            byte[] d = f.read(s + a, e - a);
            int[] p = {0};
            while (p[0] < d.length) {
                int id = Card.vint(d, p);
                coords();
                int dd = dist(lat, lon, lat(id), lon(id));
                if (dd > radius) continue;
                // insert sorted
                int at = n;
                while (at > 0 && dist[at - 1] > dd) at--;
                if (at >= max) continue;
                if (n == dist.length) continue;
                for (int k = Math.min(n, max - 1); k > at; k--) dist[k] = dist[k - 1];
                dist[at] = dd;
                ids.insertElementAt(new Integer(id), at);
                if (n < max) n++;
                if (ids.size() > max) ids.removeElementAt(ids.size() - 1);
            }
        }
        return ids;
    }

    // coordinates of all stations (about 11 m precision), for the planner's distance bound
    char[] cLat, cLon;

    void coords() throws IOException {
        if (cLat != null) return;
        byte[] d = f.read(f.sOff[8], nStops * 4);
        char[] a = new char[nStops], b = new char[nStops];
        for (int i = 0; i < nStops; i++) {
            a[i] = (char) Card.u16(d, i * 4);
            b[i] = (char) Card.u16(d, i * 4 + 2);
        }
        cLat = a;
        cLon = b;
    }

    int lat(int s) { return 29000000 + cLat[s] * 100; }

    int lon(int s) { return 34000000 + cLon[s] * 100; }

    static int dist(int lat1, int lon1, int lat2, int lon2) {
        float dy = (lat2 - lat1) * 0.11132f, dx = (lon2 - lon1) * 0.09491f;
        return (int) Math.sqrt(dx * dx + dy * dy);
    }
}
