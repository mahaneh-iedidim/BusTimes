package bs;

import java.io.IOException;
import java.util.Vector;

/**
 * Journey planning with RAPTOR (rounds of "one more vehicle"): round k holds the earliest arrival at each station
 * with k rides; between rounds the reached stations are joined by short walks. Only the stations actually reached
 * get a slot (the tables grow on demand), so a short trip needs little memory.
 */
final class Planner {
    static final int WALK_M_PER_MIN = 75, MAX_WALK = 700, TRANSFER_WALK = 350, MIN_CHANGE = 1;

    final Dat dat;
    volatile boolean cancel;
    int maxRounds = 3;   // rides (2 transfers)
    static final boolean DEBUG = false;

    Planner(Dat dat) {
        this.dat = dat;
    }

    static int walkMin(int metres) { return (metres * 5 / 4 + WALK_M_PER_MIN - 1) / WALK_M_PER_MIN; }

    // ---------- sparse station table ----------
    int cap, n;
    int[] sid, hash, best, prevBest, prevRound;
    int[][] arr, pPat, pTrip, pBoard, pFrom;   // [round][slot]; pTrip = group << 16 | trip; pBoard = pos << 8 | fromRound << 2 | dayShift + 1
    boolean[] markA, markB;

    /** small tables to start with; at most what the free memory allows (about 120 bytes a station) */
    void alloc() {
        n = 0;
        System.gc();
        maxSlots = (int) Math.min(40000L, Math.max(Runtime.getRuntime().freeMemory() / 240, Runtime.getRuntime().totalMemory() / 300));
        if (maxSlots < 1500) maxSlots = 1500;
        make(Math.min(2048, maxSlots));
    }

    void make(int c) {
        int R = maxRounds + 1;
        int[] s2 = new int[c], b2 = new int[c], pb2 = new int[c], pr2 = new int[c];
        int[][] a2 = new int[R][c], pp2 = new int[R][c], pt2 = new int[R][c], pbd2 = new int[R][c], pf2 = new int[R][c];
        boolean[] ma = new boolean[c], mb = new boolean[c];
        if (sid != null) {
            System.arraycopy(sid, 0, s2, 0, n);
            System.arraycopy(best, 0, b2, 0, n);
            System.arraycopy(prevBest, 0, pb2, 0, n);
            System.arraycopy(prevRound, 0, pr2, 0, n);
            System.arraycopy(markA, 0, ma, 0, n);
            System.arraycopy(markB, 0, mb, 0, n);
            for (int r = 0; r < R; r++) {
                System.arraycopy(arr[r], 0, a2[r], 0, n);
                System.arraycopy(pPat[r], 0, pp2[r], 0, n);
                System.arraycopy(pTrip[r], 0, pt2[r], 0, n);
                System.arraycopy(pBoard[r], 0, pbd2[r], 0, n);
                System.arraycopy(pFrom[r], 0, pf2[r], 0, n);
            }
        }
        sid = s2;
        best = b2;
        prevBest = pb2;
        prevRound = pr2;
        arr = a2;
        pPat = pp2;
        pTrip = pt2;
        pBoard = pbd2;
        pFrom = pf2;
        markA = ma;
        markB = mb;
        int h = 1;
        while (h < c * 2) h <<= 1;
        hash = new int[h];
        cap = c;
        for (int i = 0; i < n; i++) hash[-1 - find(sid[i])] = i + 1;
    }

    int find(int stop) {
        int m = hash.length - 1, h = (stop * 0x9E3779B1) >>> 9 & m;
        while (true) {
            int s = hash[h];
            if (s == 0) return -1 - h;
            if (sid[s - 1] == stop) return s - 1;
            h = (h + 1) & m;
        }
    }

    static final int INF = 0x3FFFFFFF;

    /** the slot of a station, created when missing */
    int maxSlots;

    int slot(int stop) {
        int s = find(stop);
        if (s >= 0) return s;
        if (n == cap) {
            if (cap >= maxSlots) return -1;
            try {
                make(Math.min(maxSlots, cap * 2));
            } catch (OutOfMemoryError e) {
                maxSlots = cap;
                return -1;
            }
            s = find(stop);
        }
        hash[-1 - s] = n + 1;
        int k = n++;
        sid[k] = stop;
        best[k] = INF;
        prevBest[k] = INF;
        prevRound[k] = -1;
        for (int r = 0; r <= maxRounds; r++) arr[r][k] = INF;
        markA[k] = markB[k] = false;
        return k;
    }

    // ---------- the result ----------
    static final class Leg {
        boolean walk;
        int fromStop = -1, toStop = -1;   // -1: the origin / destination point
        int dep, arr, metres;
        int pat, line, boardPos, alightPos, shift, g, tr;
        String head;
    }

    static final class Journey {
        Leg[] legs;
        int dep, arr, rides, walkMin;
    }

    int day, t0;
    int destLat, destLon;

    /** minutes that cannot be beaten from a station to the destination: straight line at 90 km/h */
    int bound(int stop) {
        return Dat.dist(dat.lat(stop), dat.lon(stop), destLat, destLon) / 1500;
    }
    int[] dStop, dWalk;    // destination stations and the walk from each
    int bestTarget;

    /**
     * earliest trip of pattern t at position i leaving at or after time tt (minutes from the query day's midnight):
     * trips of the day, of the night before (after 24:00) and of the next morning. The trip's day offset goes to
     * fShift: -1 the day before, 0 the day, 1 the next day.
     */
    int fg, ft, fdep, fShift;
    int dayPrev, dayNext;   // data days of the day before and after (the caller maps days outside the data)

    boolean earliest(Dat.Pat t, int i, int tt, int d) {
        int bestDep = INF;
        for (int g = 0; g < t.cum.length; g++) {
            int c = t.cum[g][i];
            int[] st = t.start[g], ds = t.ds[g];
            for (int off = -1; off <= 1; off++) {
                int dd = off == 0 ? d : off < 0 ? dayPrev : dayNext, want = tt - c - off * 1440;
                // binary search the first start >= want
                int lo = 0, hi = st.length;
                while (lo < hi) {
                    int m = (lo + hi) >> 1;
                    if (st[m] < want) lo = m + 1;
                    else hi = m;
                }
                for (int k = lo, steps = 0; k < st.length && steps < 400; k++, steps++) {
                    int dep = st[k] + c + off * 1440;
                    if (dep >= bestDep) break;
                    if (dat.on(ds[k], dd)) {
                        bestDep = dep;
                        fg = g;
                        ft = k;
                        fShift = off;
                        break;
                    }
                }
            }
        }
        fdep = bestDep;
        return bestDep < INF;
    }

    /**
     * journeys from the origin stations (with walking minutes) to the destination stations, leaving at minute
     * time of day d; the best for each number of rides (fewer rides only when not slower)
     */
    Vector plan(int[] oStop, int[] oWalk, int[] destStop, int[] destWalk, int d, int dPrev, int dNext, int time) throws IOException {
        cancel = false;
        day = d;
        dayPrev = dPrev;
        dayNext = dNext;
        t0 = time;
        dStop = destStop;
        dWalk = destWalk;
        sid = null;
        alloc();
        dat.coords();
        bestTarget = INF;
        for (int i = 0; i < oStop.length; i++) {
            int s = slot(oStop[i]);
            if (s < 0) continue;
            int a = time + oWalk[i];
            if (a < arr[0][s]) {
                arr[0][s] = a;
                best[s] = a;
                pPat[0][s] = -2;   // walked from the origin
                pFrom[0][s] = oWalk[i];
                markA[s] = true;
            }
        }
        int[] targetArr = new int[maxRounds + 1];
        for (int k = 0; k <= maxRounds; k++) targetArr[k] = INF;
        int[] qp = new int[256], qpos = new int[256], qhash = new int[512];
        for (int k = 1; k <= maxRounds && !cancel; k++) {
            // what the previous rounds reached, for boarding
            for (int i = 0; i < n; i++) {
                if (arr[k - 1][i] < prevBest[i]) {
                    prevBest[i] = arr[k - 1][i];
                    prevRound[i] = k - 1;
                }
            }
            // patterns through the marked stations, from their earliest marked position
            int qn = 0;
            for (int i = 0; i < qhash.length; i++) qhash[i] = 0;
            for (int i = 0; i < n; i++) {
                if (!markA[i]) continue;
                markA[i] = false;
                // nothing from here can still beat the best arrival
                if (bestTarget < INF && prevBest[i] + bound(sid[i]) >= bestTarget) continue;
                int[] ln = dat.links(sid[i]);
                for (int r = 0; r < ln[0]; r++) {
                    int p = ln[1 + 2 * r], pos = ln[2 + 2 * r];
                    int m = qhash.length - 1, h = (p * 0x9E3779B1) >>> 11 & m, found = -1;
                    while (qhash[h] != 0) {
                        if (qp[qhash[h] - 1] == p) {
                            found = qhash[h] - 1;
                            break;
                        }
                        h = (h + 1) & m;
                    }
                    if (found >= 0) {
                        if (pos < qpos[found]) qpos[found] = pos;
                        continue;
                    }
                    if (qn == qp.length) {
                        int[] a = new int[qn * 2], b = new int[qn * 2];
                        System.arraycopy(qp, 0, a, 0, qn);
                        System.arraycopy(qpos, 0, b, 0, qn);
                        qp = a;
                        qpos = b;
                        qhash = new int[qn * 4];
                        for (int j = 0; j < qn; j++) {
                            int hh = (qp[j] * 0x9E3779B1) >>> 11 & (qhash.length - 1);
                            while (qhash[hh] != 0) hh = (hh + 1) & (qhash.length - 1);
                            qhash[hh] = j + 1;
                        }
                        m = qhash.length - 1;
                        h = (p * 0x9E3779B1) >>> 11 & m;
                        while (qhash[h] != 0) h = (h + 1) & m;
                    }
                    qp[qn] = p;
                    qpos[qn] = pos;
                    qhash[h] = qn + 1;
                    qn++;
                }
            }
            // ride along each pattern
            for (int q = 0; q < qn && !cancel; q++) {
                Dat.Pat t = dat.pat(qp[q]);
                int g = -1, tr = 0, boardPos = 0, fromSlot = 0, fromRound = 0, base = 0, shift = 0;
                for (int i = qpos[q]; i < t.stops.length; i++) {
                    int stop = t.stops[i];
                    if (g >= 0) {
                        int ta = base + t.cum[g][i];
                        if (ta < bestTarget && (bestTarget == INF || ta + bound(stop) < bestTarget)) {
                            int s = slot(stop);
                            if (s >= 0 && ta < best[s]) {
                                best[s] = ta;
                                arr[k][s] = ta;
                                pPat[k][s] = t.id;
                                pTrip[k][s] = g << 16 | tr;
                                pBoard[k][s] = boardPos << 8 | fromRound << 2 | (shift + 1);
                                pFrom[k][s] = fromSlot;
                                markB[s] = true;
                            }
                        }
                    }
                    // board here (or an earlier trip) when this station was reached before
                    int s = find(stop);
                    if (s >= 0 && prevBest[s] < INF && i < t.stops.length - 1) {
                        int tt = prevBest[s] + (prevRound[s] > 0 ? MIN_CHANGE : 0);
                        int curDep = g >= 0 ? base + t.cum[g][i] : INF;
                        if (tt <= curDep && earliest(t, i, tt, day) && fdep < curDep) {
                            g = fg;
                            tr = ft;
                            shift = fShift;
                            base = t.start[g][tr] + shift * 1440;
                            boardPos = i;
                            fromSlot = s;
                            fromRound = prevRound[s];
                        }
                    }
                }
            }
            // short walks between stations reached in this round
            int nn = n;
            for (int i = 0; i < nn && !cancel; i++) {
                if (!markB[i] || pPat[k][i] < 0) continue;
                int[] ln = dat.links(sid[i]);
                int wo = 2 + 2 * ln[0];
                for (int j = 0; j < ln[wo - 1]; j++) {
                    int other = ln[wo + 2 * j];
                    int w = ln[wo + 1 + 2 * j];
                    int ta = arr[k][i] + w;
                    if (ta >= bestTarget || (bestTarget < INF && ta + bound(other) >= bestTarget)) continue;
                    int s = slot(other);
                    if (s >= 0 && ta < best[s]) {
                        best[s] = ta;
                        arr[k][s] = ta;
                        pPat[k][s] = -1;   // walked from another station of this round
                        pFrom[k][s] = i;
                        pBoard[k][s] = w;
                        markB[s] = true;
                    }
                }
            }
            // the destination
            for (int j = 0; j < dStop.length; j++) {
                int s = find(dStop[j]);
                if (s < 0 || arr[k][s] >= INF) continue;
                int a = arr[k][s] + dWalk[j];
                if (a < targetArr[k]) targetArr[k] = a;
                if (a < bestTarget) bestTarget = a;
            }
            if (DEBUG) {
                StringBuffer db = new StringBuffer("round ");
                db.append(k);
                db.append(" slots=");
                db.append(n);
                db.append(" patterns=");
                db.append(qn);
                db.append(" bestTarget=");
                db.append(bestTarget);
                db.append(" origins=");
                db.append(oStop.length);
                db.append(" dests=");
                db.append(dStop.length);
                db.append(" day=");
                db.append(day);
                db.append(" t=");
                db.append(t0);
                db.append(" ms=");
                db.append((int) (System.currentTimeMillis() % 1000000));
                db.append(" patLoads=");
                db.append(dat.patLoads);
                db.append(" stopLoads=");
                db.append(dat.stopLoads);
                db.append(" cap=");
                db.append(cap);
                db.append(" usedKB=");
                db.append((int) ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024));
                System.out.println(db.toString());
            }
            boolean any = false;
            for (int i = 0; i < n; i++) {
                markA[i] = markB[i];
                markB[i] = false;
                if (markA[i]) any = true;
            }
            if (!any) break;
        }
        // one journey per number of rides that improves on fewer rides
        Vector res = new Vector();
        int prev = INF;
        for (int k = 1; k <= maxRounds; k++) {
            if (targetArr[k] >= prev) continue;
            Journey j = build(k, targetArr[k]);
            if (j != null) {
                res.addElement(j);
                prev = targetArr[k];
            }
        }
        return res;
    }

    Journey build(int k, int target) throws IOException {
        // the destination station of round k that gives the target time
        int s = -1, dw = 0;
        for (int j = 0; j < dStop.length; j++) {
            int x = find(dStop[j]);
            if (x >= 0 && arr[k][x] < INF && arr[k][x] + dWalk[j] == target) {
                s = x;
                dw = dWalk[j];
                break;
            }
        }
        if (s < 0) return null;
        Vector legs = new Vector();
        Leg last = new Leg();
        last.walk = true;
        last.fromStop = sid[s];
        last.dep = arr[k][s];
        last.arr = target;
        legs.addElement(last);
        int r = k, guard = 0;
        while (guard++ < 40) {
            int p = pPat[r][s];
            if (p == -2) {
                Leg w = new Leg();
                w.walk = true;
                w.toStop = sid[s];
                w.arr = arr[r][s];
                w.dep = w.arr - pFrom[r][s];
                legs.insertElementAt(w, 0);
                break;
            }
            if (p == -1) {
                int from = pFrom[r][s];
                Leg w = new Leg();
                w.walk = true;
                w.fromStop = sid[from];
                w.toStop = sid[s];
                w.arr = arr[r][s];
                w.dep = arr[r][from];
                legs.insertElementAt(w, 0);
                s = from;
                continue;
            }
            Dat.Pat t = dat.pat(p);
            int g = pTrip[r][s] >>> 16, tr = pTrip[r][s] & 0xFFFF;
            int pb = pBoard[r][s];
            int bp = pb >>> 8, fr = pb >> 2 & 63, shift = (pb & 3) - 1;
            int base = t.start[g][tr] + shift * 1440;
            Leg ride = new Leg();
            ride.pat = p;
            ride.line = t.line;
            ride.head = t.head;
            ride.boardPos = bp;
            ride.fromStop = t.stops[bp];
            ride.toStop = sid[s];
            ride.shift = shift;
            ride.g = g;
            ride.tr = tr;
            ride.dep = base + t.cum[g][bp];
            ride.arr = arr[r][s];
            for (int i = bp; i < t.stops.length; i++) if (t.stops[i] == sid[s] && base + t.cum[g][i] == ride.arr) {
                ride.alightPos = i;
                break;
            }
            legs.insertElementAt(ride, 0);
            s = pFrom[r][s];
            r = fr;
        }
        Journey j = new Journey();
        j.legs = new Leg[legs.size()];
        legs.copyInto(j.legs);
        j.dep = j.legs[0].dep;
        j.arr = target;
        for (int i = 0; i < j.legs.length; i++) {
            if (j.legs[i].walk) j.walkMin += j.legs[i].arr - j.legs[i].dep;
            else j.rides++;
        }
        // leave as late as the first ride allows
        if (j.legs.length > 1 && j.legs[0].walk && !j.legs[1].walk) {
            int w = j.legs[0].arr - j.legs[0].dep;
            j.legs[0].arr = j.legs[1].dep;
            j.legs[0].dep = j.legs[1].dep - w;
            j.dep = j.legs[0].dep;
        }
        return j;
    }

    void free() {
        sid = hash = best = prevBest = prevRound = null;
        arr = pPat = pTrip = pBoard = pFrom = null;
        markA = markB = null;
    }
}
