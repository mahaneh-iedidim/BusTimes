package bs;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.rms.RecordStore;

/**
 * לוחות זמנים לתחבורה ציבורית: stations and their next departures, lines and their stops, full timetables, the
 * journey planner, nearby stations and favourites, from bus.dat on the memory card (israel.map of the maps app,
 * when present, adds address search). Screens are lists kept on a stack; slow work runs in a background thread.
 */
public class Ui extends Canvas implements Runnable {
    Bus app;
    int W = 240, H = 320;
    BFont uR, uB, uS, fTitle;

    // the look: white, orange and black
    static final int ORANGE = 0xFF6A13, ORANGE_D = 0xD9530A, BLACK = 0x161616, INK = 0x1C1C1C, GRAY = 0x6E6E6E,
        RULE = 0xE6E6E6, BG = 0xFFFFFF, SEL = 0xFFEBDD, SECTION = 0xF3F3F3, PALE = 0xFFD9C2;

    Card card, mapCard;
    Dat dat;
    Planner planner;
    Places places;
    String url = "", mapUrl = "";

    static boolean EN;

    static String L(String he, String en) { return EN ? en : he; }

    Ui(Bus app) {
        this.app = app;
        setFullScreenMode(true);
        EN = read("/lang") != null;
        uR = font("ur", 8);
        uB = font("ub", 8);
        uS = font("us", 8);
        fTitle = font("tt", 2);
        loadPrefs();
        gpsApi = hasClass("javax.microedition.location.LocationProvider");
        new Thread(new Runnable() {
            public void run() { findFiles(); }
        }).start();
    }

    static boolean hasClass(String n) {
        try {
            Class.forName(n);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    byte[] read(String p) {
        try {
            InputStream in = getClass().getResourceAsStream(p);
            if (in == null) return null;
            ByteArrayOutputStream o = new ByteArrayOutputStream(4096);
            byte[] b = new byte[1024];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    BFont font(String name, int colors) { return new BFont(read(cat("/f/", name)), colors); }

    static String cat(String a, String b) {
        StringBuffer s = new StringBuffer(a);
        s.append(b);
        return s.toString();
    }

    static String cat3(String a, String b, String c) {
        StringBuffer s = new StringBuffer(a);
        s.append(b);
        s.append(c);
        return s.toString();
    }

    static int now() { return (int) (System.currentTimeMillis() & 0x3FFFFFFF); }

    // ---------- dates and times ----------
    /** days since 1970-01-01 of a civil date */
    static int civil(int y, int m, int d) {
        if (m <= 2) y--;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** {year, month, day} of days since 1970 */
    static int[] date(int z) {
        z += 719468;
        int era = (z >= 0 ? z : z - 146096) / 146097;
        int doe = z - era * 146097;
        int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        int y = yoe + era * 400;
        int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        int mp = (5 * doy + 2) / 153;
        int d = doy - (153 * mp + 2) / 5 + 1;
        int m = mp < 10 ? mp + 3 : mp - 9;
        return new int[] {m <= 2 ? y + 1 : y, m, d};
    }

    int firstDay;         // days since 1970 of the data's first date
    int todayIx, nowMin;  // today (index into the data, may be outside it) and the minute of the day
    boolean whenNow = true;
    int whenDay, whenMin; // the chosen day (index) and minute

    void clock() {
        Calendar c = Calendar.getInstance();
        int d = civil(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
        todayIx = d - firstDay;
        nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        if (whenNow) {
            whenDay = todayIx;
            whenMin = nowMin;
        }
    }

    /** the data day used for a day: itself, or the same weekday inside the data when out of it */
    int dataDay(int ix) {
        if (dat == null || (ix >= 0 && ix < dat.days)) return ix;
        int wd = ((firstDay + ix) % 7 + 7 + 4) % 7;
        for (int k = 0; k < 7 && k < dat.days; k++) if (((firstDay + k) % 7 + 7 + 4) % 7 == wd) return k + (ix < 0 ? 0 : Math.max(0, (dat.days - 1 - k) / 7 * 7));
        return 0;
    }

    boolean outOfData(int ix) { return dat != null && (ix < 0 || ix >= dat.days); }

    static final String[] WD_HE = {"ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת"};
    static final String[] WD_EN = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};

    String dayName(int ix) {
        if (ix == todayIx) return L("היום", "Today");
        if (ix == todayIx + 1) return L("מחר", "Tomorrow");
        int z = firstDay + ix;
        int wd = ((z % 7) + 7 + 4) % 7;
        int[] ymd = date(z);
        StringBuffer b = new StringBuffer();
        if (!EN) b.append("יום ");
        b.append(EN ? WD_EN[wd] : WD_HE[wd]);
        b.append(' ');
        b.append(ymd[2]);
        b.append('.');
        b.append(ymd[1]);
        return b.toString();
    }

    static String hm(int m) {
        m = ((m % 1440) + 1440) % 1440;
        StringBuffer b = new StringBuffer();
        if (m / 60 < 10) b.append('0');
        b.append(m / 60);
        b.append(':');
        if (m % 60 < 10) b.append('0');
        b.append(m % 60);
        return b.toString();
    }

    /** "עכשיו", "7 דק'", or the clock time for later */
    String inMin(int dep, int from) {
        int d = dep - from;
        if (d <= 0) return L("עכשיו", "Now");
        if (d < 60) {
            StringBuffer b = new StringBuffer();
            b.append(d);
            b.append(L(" דק'", " min"));
            return b.toString();
        }
        return hm(dep);
    }

    static String meters(int m) {
        StringBuffer b = new StringBuffer();
        if (m < 1000) {
            b.append(m / 10 * 10);
            b.append(L(" מ'", " m"));
        } else {
            b.append(m / 1000);
            b.append('.');
            b.append(m / 100 % 10);
            b.append(L(" ק\"מ", " km"));
        }
        return b.toString();
    }

    String duration(int min) {
        StringBuffer b = new StringBuffer();
        if (min < 60) {
            b.append(min);
            b.append(L(" דק'", " min"));
        } else {
            b.append(min / 60);
            b.append(L(" שע' ", " h "));
            b.append(min % 60);
            b.append(L(" דק'", " min"));
        }
        return b.toString();
    }

    // ---------- bidi text ----------
    final Bidi bd = new Bidi();
    private byte[] tlv = new byte[128];
    private int[] tord = new int[128];

    int textW(BFont f, String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += f.w(s.charAt(i));
        return w;
    }

    void text(Graphics g, BFont f, int color, String s, int x, int y, int anchor) {
        int n = s.length();
        if (n == 0) return;
        char[] t = s.toCharArray();
        for (int i = 0; i < n; i++) if (t[i] == 0xA0) t[i] = ' ';
        for (int i = 1; i < n; i++) {
            char c = t[i], p = t[i - 1];
            if ((c == '\'' || c == '"') && p >= 0x5D0 && p <= 0x5EA) {
                char g2 = c == '\'' ? (char) 0x5F3 : (char) 0x5F4;
                if (f.has(g2)) t[i] = g2;
            }
        }
        if (tlv.length < n) {
            tlv = new byte[n + 32];
            tord = new int[n + 32];
        }
        int para = bd.resolve(t, n, 0, EN ? 0 : 1, tlv);
        int m = bd.reorder(t, tlv, 0, n, para, tord);
        int w = 0;
        for (int i = 0; i < n; i++) w += f.w(t[i]);
        int xl = (anchor & Graphics.RIGHT) != 0 ? x - w : (anchor & Graphics.HCENTER) != 0 ? x - w / 2 : x;
        for (int k = 0; k < m; k++) {
            int i = tord[k];
            char c = t[i];
            if ((tlv[i] & 1) != 0) c = Bidi.mirror(c);
            int cw = f.w(c);
            f.draw(g, c, xl, y, color);
            xl += cw;
        }
    }

    String fit(BFont f, String s, int maxW) {
        if (s == null) return "";
        if (textW(f, s) <= maxW) return s;
        int dots = textW(f, "..");
        int n = s.length();
        while (n > 1 && textW(f, s.substring(0, n)) + dots > maxW) n--;
        return cat(s.substring(0, n).trim(), "..");
    }

    Vector wrap(BFont f, String s, int w) {
        Vector v = new Vector();
        int i = 0, n = s.length();
        while (i < n) {
            int nl = s.indexOf('\n', i);
            if (nl < 0) nl = n;
            String para = s.substring(i, nl);
            if (para.length() == 0) v.addElement("");
            while (para.length() > 0) {
                int cut = para.length();
                while (cut > 0 && textW(f, para.substring(0, cut)) > w) {
                    int sp = para.lastIndexOf(' ', cut - 1);
                    cut = sp > 0 ? sp : cut - 1;
                }
                if (cut <= 0) cut = 1;
                v.addElement(para.substring(0, cut).trim());
                para = para.substring(cut).trim();
            }
            i = nl + 1;
        }
        return v;
    }

    static int mid(BFont f, int y, int h) { return y + (h - f.h) / 2; }

    int barH() { return uR.h + 9; }

    /** the start side (right in Hebrew) and the end side */
    int xs(int m) { return EN ? m : W - m; }

    int as() { return Graphics.TOP | (EN ? Graphics.LEFT : Graphics.RIGHT); }

    int xe(int m) { return EN ? W - m : m; }

    int ae() { return Graphics.TOP | (EN ? Graphics.RIGHT : Graphics.LEFT); }

    // ---------- operator colours ----------
    static int agencyColor(String a, int type) {
        if (type == 0) return 0xC8102E;
        if (type == 2) return 0x1F3A68;
        if (a == null) return 0x3A3A3A;
        if (a.startsWith("אגד") || a.startsWith("דרך אגד")) return 0x2E9E4F;
        if (a.startsWith("דן")) return 0x1E5BB5;
        if (a.startsWith("מטרופולין")) return 0x0096A6;
        if (a.startsWith("סופרבוס")) return 0xD7262E;
        if (a.startsWith("קווים")) return 0x7B3F98;
        if (a.indexOf("אפיקים") >= 0) return 0xB86A00;
        if (a.startsWith("נתיב")) return 0x5C4AA0;
        if (a.startsWith("אקסטרה")) return 0xC2185B;
        if (a.startsWith("תנופה")) return 0x00838F;
        if (a.startsWith("גלים")) return 0x0072BC;
        if (a.startsWith("בית שמש")) return 0x388E3C;
        if (a.startsWith("כרמלית") || a.startsWith("כבל")) return 0x5D4037;
        if (a.startsWith("רכבת")) return 0x1F3A68;
        return 0x3A3A3A;
    }

    String agency(int i) { return dat != null && i >= 0 && i < dat.agencies.length ? dat.agencies[i] : ""; }

    // ---------- preferences, favourites ----------
    int maxTransfers = 2, maxWalk = 700;
    // favourites: kind 0 station (id), 1 line (pattern), 2 place (lat, lon)
    int favN;
    int[] favKind = new int[60], favA = new int[60], favLat = new int[60], favLon = new int[60];
    String[] favName = new String[60];
    int myLat, myLon;         // the last known location (GPS or chosen)
    String myName = "";
    boolean myKnown;

    void loadPrefs() {
        try {
            RecordStore rs = RecordStore.openRecordStore("bus", true);
            if (rs.getNumRecords() >= 1) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                url = in.readUTF();
                mapUrl = in.readUTF();
                maxTransfers = in.readInt();
                maxWalk = in.readInt();
                myKnown = in.readBoolean();
                myLat = in.readInt();
                myLon = in.readInt();
                myName = in.readUTF();
                favN = in.readInt();
                for (int i = 0; i < favN && i < favName.length; i++) {
                    favKind[i] = in.readInt();
                    favA[i] = in.readInt();
                    favLat[i] = in.readInt();
                    favLon[i] = in.readInt();
                    favName[i] = in.readUTF();
                }
            }
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    void save() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF(url);
            o.writeUTF(mapUrl);
            o.writeInt(maxTransfers);
            o.writeInt(maxWalk);
            o.writeBoolean(myKnown);
            o.writeInt(myLat);
            o.writeInt(myLon);
            o.writeUTF(myName);
            o.writeInt(favN);
            for (int i = 0; i < favN; i++) {
                o.writeInt(favKind[i]);
                o.writeInt(favA[i]);
                o.writeInt(favLat[i]);
                o.writeInt(favLon[i]);
                o.writeUTF(favName[i]);
            }
            byte[] a = bo.toByteArray();
            RecordStore rs = RecordStore.openRecordStore("bus", true);
            if (rs.getNumRecords() != 1) {
                rs.closeRecordStore();
                try {
                    RecordStore.deleteRecordStore("bus");
                } catch (Exception e) {
                }
                rs = RecordStore.openRecordStore("bus", true);
                rs.addRecord(a, 0, a.length);
            } else rs.setRecord(1, a, 0, a.length);
            rs.closeRecordStore();
        } catch (Exception e) {
        }
    }

    int favIndex(int kind, int a, int lat, int lon) {
        for (int i = 0; i < favN; i++) {
            if (favKind[i] != kind) continue;
            if (kind == 2 ? favLat[i] == lat && favLon[i] == lon : favA[i] == a) return i;
        }
        return -1;
    }

    void toggleFav(int kind, int a, int lat, int lon, String name) {
        int i = favIndex(kind, a, lat, lon);
        if (i >= 0) {
            for (int k = i; k + 1 < favN; k++) {
                favKind[k] = favKind[k + 1];
                favA[k] = favA[k + 1];
                favLat[k] = favLat[k + 1];
                favLon[k] = favLon[k + 1];
                favName[k] = favName[k + 1];
            }
            favN--;
            toast(L("הוסר מהמועדפים", "Removed from favourites"));
        } else {
            if (favN == favName.length) {
                msg(L("מועדפים", "Favourites"), L("הרשימה מלאה (60).", "The list is full (60)."));
                return;
            }
            favKind[favN] = kind;
            favA[favN] = a;
            favLat[favN] = lat;
            favLon[favN] = lon;
            favName[favN] = name;
            favN++;
            toast(L("נוסף למועדפים", "Added to favourites"));
        }
        save();
    }

    void close() {
        if (gps != null) gps.stop();
        if (card != null) card.close();
        if (mapCard != null) mapCard.close();
    }

    // ---------- the data files ----------
    static final String[] PLACES = {"", "Bus/", "bus/", "Maps/", "maps/", "jvm/", "Others/", "Other/", "Java/", "Downloads/", "Download/"};
    boolean ready;
    String startText;
    String noFileWhy;

    String find(String name, String saved) {
        if (saved.length() > 0 && Card.exists(saved)) return saved;
        Vector roots = Card.roots();
        if (roots.size() == 0) roots.addElement("E:/");
        for (int k = 0; k < PLACES.length; k++) {
            for (int r = 0; r < roots.size(); r++) {
                StringBuffer u = new StringBuffer("file:///");
                u.append((String) roots.elementAt(r));
                u.append(PLACES[k]);
                u.append(name);
                String s = u.toString();
                if (Card.exists(s)) return s;
            }
        }
        return null;
    }

    void findFiles() {
        startText = L("מחפש את קובץ הנתונים...", "Looking for the data file...");
        safeRepaint();
        if (!Card.available()) {
            noFile(L("הטלפון לא תומך בגישה לקבצים (JSR-75), ולכן אי אפשר לקרוא את קובץ הנתונים.", "This phone has no file access (JSR-75), so the data file cannot be read."));
            return;
        }
        String u = find("bus.dat", url);
        if (u == null || !open(u)) {
            noFile(null);
            return;
        }
        String m = find("israel.map", mapUrl);
        if (m != null) {
            try {
                mapCard = new Card(m, "ILMP");
                places = new Places(mapCard);
                mapUrl = m;
            } catch (Throwable t) {
                mapCard = null;
            }
        }
        save();
    }

    boolean open(String u) {
        try {
            Card c = new Card(u, "ILBS");
            Dat d = new Dat(c);
            if (card != null) card.close();
            card = c;
            dat = d;
            planner = new Planner(d);
            int fd = d.firstDate;
            firstDay = civil(fd / 10000, fd / 100 % 100, fd % 100);
            url = u;
            clock();
            ready = true;
            stack.removeAllElements();
            show(home());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    void noFile(String why) {
        noFileWhy = why;
        ready = false;
        startText = null;
        safeRepaint();
    }

    // ---------- screens ----------
    static final class Row {
        String a, b, end, endSub, badge;
        int badgeCol, act, i1, i2, i3, i4, kind, sh;   // kind: 0 row, 1 section title, 2 timeline stop, 3 journey
        int[] badges;     // journey: line badge colours, with names in names
        String[] names;
        Object obj;
        boolean on;       // timeline: the stop of interest
    }

    static final class Scr {
        int kind, sel, top;
        String title, sub, note;
        Vector rows = new Vector();
        String softL, softR;
        int stop = -1, pat = -1, pos = -1, line = -1, g, tr, day, base;
        boolean before;
        Object data;
    }

    static final int K_HOME = 1, K_STATION = 2, K_TRIP = 3, K_LINES = 4, K_LINE = 5, K_TIMES = 6, K_STOPS = 7, K_NEAR = 8,
        K_PLAN = 9, K_JOURNEYS = 10, K_JOURNEY = 11, K_MENU = 12, K_HELP = 13, K_PICK = 14, K_DIRS = 15, K_SET = 16,
        K_ADDR = 17, K_BROWSE = 18, K_FAVS = 19;
    final Vector stack = new Vector();
    Scr cur;

    Row row(Scr s, String a, String b, int act) {
        Row r = new Row();
        r.a = a;
        r.b = b;
        r.act = act;
        s.rows.addElement(r);
        return r;
    }

    Row section(Scr s, String a) {
        Row r = row(s, a, null, 0);
        r.kind = 1;
        return r;
    }

    void show(Scr s) {
        if (s.softL == null) s.softL = L("בחר", "Select");
        if (s.softR == null) s.softR = L("חזרה", "Back");
        // the first selectable row
        while (s.sel < s.rows.size() && ((Row) s.rows.elementAt(s.sel)).kind == 1) s.sel++;
        stack.addElement(s);
        cur = s;
        ov = O_NONE;
        safeRepaint();
    }

    void replace(Scr s) {
        if (stack.size() > 0) stack.removeElementAt(stack.size() - 1);
        show(s);
    }

    void back() {
        if (stack.size() <= 1) {
            save();
            app.quit();
            return;
        }
        stack.removeElementAt(stack.size() - 1);
        cur = (Scr) stack.lastElement();
        if (cur.kind == K_HOME) {
            clock();
            replace(home());
            return;
        }
        if (cur.kind == K_PLAN) {
            replace(planForm());
            return;
        }
        safeRepaint();
    }

    // ----- home
    static final int A_PLAN = 1, A_NEAR = 2, A_LINE = 3, A_STOP = 4, A_SET = 5, A_HELP = 6, A_FAV = 7, A_EXIT = 8;

    Scr home() {
        Scr s = new Scr();
        s.kind = K_HOME;
        s.title = L("תחבורה ציבורית", "Public Transport");
        s.sub = cat3(dayName(whenDay), ", ", hm(whenMin));
        if (outOfData(todayIx)) s.note = L("הנתונים לא בתוקף להיום: מוצגים לוחות של יום דומה. כדאי לעדכן את bus.dat.", "The data does not cover today: times of a similar day are shown. Update bus.dat.");
        else if (dat.days - todayIx <= 5) s.note = L("תוקף הנתונים מסתיים בעוד כמה ימים. כדאי לעדכן את bus.dat.", "The data expires in a few days. Update bus.dat.");
        row(s, L("תכנון מסלול", "Plan a journey"), L("מנקודה לנקודה, עם החלפות", "From place to place, with transfers"), A_PLAN);
        row(s, L("תחנות קרובות", "Nearby stations"), myKnown ? cat(L("ליד ", "Near "), myName) : L("לפי GPS או מקום שתבחרו", "By GPS or a place you choose"), A_NEAR);
        row(s, L("חיפוש קו", "Find a line"), L("לפי מספר הקו", "By line number"), A_LINE);
        row(s, L("חיפוש תחנה", "Find a station"), L("לפי שם או מספר התחנה שעל השלט", "By name or the number on the sign"), A_STOP);
        if (favN > 0) {
            section(s, L("מועדפים", "Favourites"));
            for (int i = 0; i < favN; i++) {
                Row r = row(s, favName[i], favKind[i] == 0 ? L("תחנה", "Station") : favKind[i] == 1 ? L("קו", "Line") : L("מקום", "Place"), A_FAV);
                r.i1 = i;
                if (favKind[i] == 1) {
                    int sp = favName[i].indexOf(' ');
                    r.badge = sp > 0 ? favName[i].substring(0, sp) : favName[i];
                    r.badgeCol = favLat[i];
                    r.a = sp > 0 ? favName[i].substring(sp + 1) : favName[i];
                }
            }
        }
        section(s, "");
        row(s, L("הגדרות", "Settings"), null, A_SET);
        row(s, L("עזרה ומידע", "Help and information"), null, A_HELP);
        s.softR = L("יציאה", "Exit");
        return s;
    }

    // ----- a station and its next departures
    /** departures from the station: one row per line and direction, soonest first */
    Scr station(int stopId) throws java.io.IOException {
        clock();
        Dat.Stop st = dat.stop(stopId);
        Scr s = new Scr();
        s.kind = K_STATION;
        s.stop = stopId;
        s.title = st.name;
        StringBuffer sub = new StringBuffer();
        if (st.code > 0) {
            sub.append(L("תחנה ", "Station "));
            sub.append(st.code);
        }
        if (st.city.length() > 0) {
            if (sub.length() > 0) sub.append(" · ");
            sub.append(st.city);
        }
        s.sub = sub.toString();
        int day = whenDay, t = whenMin;
        if (!whenNow || outOfData(day)) {
            StringBuffer n = new StringBuffer(L("זמנים ל", "Times for "));
            n.append(dayName(day));
            n.append(", ");
            n.append(hm(t));
            if (outOfData(day)) n.append(L(" (לפי יום דומה)", " (a similar day)"));
            s.note = n.toString();
        }
        int dd = dataDay(day);
        Vector later = new Vector();
        for (int r = 0; r < st.refP.length; r++) {
            Dat.Pat p = dat.pat(st.refP[r]);
            int pos = st.refPos[r];
            if (pos >= p.stops.length - 1) continue;   // the last stop: arrivals only
            int[] times = new int[3];
            int[] tg = new int[3], tt = new int[3], tb = new int[3];
            int n = next(p, pos, dd, t, times, tg, tt, tb);
            Dat.Line l = dat.lineC(p.line);
            Row row = new Row();
            row.badge = l.num;
            row.badgeCol = agencyColor(agency(l.agency), l.type);
            row.a = p.head.length() > 0 ? p.head : l.to;
            row.i1 = p.id;
            row.i2 = pos;
            row.act = 1;
            if (n == 0) {
                row.b = cat(agency(l.agency), L(" · אין עוד נסיעות היום", " · no more trips today"));
                row.end = "—";
                row.i3 = Integer.MAX_VALUE;
                later.addElement(row);
                continue;
            }
            row.i3 = times[0];
            row.i4 = tg[0] << 16 | tt[0];
            row.sh = tb[0];
            row.end = inMin(times[0], t);
            row.endSub = hm(times[0]);
            StringBuffer b = new StringBuffer(agency(l.agency));
            for (int k = 1; k < n; k++) {
                b.append(k == 1 ? " · " : ", ");
                b.append(hm(times[k]));
            }
            row.b = b.toString();
            // sorted insert by the next departure
            int at = 0;
            while (at < s.rows.size() && ((Row) s.rows.elementAt(at)).i3 <= row.i3) at++;
            s.rows.insertElementAt(row, at);
        }
        for (int i = 0; i < later.size(); i++) s.rows.addElement(later.elementAt(i));
        if (s.rows.size() == 0) row(s, L("אין קווים בתחנה הזו", "No lines at this station"), null, 0).kind = 1;
        s.softL = L("אפשרויות", "Options");
        myLat = st.lat;
        myLon = st.lon;
        myName = st.name;
        myKnown = true;
        return s;
    }

    /**
     * the next departures of pattern p at position pos from minute t of data day d (also trips of the previous
     * night and of the next morning); fills up to times.length, returns the count
     */
    int next(Dat.Pat p, int pos, int d, int t, int[] times, int[] tg, int[] tt, int[] tb) {
        int n = 0;
        for (int g = 0; g < p.cum.length; g++) {
            int c = p.cum[g][pos];
            int[] st = p.start[g], ds = p.ds[g];
            for (int k = 0; k < st.length; k++) {
                int dep = st[k] + c;
                // today, last night (after midnight) and tomorrow early
                for (int shift = -1; shift <= 1; shift++) {
                    int dd = d + shift, m = dep + shift * 1440;
                    if (m < t || m > t + 1440) continue;
                    if (!dat.on(ds[k], dd < 0 ? dataDay(dd) : dd >= dat.days ? dataDay(dd) : dd)) continue;
                    // insert into the sorted few
                    int at = n;
                    while (at > 0 && times[at - 1] > m) at--;
                    if (at >= times.length) continue;
                    for (int j = Math.min(n, times.length - 1); j > at; j--) {
                        times[j] = times[j - 1];
                        tg[j] = tg[j - 1];
                        tt[j] = tt[j - 1];
                        tb[j] = tb[j - 1];
                    }
                    times[at] = m;
                    tg[at] = g;
                    tt[at] = k;
                    tb[at] = shift;
                    if (n < times.length) n++;
                }
            }
        }
        return n;
    }

    // ----- one trip: its stops and times
    Scr trip(int patId, int g, int tr, int shift, int mark) throws java.io.IOException {
        Dat.Pat p = dat.pat(patId);
        Dat.Line l = dat.lineC(p.line);
        Scr s = new Scr();
        s.kind = K_TRIP;
        s.pat = patId;
        s.g = g;
        s.tr = tr;
        s.pos = mark;
        s.title = p.head.length() > 0 ? p.head : l.to;
        s.data = l;
        int base = p.start[g][tr] + shift * 1440;
        s.base = base;
        s.sub = cat3(agency(l.agency), L(" · יציאה ", " · leaves "), hm(base));
        for (int i = 0; i < p.stops.length; i++) {
            Dat.Stop st = dat.stop(p.stops[i]);
            Row r = row(s, st.name, st.city, 2);
            r.kind = 2;
            r.end = hm(base + p.cum[g][i]);
            r.i1 = st.id;
            r.i2 = i;
            r.on = i == mark;
            r.i3 = i == 0 ? 1 : i == p.stops.length - 1 ? 2 : 0;
        }
        s.sel = Math.max(0, mark);
        s.softL = L("אפשרויות", "Options");
        return s;
    }

    // ----- lines
    Scr lines(String num) throws java.io.IOException {
        Vector v = dat.findLines(num, true);
        Scr s = new Scr();
        s.kind = K_LINES;
        s.title = cat(L("קו ", "Line "), num);
        s.sub = v.size() == 0 ? L("לא נמצאו קווים", "No lines found") : null;
        for (int i = 0; i < v.size(); i++) {
            Dat.Line l = (Dat.Line) v.elementAt(i);
            Row r = row(s, cat(L("אל ", "To "), l.to), cat3(agency(l.agency), L(" · מ", " · from "), l.from), 1);
            r.badge = l.num;
            r.badgeCol = agencyColor(agency(l.agency), l.type);
            r.i1 = l.id;
        }
        return s;
    }

    /** a line in one direction (pattern): its stops */
    Scr line(int lineId, int patId) throws java.io.IOException {
        Dat.Line l = dat.lineC(lineId);
        if (patId < 0) patId = l.pats[0];
        Dat.Pat p = dat.pat(patId);
        Scr s = new Scr();
        s.kind = K_LINE;
        s.line = lineId;
        s.pat = patId;
        s.title = cat3(L("קו ", "Line "), l.num, cat(" · ", agency(l.agency)));
        s.sub = cat(L("אל ", "To "), p.head.length() > 0 ? p.head : l.to);
        for (int i = 0; i < p.stops.length; i++) {
            Dat.Stop st = dat.stop(p.stops[i]);
            Row r = row(s, st.name, st.city, 1);
            r.kind = 2;
            r.i1 = st.id;
            r.i2 = i;
            r.i3 = i == 0 ? 1 : i == p.stops.length - 1 ? 2 : 0;
        }
        s.softL = L("אפשרויות", "Options");
        s.data = l;
        return s;
    }

    /** the whole day's departures of a pattern at a position, one row per hour */
    Scr times(int patId, int pos, int day) throws java.io.IOException {
        Dat.Pat p = dat.pat(patId);
        Dat.Line l = dat.lineC(p.line);
        Dat.Stop st = dat.stop(p.stops[pos]);
        Scr s = new Scr();
        s.kind = K_TIMES;
        s.pat = patId;
        s.pos = pos;
        s.day = day;
        s.title = cat3(L("קו ", "Line "), l.num, cat(L(" · ", " · "), st.name));
        s.sub = cat3(dayName(day), L(" · אל ", " · to "), p.head);
        if (outOfData(day)) s.note = L("לפי יום דומה (הנתונים לא מכסים את היום הזה)", "A similar day (the data does not cover this day)");
        int dd = dataDay(day);
        // all departures of the day, sorted
        int cnt = 0;
        int[] tm = new int[64], gg = new int[64], kk = new int[64];
        for (int g = 0; g < p.cum.length; g++) {
            int c = p.cum[g][pos];
            for (int k = 0; k < p.start[g].length; k++) {
                if (!dat.on(p.ds[g][k], dd)) continue;
                int m = p.start[g][k] + c;
                if (cnt == tm.length) {
                    tm = grow(tm);
                    gg = grow(gg);
                    kk = grow(kk);
                }
                int at = cnt;
                while (at > 0 && tm[at - 1] > m) {
                    tm[at] = tm[at - 1];
                    gg[at] = gg[at - 1];
                    kk[at] = kk[at - 1];
                    at--;
                }
                tm[at] = m;
                gg[at] = g;
                kk[at] = k;
                cnt++;
            }
        }
        int i = 0, firstNext = -1;
        while (i < cnt) {
            int h = tm[i] / 60;
            StringBuffer b = new StringBuffer();
            Row r = new Row();
            r.i1 = gg[i] << 16 | kk[i];
            while (i < cnt && tm[i] / 60 == h) {
                if (b.length() > 0) b.append("  ");
                if (tm[i] % 60 < 10) b.append('0');
                b.append(tm[i] % 60);
                if (firstNext < 0 && day == todayIx && tm[i] >= nowMin) firstNext = s.rows.size();
                i++;
            }
            StringBuffer hh = new StringBuffer();
            if (h % 24 < 10) hh.append('0');
            hh.append(h % 24);
            hh.append(":00");
            r.end = hh.toString();
            r.a = b.toString();
            r.act = 1;
            r.i2 = h;
            s.rows.addElement(r);
        }
        if (cnt == 0) row(s, L("אין נסיעות ביום הזה", "No trips on this day"), null, 0).kind = 1;
        s.sel = firstNext > 0 ? firstNext : 0;
        s.softL = L("יום אחר", "Other day");
        return s;
    }

    static int[] grow(int[] a) {
        int[] b = new int[a.length * 2];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    // ----- stations by name or code, nearby
    Scr stops(String q) throws java.io.IOException {
        Scr s = new Scr();
        s.kind = K_STOPS;
        s.title = q;
        Vector v;
        boolean digits = q.length() > 0;
        for (int i = 0; i < q.length(); i++) if (q.charAt(i) < '0' || q.charAt(i) > '9') digits = false;
        if (digits) {
            v = new Vector();
            int id = dat.byCode(Integer.parseInt(q));
            if (id >= 0) v.addElement(new Integer(id));
        } else v = dat.findStops(Places.norm(q), 60);
        // nearest to the known location first
        Vector rows = new Vector();
        for (int i = 0; i < v.size(); i++) {
            Dat.Stop st = dat.stop(((Integer) v.elementAt(i)).intValue());
            Row r = stopRow(st, -1);
            r.i3 = myKnown ? Dat.dist(myLat, myLon, st.lat, st.lon) : 0;
            if (myKnown) r.end = meters(r.i3);
            int at = rows.size();
            while (at > 0 && ((Row) rows.elementAt(at - 1)).i3 > r.i3) at--;
            rows.insertElementAt(r, at);
        }
        s.rows = rows;
        if (rows.size() == 0) s.sub = L("לא נמצאו תחנות", "No stations found");
        return s;
    }

    Row stopRow(Dat.Stop st, int dist) throws java.io.IOException {
        Row r = new Row();
        r.a = st.name;
        StringBuffer b = new StringBuffer(st.city);
        if (st.code > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(st.code);
        }
        // the lines through it
        StringBuffer ls = new StringBuffer();
        int shown = 0;
        String[] seen = new String[8];
        for (int k = 0; k < st.refP.length && shown < 8; k++) {
            Dat.Pat p = dat.pat(st.refP[k]);
            String num = dat.lineC(p.line).num;
            boolean dup = false;
            for (int j = 0; j < shown; j++) if (seen[j].equals(num)) dup = true;
            if (dup) continue;
            seen[shown++] = num;
            if (ls.length() > 0) ls.append(", ");
            ls.append(num);
        }
        if (ls.length() > 0) {
            if (b.length() > 0) b.append(" · ");
            b.append(L("קווים ", "lines "));
            b.append(ls.toString());
        }
        r.b = b.toString();
        r.i1 = st.id;
        r.act = 1;
        if (dist >= 0) r.end = meters(dist);
        return r;
    }

    Scr near(int lat, int lon, String where) throws java.io.IOException {
        Vector v = dat.near(lat, lon, 800, 30);
        Scr s = new Scr();
        s.kind = K_NEAR;
        s.title = L("תחנות קרובות", "Nearby stations");
        s.sub = cat(L("ליד ", "Near "), where);
        for (int i = 0; i < v.size(); i++) {
            Dat.Stop st = dat.stop(((Integer) v.elementAt(i)).intValue());
            s.rows.addElement(stopRow(st, Dat.dist(lat, lon, st.lat, st.lon)));
        }
        if (v.size() == 0) s.sub = L("אין תחנות בטווח 800 מטר", "No stations within 800 metres");
        s.softL = L("מקום אחר", "Other place");
        return s;
    }

    // ----- the journey planner
    String fromName = "", toName = "";
    int fromLat, fromLon, toLat, toLon, fromStop = -1, toStop = -1;
    boolean fromSet, toSet, fromGps;
    static final int P_FROM = 1, P_TO = 2, P_WHEN = 3, P_GO = 4, P_SWAP = 5;

    Scr planForm() {
        clock();
        Scr s = new Scr();
        s.kind = K_PLAN;
        s.title = L("תכנון מסלול", "Plan a journey");
        Row r = row(s, fromSet ? fromName : L("בחרו נקודת מוצא", "Choose where from"), L("מ:", "From:"), P_FROM);
        r.kind = 4;
        r = row(s, toSet ? toName : L("בחרו יעד", "Choose where to"), L("אל:", "To:"), P_TO);
        r.kind = 4;
        r = row(s, whenNow ? L("יציאה עכשיו", "Leave now") : cat3(L("יציאה ב-", "Leave at "), hm(whenMin), cat(", ", dayName(whenDay))), L("מתי:", "When:"), P_WHEN);
        r.kind = 4;
        if (fromSet && toSet) row(s, L("החלף כיוון", "Swap"), null, P_SWAP);
        r = row(s, L("חפש מסלולים", "Find journeys"), null, P_GO);
        r.kind = 5;
        if (!fromSet) s.sel = 0;
        else if (!toSet) s.sel = 1;
        else s.sel = s.rows.size() - 1;
        return s;
    }

    /** the stations around a point with the walk to each (or the station itself when it is one) */
    void ends(int lat, int lon, int stop, Vector ids, Vector walks) throws java.io.IOException {
        int radius = maxWalk;
        Vector v = dat.near(lat, lon, radius, 40);
        for (int i = 0; i < v.size(); i++) {
            Dat.Stop st = dat.stop(((Integer) v.elementAt(i)).intValue());
            ids.addElement(new Integer(st.id));
            walks.addElement(new Integer(st.id == stop ? 0 : Planner.walkMin(Dat.dist(lat, lon, st.lat, st.lon))));
        }
        if (stop >= 0 && !ids.contains(new Integer(stop))) {
            ids.addElement(new Integer(stop));
            walks.addElement(new Integer(0));
        }
    }

    static int[] ints(Vector v) {
        int[] a = new int[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = ((Integer) v.elementAt(i)).intValue();
        return a;
    }

    Scr journeys() throws java.io.IOException {
        clock();
        Vector oi = new Vector(), ow = new Vector(), di = new Vector(), dw = new Vector();
        ends(fromLat, fromLon, fromStop, oi, ow);
        ends(toLat, toLon, toStop, di, dw);
        Scr s = new Scr();
        s.kind = K_JOURNEYS;
        s.title = cat3(fromName, " ← ", toName);
        if (EN) s.title = cat3(fromName, " → ", toName);
        s.sub = whenNow ? cat(L("יציאה עכשיו, ", "Leaving now, "), hm(whenMin)) : cat3(dayName(whenDay), ", ", hm(whenMin));
        int straight = Dat.dist(fromLat, fromLon, toLat, toLon);
        Vector all = new Vector();
        if (oi.size() == 0 || di.size() == 0) {
            s.note = L("אין תחנות בטווח הליכה מאחת הנקודות. אפשר להגדיל את מרחק ההליכה בהגדרות.", "No stations within walking distance of one of the points. You can allow longer walks in the settings.");
        } else {
            planner.maxRounds = maxTransfers + 1;
            planner.destLat = toLat;
            planner.destLon = toLon;
            int day = dataDay(whenDay), t = whenMin;
            int[] o = ints(oi), owk = ints(ow), d = ints(di), dwk = ints(dw);
            // two searches: from now, and from just after the first answer leaves
            for (int run = 0; run < 2 && !jobCancel; run++) {
                Vector v;
                try {
                    v = planner.plan(o, owk, d, dwk, day, dataDay(whenDay - 1), dataDay(whenDay + 1), t);
                } catch (OutOfMemoryError e) {
                    // not enough memory: everything freed, once more with one transfer less
                    planner.free();
                    freeMemory();
                    if (run > 0 || planner.maxRounds <= 1) break;
                    planner.maxRounds--;
                    s.note = L("הטלפון בלי מספיק זיכרון, ולכן החיפוש הוא עם פחות החלפות.", "The phone is short of memory, so fewer transfers were searched.");
                    v = planner.plan(o, owk, d, dwk, day, dataDay(whenDay - 1), dataDay(whenDay + 1), t);
                }
                int firstDep = Integer.MAX_VALUE;
                for (int i = 0; i < v.size(); i++) {
                    Planner.Journey j = (Planner.Journey) v.elementAt(i);
                    boolean dup = false;
                    for (int k = 0; k < all.size(); k++) {
                        Planner.Journey x = (Planner.Journey) all.elementAt(k);
                        if (x.dep == j.dep && x.arr == j.arr && x.rides == j.rides) dup = true;
                    }
                    if (!dup) all.addElement(j);
                    int fd = firstRide(j);
                    if (fd < firstDep) firstDep = fd;
                }
                if (firstDep == Integer.MAX_VALUE) break;
                t = firstDep + 1 - (firstWalk(v));
            }
            planner.free();
        }
        // by arrival
        for (int i = 1; i < all.size(); i++) for (int k = i; k > 0; k--) {
            Planner.Journey a = (Planner.Journey) all.elementAt(k - 1), b = (Planner.Journey) all.elementAt(k);
            if (a.arr > b.arr || (a.arr == b.arr && a.rides > b.rides)) {
                all.setElementAt(b, k - 1);
                all.setElementAt(a, k);
            }
        }
        if (straight < 1500) {
            Row r = row(s, cat(L("הליכה בלבד · ", "Walk only · "), duration(Planner.walkMin(straight))), meters(straight * 5 / 4), 0);
            r.kind = 0;
        }
        for (int i = 0; i < all.size(); i++) {
            Planner.Journey j = (Planner.Journey) all.elementAt(i);
            Row r = new Row();
            r.kind = 3;
            r.act = 1;
            r.obj = j;
            r.a = cat3(hm(j.dep), " – ", hm(j.arr));
            r.end = duration(j.arr - j.dep);
            int nr = 0;
            for (int k = 0; k < j.legs.length; k++) if (!j.legs[k].walk) nr++;
            r.names = new String[nr];
            r.badges = new int[nr];
            nr = 0;
            for (int k = 0; k < j.legs.length; k++) {
                if (j.legs[k].walk) continue;
                Dat.Line l = dat.lineC(j.legs[k].line);
                r.names[nr] = l.num;
                r.badges[nr] = agencyColor(agency(l.agency), l.type);
                nr++;
            }
            StringBuffer b = new StringBuffer();
            b.append(j.rides <= 1 ? L("ישיר", "Direct") : j.rides == 2 ? L("החלפה אחת", "1 transfer") : L("2 החלפות", "2 transfers"));
            b.append(L(" · הליכה ", " · walk "));
            b.append(j.walkMin);
            b.append(L(" דק'", " min"));
            r.b = b.toString();
            s.rows.addElement(r);
        }
        if (s.rows.size() == 0 && s.note == null) s.note = L("לא נמצא מסלול בשעות הקרובות. נסו שעה אחרת, יותר החלפות או מרחק הליכה גדול יותר.", "No journey found soon. Try another time, more transfers or longer walks.");
        return s;
    }

    /** frees the caches before a big job: patterns, stations, glyph images and the address index */
    void freeMemory() {
        dat.dropPats();
        if (places != null) places.keys = null;
        uR.flush();
        uB.flush();
        uS.flush();
        fTitle.flush();
        System.gc();
    }

    static int firstRide(Planner.Journey j) {
        for (int k = 0; k < j.legs.length; k++) if (!j.legs[k].walk) return j.legs[k].dep;
        return Integer.MAX_VALUE;
    }

    static int firstWalk(Vector v) {
        if (v.size() == 0) return 0;
        Planner.Journey j = (Planner.Journey) v.elementAt(0);
        return j.legs.length > 0 && j.legs[0].walk ? j.legs[0].arr - j.legs[0].dep : 0;
    }

    Scr journey(Planner.Journey j) throws java.io.IOException {
        Scr s = new Scr();
        s.kind = K_JOURNEY;
        s.title = cat3(hm(j.dep), " – ", hm(j.arr));
        s.sub = cat3(duration(j.arr - j.dep), " · ", toName);
        s.data = j;
        for (int k = 0; k < j.legs.length; k++) {
            Planner.Leg g = j.legs[k];
            if (g.walk) {
                int m = g.arr - g.dep;
                if (m <= 0 && k > 0 && k < j.legs.length - 1) continue;
                String to = g.toStop >= 0 ? dat.stop(g.toStop).name : toName;
                Row r = row(s, cat3(L("הליכה ", "Walk "), String.valueOf(Math.max(1, m)), L(" דק'", " min")), cat(L("אל ", "to "), to), 0);
                r.end = hm(g.dep);
                r.kind = 6;
            } else {
                Dat.Line l = dat.lineC(g.line);
                Dat.Stop a = dat.stop(g.fromStop), b = dat.stop(g.toStop);
                Row r = row(s, cat(L("עלייה ב", "Board at "), a.name), cat3(L("לכיוון ", "towards "), EN ? cat(g.head, "\u200E") : g.head, cat3(" · ", String.valueOf(g.alightPos - g.boardPos), L(" תחנות", " stops"))), 1);
                r.badge = l.num;
                r.badgeCol = agencyColor(agency(l.agency), l.type);
                r.end = hm(g.dep);
                r.obj = g;
                Row e = row(s, cat(L("ירידה ב", "Get off at "), b.name), b.city, 1);
                e.end = hm(g.arr);
                e.obj = g;
                e.kind = 7;
            }
        }
        Row r = row(s, cat(L("הגעה ל", "Arrive at "), toName), null, 0);
        r.end = hm(j.arr);
        r.kind = 6;
        return s;
    }

    // ----- the place picker of the planner
    int pickFor;   // P_FROM or P_TO

    Scr pick(int which) {
        pickFor = which;
        Scr s = new Scr();
        s.kind = K_PICK;
        s.title = which == P_FROM ? L("מאיפה?", "Where from?") : L("לאן?", "Where to?");
        if (gpsApi) row(s, L("המיקום שלי (GPS)", "My location (GPS)"), gpsOk ? L("מיקום ידוע", "Location known") : null, 1);
        row(s, L("חיפוש תחנה", "Find a station"), L("לפי שם או מספר", "By name or number"), 2);
        if (places != null) row(s, L("חיפוש כתובת או מקום", "Find an address or place"), L("מקובץ המפה israel.map", "From the map file israel.map"), 3);
        if (myKnown) row(s, myName, L("המקום האחרון", "The last place"), 4);
        for (int i = 0; i < favN; i++) {
            if (favKind[i] == 1) continue;
            Row r = row(s, favName[i], favKind[i] == 0 ? L("תחנה מועדפת", "Favourite station") : L("מקום מועדף", "Favourite place"), 5);
            r.i1 = i;
        }
        return s;
    }

    void setEnd(int which, String name, int lat, int lon, int stop) {
        if (which == P_FROM) {
            fromName = name;
            fromLat = lat;
            fromLon = lon;
            fromStop = stop;
            fromSet = true;
        } else {
            toName = name;
            toLat = lat;
            toLon = lon;
            toStop = stop;
            toSet = true;
        }
        // back to the form
        while (stack.size() > 0 && ((Scr) stack.lastElement()).kind != K_PLAN) stack.removeElementAt(stack.size() - 1);
        if (stack.size() > 0) stack.removeElementAt(stack.size() - 1);
        show(planForm());
    }

    // ----- menus
    Scr menu(String title, int kind) {
        Scr s = new Scr();
        s.kind = K_MENU;
        s.title = title;
        s.g = kind;
        return s;
    }

    static final int M_FAV = 1, M_TIMES = 2, M_WHEN = 3, M_PLAN_FROM = 4, M_PLAN_TO = 5, M_NEAR = 6, M_DIRS = 7,
        M_TRIP_TIMES = 8, M_STATION = 9, M_LINE = 10, M_PLACE = 11;

    // ----- settings and help
    Scr settings() {
        Scr s = new Scr();
        s.kind = K_SET;
        s.title = L("הגדרות", "Settings");
        String[] tr = {L("בלי החלפות", "No transfers"), L("עד החלפה אחת", "Up to 1 transfer"), L("עד 2 החלפות", "Up to 2 transfers")};
        row(s, tr[maxTransfers], L("בתכנון מסלול (פחות = מהיר יותר)", "In journey planning (fewer = faster)"), 1);
        row(s, cat(L("הליכה עד ", "Walk up to "), meters(maxWalk)), L("מהמוצא ומהיעד לתחנה", "From and to the stations"), 2);
        row(s, L("קובץ הנתונים", "Data file"), url, 3);
        return s;
    }

    Scr help() {
        Scr s = new Scr();
        s.kind = K_HELP;
        s.title = L("עזרה ומידע", "Help and information");
        String t = EN ? HELP_EN : HELP;
        int i = 0;
        while (i < t.length()) {
            int nl = t.indexOf('\n', i);
            if (nl < 0) nl = t.length();
            String line = t.substring(i, nl);
            int bar = line.indexOf('|');
            section(s, line.substring(0, bar));
            Vector w = wrap(uR, line.substring(bar + 1), W - 24);
            for (int k = 0; k < w.size(); k++) row(s, (String) w.elementAt(k), null, 0);
            i = nl + 1;
        }
        section(s, L("הנתונים", "The data"));
        StringBuffer b = new StringBuffer();
        if (dat != null) {
            int[] a = date(firstDay), z = date(firstDay + dat.days - 1);
            b.append(L("לוחות הזמנים של משרד התחבורה, בתוקף מ-", "Ministry of Transport timetables, valid "));
            b.append(a[2]);
            b.append('.');
            b.append(a[1]);
            b.append('.');
            b.append(a[0]);
            b.append(L(" עד ", " to "));
            b.append(z[2]);
            b.append('.');
            b.append(z[1]);
            b.append('.');
            b.append(z[0]);
            b.append('.');
        }
        Vector w = wrap(uR, b.toString(), W - 24);
        for (int k = 0; k < w.size(); k++) row(s, (String) w.elementAt(k), null, 0);
        s.softL = "";
        return s;
    }

    static final String HELP = "תחנה|בתחנה מופיע כל קו עם היעד שלו, בעוד כמה דקות הוא יוצא ומתי הנסיעות הבאות. בחירה בקו פותחת את הנסיעה עם כל התחנות והשעות.\nקו|חיפוש לפי מספר. בקו רואים את כל התחנות; בחירה בתחנה מראה את כל השעות של היום. ב'אפשרויות' מחליפים כיוון.\nתכנון מסלול|בוחרים מוצא ויעד: תחנה, כתובת (אם יש בכרטיס את israel.map), מועדף או GPS. מוצגים כמה מסלולים, עם קווים, החלפות והליכה.\nמקשים|2/8 למעלה ולמטה, אישור לבחירה, מקש שמאלי לאפשרויות, ימני לחזרה. במסך שעות: 4/6 יום קודם / הבא.\nזמנים|הזמנים הם לפי לוח הזמנים המתוכנן, בלי מידע על עיכובים בזמן אמת.\nעדכון|הנתונים בתוקף לחודש. מעדכנים על ידי החלפת הקובץ bus.dat בכרטיס בגרסה חדשה.";
    static final String HELP_EN = "Station|Each line at the station shows its destination, the minutes to its next trip and the trips after it. Selecting a line opens the trip with all its stops and times.\nLine|Search by number. A line shows all its stops; selecting a stop shows all the day's times. 'Options' switches direction.\nJourney planner|Choose where from and to: a station, an address (with israel.map on the card), a favourite or GPS. Several journeys are shown, with lines, transfers and walks.\nKeys|2/8 up and down, OK selects, left soft key for options, right soft key back. In the times screen: 4/6 previous / next day.\nTimes|Times are the planned timetable, without real-time delays.\nUpdating|The data is valid for a month. Update it by replacing bus.dat on the card with a new version.";

    // ---------- choosing an action ----------
    void choose(Row r) {
        Scr s = cur;
        if (r.kind == 1) return;
        switch (s.kind) {
            case K_HOME:
                if (r.act == A_PLAN) show(planForm());
                else if (r.act == A_NEAR) nearHere();
                else if (r.act == A_LINE) input(IN_LINE);
                else if (r.act == A_STOP) input(IN_STOP);
                else if (r.act == A_SET) show(settings());
                else if (r.act == A_HELP) show(help());
                else if (r.act == A_FAV) openFav(r.i1);
                break;
            case K_STATION:
                if (r.i3 == Integer.MAX_VALUE) job(J_TIMES, r.i1, r.i2, whenDay, 0);
                else job(J_TRIP, r.i1, r.i4 >>> 16, r.i4 & 0xFFFF, r.sh, r.i2);
                break;
            case K_TRIP:
                job(J_STATION, r.i1, 0, 0, 0);
                break;
            case K_LINES:
                job(J_LINE, r.i1, -1, 0, 0);
                break;
            case K_LINE:
                job(J_TIMES, s.pat, r.i2, whenDay, 0);
                break;
            case K_TIMES:
                job(J_TRIP, s.pat, r.i1 >>> 16, r.i1 & 0xFFFF, 0, s.pos);
                break;
            case K_STOPS:
                if (s.g != 0) stopChosen(s.g, r.i1);   // picked for the planner or for "nearby"
                else job(J_STATION, r.i1, 0, 0, 0);
                break;
            case K_NEAR:
                job(J_STATION, r.i1, 0, 0, 0);
                break;
            case K_PLAN:
                if (r.act == P_FROM) show(pick(P_FROM));
                else if (r.act == P_TO) show(pick(P_TO));
                else if (r.act == P_WHEN) when(K_PLAN);
                else if (r.act == P_SWAP) {
                    String n = fromName;
                    int a = fromLat, b = fromLon, c = fromStop;
                    fromName = toName;
                    fromLat = toLat;
                    fromLon = toLon;
                    fromStop = toStop;
                    toName = n;
                    toLat = a;
                    toLon = b;
                    toStop = c;
                    replace(planForm());
                } else if (r.act == P_GO) {
                    if (!fromSet || !toSet) msg(L("תכנון מסלול", "Journey planner"), L("צריך לבחור מוצא ויעד.", "Choose where from and where to."));
                    else job(J_PLAN, 0, 0, 0, 0);
                }
                break;
            case K_PICK:
                if (r.act == 1) {
                    if (!gpsOk) {
                        startGps();
                        msg("GPS", L("מחפש מיקום... נסו שוב בעוד רגע.", "Finding your location... try again in a moment."));
                    } else setEnd(pickFor, L("המיקום שלי", "My location"), gLat, gLon, -1);
                } else if (r.act == 2) input(IN_PICK_STOP);
                else if (r.act == 3) input(IN_ADDR);
                else if (r.act == 4) setEnd(pickFor, myName, myLat, myLon, -1);
                else if (r.act == 5) {
                    int i = r.i1;
                    setEnd(pickFor, favName[i], favLat[i], favLon[i], favKind[i] == 0 ? favA[i] : -1);
                }
                break;
            case K_ADDR:
                Places.Hit h = (Places.Hit) r.obj;
                if (s.g == 1) setEnd(pickFor, r.a, h.lat, h.lon, -1);
                else {
                    myLat = h.lat;
                    myLon = h.lon;
                    myName = r.a;
                    myKnown = true;
                    job(J_NEAR, 0, 0, 0, 0);
                }
                break;
            case K_JOURNEYS:
                if (r.obj != null) job(J_JOURNEY, 0, 0, 0, 0, r.obj);
                break;
            case K_JOURNEY:
                if (r.obj != null) {
                    Planner.Leg g = (Planner.Leg) r.obj;
                    Planner.Journey j = (Planner.Journey) s.data;
                    job(J_LEG, g.pat, g.boardPos, 0, 0, g);
                }
                break;
            case K_MENU:
                menuAction(s.g, r.act);
                break;
            case K_SET:
                if (r.act == 1) maxTransfers = (maxTransfers + 1) % 3;
                else if (r.act == 2) maxWalk = maxWalk == 400 ? 700 : maxWalk == 700 ? 1000 : 400;
                else if (r.act == 3) {
                    browse("");
                    return;
                }
                save();
                int sel = s.sel;
                replace(settings());
                cur.sel = sel;
                break;
            case K_DIRS:
                stack.removeElementAt(stack.size() - 1);
                cur = (Scr) stack.lastElement();
                job(J_LINE_REPLACE, cur.line, r.i1, 0, 0);
                break;
            case K_BROWSE:
                String e = (String) r.obj;
                if (e.endsWith("/")) browse(cat(browseDir, e));
                else {
                    String u = cat3("file:///", browseDir, e);
                    if (open(u)) save();
                    else msg(L("קובץ הנתונים", "Data file"), L("לא ניתן לפתוח את הקובץ. ודאו שזה קובץ bus.dat שלם.", "The file cannot be opened. Make sure it is a complete bus.dat."));
                }
                break;
        }
    }

    void openFav(int i) {
        if (favKind[i] == 0) job(J_STATION, favA[i], 0, 0, 0);
        else if (favKind[i] == 1) job(J_LINE, favLon[i], favA[i], 0, 0);
        else {
            myLat = favLat[i];
            myLon = favLon[i];
            myName = favName[i];
            myKnown = true;
            job(J_NEAR, 0, 0, 0, 0);
        }
    }

    void nearHere() {
        if (gpsOk) {
            myLat = gLat;
            myLon = gLon;
            myName = L("המיקום שלי", "My location");
            myKnown = true;
            job(J_NEAR, 0, 0, 0, 0);
            return;
        }
        Scr s = menu(L("תחנות קרובות ל...", "Stations near..."), M_NEAR);
        if (gpsApi) row(s, L("המיקום שלי (GPS)", "My location (GPS)"), null, 1);
        if (myKnown) row(s, myName, L("המקום האחרון", "The last place"), 2);
        row(s, L("תחנה", "A station"), L("חיפוש לפי שם או מספר", "Search by name or number"), 3);
        if (places != null) row(s, L("כתובת או מקום", "An address or place"), null, 4);
        for (int i = 0; i < favN; i++) if (favKind[i] != 1) {
            Row r = row(s, favName[i], L("מועדף", "Favourite"), 10 + i);
        }
        show(s);
    }

    void options() {
        Scr s = cur;
        if (s.kind == K_STATION) {
            Scr m = menu(s.title, M_STATION);
            row(m, favIndex(0, s.stop, 0, 0) >= 0 ? L("הסר מהמועדפים", "Remove from favourites") : L("הוסף למועדפים", "Add to favourites"), null, 1);
            row(m, L("שינוי יום ושעה", "Change day and time"), whenNow ? L("עכשיו", "Now") : cat3(dayName(whenDay), ", ", hm(whenMin)), 2);
            row(m, L("מסלול מכאן", "Journey from here"), null, 3);
            row(m, L("מסלול לכאן", "Journey to here"), null, 4);
            row(m, L("תחנות קרובות", "Nearby stations"), null, 5);
            m.data = s;
            show(m);
        } else if (s.kind == K_TRIP || s.kind == K_LINE) {
            Dat.Line l = (Dat.Line) s.data;
            Scr m = menu(cat(L("קו ", "Line "), l.num), M_LINE);
            row(m, favIndex(1, s.pat, 0, 0) >= 0 ? L("הסר מהמועדפים", "Remove from favourites") : L("הוסף למועדפים", "Add to favourites"), null, 1);
            if (s.kind == K_LINE && l.pats.length > 1) row(m, L("כיוון / חלופה אחרת", "Other direction / variant"), null, 2);
            if (s.kind == K_TRIP) row(m, L("כל השעות בתחנה הזו", "All times at this stop"), null, 3);
            if (s.kind == K_TRIP) row(m, L("כל התחנות של הקו", "All stops of the line"), null, 4);
            m.data = s;
            show(m);
        } else if (s.kind == K_TIMES) {
            when(K_TIMES);
        } else if (s.kind == K_NEAR) {
            nearHere();
        }
    }

    void menuAction(int kind, int act) {
        Scr m = cur;
        Scr s = (Scr) m.data;
        if (kind == M_STATION) {
            if (act == 1) {
                back();
                toggleFav(0, s.stop, 0, 0, s.title);
                try {
                    Dat.Stop st = dat.stop(s.stop);
                    int i = favIndex(0, s.stop, 0, 0);
                    if (i >= 0) {
                        favLat[i] = st.lat;
                        favLon[i] = st.lon;
                        save();
                    }
                } catch (Exception e) {
                }
            } else if (act == 2) {
                back();
                when(K_STATION);
            } else if (act == 3 || act == 4) {
                try {
                    Dat.Stop st = dat.stop(s.stop);
                    back();
                    show(planForm());
                    setEnd(act == 3 ? P_FROM : P_TO, st.name, st.lat, st.lon, st.id);
                } catch (Exception e) {
                }
            } else if (act == 5) {
                back();
                job(J_NEAR, 0, 0, 0, 0);
            }
        } else if (kind == M_LINE) {
            Dat.Line l = (Dat.Line) s.data;
            if (act == 1) {
                back();
                toggleFav(1, s.pat, agencyColor(agency(l.agency), l.type), l.id, cat3(l.num, " ", cat(L("אל ", "to "), s.kind == K_TRIP ? s.title : s.sub.substring(Math.min(s.sub.length(), 3)))));
            } else if (act == 2) {
                Scr d = new Scr();
                d.kind = K_DIRS;
                d.title = L("כיוון / חלופה", "Direction / variant");
                for (int i = 0; i < l.pats.length; i++) {
                    try {
                        Dat.Pat p = dat.pat(l.pats[i]);
                        Row r = row(d, cat(L("אל ", "To "), p.head), cat3(dat.stop(p.stops[0]).name, L(" · ", " · "), cat(String.valueOf(p.stops.length), L(" תחנות", " stops"))), 1);
                        r.i1 = l.pats[i];
                    } catch (Exception e) {
                    }
                }
                stack.removeElementAt(stack.size() - 1);
                cur = (Scr) stack.lastElement();
                show(d);
            } else if (act == 3) {
                back();
                job(J_TIMES, s.pat, s.pos < 0 ? 0 : ((Row) s.rows.elementAt(s.sel)).i2, whenDay, 0);
            } else if (act == 4) {
                back();
                job(J_LINE, l.id, s.pat, 0, 0);
            }
        } else if (kind == M_NEAR) {
            if (act == 1) {
                if (!gpsOk) {
                    startGps();
                    msg("GPS", L("מחפש מיקום... נסו שוב בעוד רגע.", "Finding your location... try again in a moment."));
                    return;
                }
                myLat = gLat;
                myLon = gLon;
                myName = L("המיקום שלי", "My location");
                myKnown = true;
                stack.removeElementAt(stack.size() - 1);
                job(J_NEAR, 0, 0, 0, 0);
            } else if (act == 2) {
                stack.removeElementAt(stack.size() - 1);
                job(J_NEAR, 0, 0, 0, 0);
            } else if (act == 3) input(IN_NEAR_STOP);
            else if (act == 4) input(IN_NEAR_ADDR);
            else if (act >= 10) {
                int i = act - 10;
                myLat = favLat[i];
                myLon = favLon[i];
                myName = favName[i];
                myKnown = true;
                stack.removeElementAt(stack.size() - 1);
                job(J_NEAR, 0, 0, 0, 0);
            }
        }
    }

    // ---------- the day and time chooser ----------
    int whenFor;
    int wDay, wMin;
    boolean wNow;

    void when(int forKind) {
        whenFor = forKind;
        clock();
        wDay = cur != null && cur.kind == K_TIMES ? cur.day : whenDay;
        wMin = whenMin / 5 * 5;
        wNow = whenNow;
        ov = O_WHEN;
        safeRepaint();
    }

    void paintWhen(Graphics g) {
        int w = W - 24, h = uR.h * 3 + uB.h + 60;
        int x = 12, y = (H - barH() - h) / 2;
        int by = dialog(g, whenFor == K_TIMES ? L("איזה יום?", "Which day?") : L("מתי?", "When?"), x, y, w, h) + 10;
        boolean timesOnly = whenFor == K_TIMES;
        String dl = dayName(wDay);
        text(g, uR, GRAY, L("יום", "Day"), EN ? x + 10 : x + w - 10, by, EN ? Graphics.TOP | Graphics.LEFT : Graphics.TOP | Graphics.RIGHT);
        text(g, uB, INK, cat3("< ", dl, " >"), x + w / 2, by + uR.h + 4, Graphics.TOP | Graphics.HCENTER);
        by += uR.h + uB.h + 14;
        if (!timesOnly) {
            text(g, uR, GRAY, L("שעה", "Time"), EN ? x + 10 : x + w - 10, by, EN ? Graphics.TOP | Graphics.LEFT : Graphics.TOP | Graphics.RIGHT);
            String tl = wNow ? L("עכשיו", "Now") : hm(wMin);
            text(g, uB, ORANGE_D, cat3("^ ", tl, " v"), x + w / 2, by + uR.h + 4, Graphics.TOP | Graphics.HCENTER);
        }
        text(g, uS, GRAY, timesOnly ? L("4/6 או חצים: יום", "4/6 or arrows: day") : L("4/6: יום · 2/8: שעה · 0: עכשיו", "4/6: day · 2/8: time · 0: now"), x + w / 2, y + h - uS.h - 6, Graphics.TOP | Graphics.HCENTER);
        softBar(g, L("אישור", "OK"), L("ביטול", "Cancel"));
    }

    void keyWhen(int a, int key) {
        if (a == LEFT || key == Canvas.KEY_NUM4) wDay += EN ? -1 : 1;
        else if (a == RIGHT || key == Canvas.KEY_NUM6) wDay += EN ? 1 : -1;
        else if (a == UP || key == Canvas.KEY_NUM2) {
            if (wNow) wNow = false;
            else wMin = (wMin + 15) % 1440;
        } else if (a == DOWN || key == Canvas.KEY_NUM8) {
            if (wNow) wNow = false;
            else wMin = (wMin + 1440 - 15) % 1440;
        } else if (key == Canvas.KEY_NUM0) {
            wNow = true;
            clock();
            wDay = todayIx;
        } else if (a == SOFT_R) ov = O_NONE;
        else if (a == SOFT_L || a == FIRE) {
            ov = O_NONE;
            if (whenFor == K_TIMES) {
                job(J_TIMES_REPLACE, cur.pat, cur.pos, wDay, 0);
                return;
            }
            whenNow = wNow && wDay == todayIx;
            whenDay = wDay;
            whenMin = wNow ? nowMin : wMin;
            clock();
            if (whenFor == K_STATION) job(J_STATION_REPLACE, cur.stop, 0, 0, 0);
            else if (whenFor == K_PLAN) replace(planForm());
        }
        if (wDay < todayIx - 1) wDay = todayIx - 1;
        if (wDay > todayIx + 30) wDay = todayIx + 30;
    }

    // ---------- text input (multi-tap Hebrew with final letters, digits, English) ----------
    static final int IN_LINE = 1, IN_STOP = 2, IN_PICK_STOP = 3, IN_ADDR = 4, IN_NEAR_STOP = 5, IN_NEAR_ADDR = 6;
    static final String[] KEYS = {" ", "-'\"", "דהו", "אבג", "מנ", "יכל", "זחט", "רשת", "צק", "סעפ"};
    static final String[] KEYS_TYPE = {" ", "-'\"", "דהו", "אבג", "מםנן", "יכךל", "זחט", "רשת", "צץק", "סעפף"};
    static final String[] KEYS_EN = {" ", "-'\"", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"};
    int inFor, sMode, sPendKey = -1, sPendIdx, sPendTime;
    StringBuffer sText = new StringBuffer();

    void input(int f) {
        inFor = f;
        sText.setLength(0);
        sMode = f == IN_LINE ? 1 : 0;
        sPendKey = -1;
        ov = O_INPUT;
        safeRepaint();
    }

    String nextMode() {
        int m = (sMode + 1) % 3;
        if (EN) return m == 0 ? "# Hebrew" : m == 1 ? "# digits" : "# English";
        return m == 0 ? "# אותיות" : m == 1 ? "# ספרות" : "# English";
    }

    void paintInput(Graphics g) {
        int w = W - 16, cell = uS.h + 9;
        int h = uR.h + 8 + uR.h + 14 + uS.h + 8 + cell * 4 + 10;
        int x = 8, y = (H - barH() - h) / 2;
        String title = inFor == IN_LINE ? L("מספר הקו", "Line number") : inFor == IN_ADDR || inFor == IN_NEAR_ADDR ? L("כתובת או מקום", "Address or place") : L("שם או מספר תחנה", "Station name or number");
        int by = dialog(g, title, x, y, w, h) + 6;
        g.setColor(BG);
        g.fillRect(x + 6, by, w - 12, uR.h + 8);
        g.setColor(ORANGE);
        g.drawRect(x + 6, by, w - 13, uR.h + 7);
        String t = sText.toString();
        int tw = textW(uR, t);
        int xr = EN ? x + 12 : x + w - 12;
        text(g, uR, INK, t, xr, by + 4, EN ? Graphics.TOP | Graphics.LEFT : Graphics.TOP | Graphics.RIGHT);
        g.setColor(ORANGE);
        g.fillRect(EN ? xr + tw + 2 : xr - tw - 3, by + 3, 1, uR.h + 2);
        by += uR.h + 12;
        String hint = inFor == IN_LINE ? L("למשל: 18 או 480", "e.g. 18 or 480") : inFor == IN_ADDR || inFor == IN_NEAR_ADDR ? L("למשל: הרצל 12 חולון", "e.g. הרצל 12 חולון") : L("למשל: הרצל, או 38831", "e.g. הרצל or 38831");
        text(g, uS, GRAY, hint, EN ? x + 10 : x + w - 10, by, EN ? Graphics.TOP | Graphics.LEFT : Graphics.TOP | Graphics.RIGHT);
        text(g, uS, GRAY, nextMode(), EN ? x + w - 10 : x + 10, by, EN ? Graphics.TOP | Graphics.RIGHT : Graphics.TOP | Graphics.LEFT);
        by += uS.h + 8;
        int cwd = (w - 12) / 3;
        for (int k = 1; k <= 12; k++) {
            int r = (k - 1) / 3, c = (k - 1) % 3;
            int cx = EN ? x + 6 + c * cwd : x + w - 6 - (c + 1) * cwd, cy = by + r * cell;
            g.setColor(SECTION);
            g.fillRect(cx + 1, cy + 1, cwd - 2, cell - 2);
            String lab;
            if (k == 10) lab = L("* מחק", "* delete");
            else if (k == 11) lab = sMode == 1 ? "0" : L("0 רווח", "0 space");
            else if (k == 12) lab = nextMode();
            else if (sMode == 1) lab = String.valueOf(k);
            else {
                StringBuffer b = new StringBuffer();
                b.append(k);
                b.append(' ');
                b.append(k == 1 ? "-'\"" : sMode == 2 ? KEYS_EN[k] : KEYS[k]);
                lab = b.toString();
            }
            textL(g, uS, INK, lab, cx + cwd / 2, mid(uS, cy, cell));
        }
        softBar(g, L("חפש", "Search"), sText.length() > 0 ? L("מחק", "Delete") : L("ביטול", "Cancel"));
    }

    /** a key label: digit first, then the letters, always left to right */
    void textL(Graphics g, BFont f, int color, String s, int x, int y) {
        int sp = s.indexOf(' ');
        if (sp < 0) {
            text(g, f, color, s, x, y, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        String a = s.substring(0, sp), b = s.substring(sp + 1);
        int wa = textW(f, a), wb = textW(f, b), gap = f.w(' ');
        int xl = x - (wa + gap + wb) / 2;
        text(g, f, color, a, xl, y, Graphics.TOP | Graphics.LEFT);
        text(g, f, color, b, xl + wa + gap, y, Graphics.TOP | Graphics.LEFT);
    }

    void keyInput(int a, int key) {
        if (key >= Canvas.KEY_NUM0 && key <= Canvas.KEY_NUM9) {
            int d = key - Canvas.KEY_NUM0, t = now();
            if (sMode == 1) {
                if (sText.length() < 40) sText.append((char) ('0' + d));
                sPendKey = -1;
                return;
            }
            String letters = sMode == 2 ? KEYS_EN[d] : KEYS_TYPE[d];
            if (d == sPendKey && t - sPendTime < 1100 && letters.length() > 1 && sText.length() > 0) {
                sPendIdx = (sPendIdx + 1) % letters.length();
                sText.setCharAt(sText.length() - 1, letters.charAt(sPendIdx));
            } else if (sText.length() < 40) {
                sPendIdx = 0;
                sText.append(letters.charAt(0));
            }
            sPendKey = letters.length() > 1 ? d : -1;
            sPendTime = t;
        } else if (key == Canvas.KEY_POUND) {
            sMode = (sMode + 1) % 3;
            sPendKey = -1;
        } else if (key == Canvas.KEY_STAR || (a == SOFT_R && sText.length() > 0)) {
            if (sText.length() > 0) sText.setLength(sText.length() - 1);
            sPendKey = -1;
        } else if (a == SOFT_R) ov = O_NONE;
        else if (a == SOFT_L || a == FIRE) {
            String q = sText.toString().trim();
            if (q.length() == 0) return;
            ov = O_NONE;
            if (inFor == IN_LINE) job(J_LINES, 0, 0, 0, 0, q);
            else if (inFor == IN_ADDR || inFor == IN_NEAR_ADDR) {
                if (places == null) msg(L("כתובות", "Addresses"), L("כדי לחפש כתובות צריך את קובץ המפה israel.map בכרטיס.", "Address search needs the map file israel.map on the card."));
                else job(J_ADDR, inFor == IN_ADDR ? 1 : 0, 0, 0, 0, q);
            } else job(J_STOPS, inFor, 0, 0, 0, q);
        }
    }

    // ---------- background jobs ----------
    static final int J_STATION = 1, J_TRIP = 2, J_LINES = 3, J_LINE = 4, J_TIMES = 5, J_STOPS = 6, J_NEAR = 7, J_PLAN = 8,
        J_JOURNEY = 9, J_LEG = 10, J_ADDR = 11, J_STATION_REPLACE = 12, J_TIMES_REPLACE = 13, J_LINE_REPLACE = 14;
    int job, ja, jb, jc, jd, je;
    Object jo;
    volatile boolean jobCancel;
    boolean busyRunning;

    void job(int j, int a, int b, int c, int d) { job(j, a, b, c, d, 0, null); }

    void job(int j, int a, int b, int c, int d, int e) { job(j, a, b, c, d, e, null); }

    void job(int j, int a, int b, int c, int d, Object o) { job(j, a, b, c, d, 0, o); }

    void job(int j, int a, int b, int c, int d, int e, Object o) {
        if (busyRunning) return;
        job = j;
        ja = a;
        jb = b;
        jc = c;
        jd = d;
        je = e;
        jo = o;
        jobCancel = false;
        busyRunning = true;
        busyText = j == J_PLAN ? L("מחפש מסלולים...", "Finding journeys...") : L("טוען...", "Loading...");
        ov = O_BUSY;
        safeRepaint();
        new Thread(this).start();
        timer.schedule(new TimerTask() {
            public void run() {
                if (ov == O_BUSY) safeRepaint();
                else cancel();
            }
        }, 200, 200);
    }

    public void run() {
        int j = job;
        try {
            Scr s = null;
            boolean repl = false;
            switch (j) {
                case J_STATION: s = station(ja); break;
                case J_STATION_REPLACE: s = station(ja); repl = true; break;
                case J_TRIP: {
                    // the trip that passes the station (jd = -1: from last night)
                    s = trip(ja, jb, jc, jd, je);
                    break;
                }
                case J_LINES: s = lines((String) jo); break;
                case J_LINE: s = line(ja, jb); break;
                case J_LINE_REPLACE: s = line(ja, jb); repl = true; break;
                case J_TIMES: s = times(ja, jb, jc); break;
                case J_TIMES_REPLACE: s = times(ja, jb, jc); repl = true; break;
                case J_STOPS: {
                    s = stops((String) jo);
                    if (ja == IN_PICK_STOP) s.g = 1;
                    else if (ja == IN_NEAR_STOP) s.g = 2;
                    break;
                }
                case J_NEAR: s = near(myLat, myLon, myName); break;
                case J_PLAN: {
                    freeMemory();
                    s = journeys();
                    break;
                }
                case J_JOURNEY: s = journey((Planner.Journey) jo); break;
                case J_LEG: {
                    Planner.Leg g = (Planner.Leg) jo;
                    s = trip(g.pat, g.g, g.tr, g.shift, g.boardPos);
                    break;
                }
                case J_ADDR: {
                    Vector v = places.search((String) jo, myKnown ? myLat : 31780000, myKnown ? myLon : 35220000);
                    s = new Scr();
                    s.kind = K_ADDR;
                    s.g = ja;
                    s.title = (String) jo;
                    for (int i = 0; i < v.size(); i++) {
                        Places.Hit h = (Places.Hit) v.elementAt(i);
                        StringBuffer a = new StringBuffer(h.name);
                        if (h.house != null && h.house.length() > 0) {
                            a.append(' ');
                            a.append(h.house);
                        }
                        if (h.city != null && h.city.length() > 0 && !h.city.equals(h.name)) {
                            a.append(", ");
                            a.append(h.city);
                        }
                        Row r = row(s, a.toString(), null, 1);
                        r.obj = h;
                    }
                    if (v.size() == 0) s.sub = L("לא נמצאו תוצאות", "No results");
                    break;
                }
            }
            if (jobCancel || s == null) return;
            // a station chosen for the planner or for "nearby"
            if (j == J_STOPS && s.g != 0 && s.rows.size() == 1) {
                Row r = (Row) s.rows.elementAt(0);
                busyRunning = false;
                stopChosen(s.g, r.i1);
                return;
            }
            busyRunning = false;
            if (repl) replace(s);
            else show(s);
        } catch (OutOfMemoryError e) {
            dat.dropPats();
            if (planner != null) planner.free();
            System.gc();
            busyRunning = false;
            msg(L("שגיאה", "Error"), L("אין מספיק זיכרון.", "Not enough memory."));
        } catch (Throwable t) {
            busyRunning = false;
            msg(L("שגיאה", "Error"), L("קריאת הנתונים נכשלה.", "Reading the data failed."));
        } finally {
            busyRunning = false;
            if (ov == O_BUSY) ov = O_NONE;
            safeRepaint();
        }
    }

    /** a station picked from search results for the planner (1) or nearby (2) */
    void stopChosen(int forWhat, int id) {
        try {
            Dat.Stop st = dat.stop(id);
            if (forWhat == 1) setEnd(pickFor, st.name, st.lat, st.lon, st.id);
            else {
                myLat = st.lat;
                myLon = st.lon;
                myName = st.name;
                myKnown = true;
                job(J_NEAR, 0, 0, 0, 0);
            }
        } catch (Exception e) {
        }
    }

    // ---------- the file browser ----------
    String browseDir = "";

    void browse(String dir) {
        browseDir = dir;
        Scr s = new Scr();
        s.kind = K_BROWSE;
        s.title = dir.length() == 0 ? L("בחירת קובץ הנתונים", "Choose the data file") : dir;
        try {
            Vector v = dir.length() == 0 ? Card.roots() : Card.list(cat("file:///", dir));
            for (int i = 0; i < v.size(); i++) {
                String e = (String) v.elementAt(i);
                if (e.endsWith("/")) row(s, e.substring(0, e.length() - 1), L("תיקייה", "Folder"), 1).obj = e;
                else if (e.toLowerCase().endsWith(".dat")) row(s, e, L("קובץ נתונים", "Data file"), 1).obj = e;
            }
        } catch (Throwable t) {
        }
        if (stack.size() > 0 && cur.kind == K_BROWSE) replace(s);
        else show(s);
    }

    // ---------- GPS ----------
    boolean gpsApi, gpsOn, gpsOk;
    Gps gps;
    int gLat, gLon, gAcc;

    void startGps() {
        if (gpsOn || !gpsApi) return;
        if (gps == null) gps = new Gps(this);
        gpsOn = gps.start();
    }

    void gpsFix(int la, int lo, int acc) {
        gLat = la;
        gLon = lo;
        gAcc = acc;
        gpsOk = true;
        safeRepaint();
    }

    void gpsState(boolean ok) {
        if (!ok) gpsOk = false;
    }

    // ---------- overlays ----------
    static final int O_NONE = 0, O_INPUT = 1, O_MSG = 2, O_BUSY = 3, O_WHEN = 4;
    int ov;
    String mTitle, busyText;
    Vector mLines;
    String toastText;
    int toastUntil;
    final Timer timer = new Timer();

    void msg(String title, String t) {
        mTitle = title;
        mLines = wrap(uR, t, W - 40);
        ov = O_MSG;
        safeRepaint();
    }

    void toast(String s) {
        toastText = s;
        toastUntil = now() + 1600;
        timer.schedule(new TimerTask() {
            public void run() { safeRepaint(); }
        }, 1700);
        safeRepaint();
    }

    int dialog(Graphics g, String title, int x, int y, int w, int h) {
        g.setColor(BLACK);
        g.fillRect(x - 1, y - 1, w + 2, h + 2);
        g.setColor(BG);
        g.fillRect(x, y, w, h);
        int th = uR.h + 10;
        g.setColor(ORANGE);
        g.fillRect(x, y, w, th);
        text(g, uB, BG, fit(uB, title, w - 12), x + w / 2, mid(uB, y, th), Graphics.TOP | Graphics.HCENTER);
        return y + th;
    }

    void paintMsg(Graphics g) {
        int lh = uR.h + 4;
        int h = uR.h + 10 + mLines.size() * lh + 16;
        if (h > H - barH() - 10) h = H - barH() - 10;
        int x = 12, w = W - 24, y = (H - barH() - h) / 2;
        int by = dialog(g, mTitle, x, y, w, h) + 8;
        for (int i = 0; i < mLines.size() && by + lh <= y + h; i++, by += lh) {
            text(g, uR, INK, (String) mLines.elementAt(i), EN ? x + 8 : x + w - 8, by, EN ? Graphics.TOP | Graphics.LEFT : Graphics.TOP | Graphics.RIGHT);
        }
        softBar(g, L("אישור", "OK"), "");
    }

    void paintBusy(Graphics g) {
        int w = W - 60, h = uR.h * 2 + 30;
        int x = 30, y = (H - h) / 2;
        int by = dialog(g, busyText, x, y, w, h);
        int ph = (now() / 150) % 8;
        for (int i = 0; i < 8; i++) {
            g.setColor(i == ph ? ORANGE : PALE);
            g.fillArc(x + w / 2 - 44 + i * 11, by + 14, 7, 7, 0, 360);
        }
        softBar(g, "", L("ביטול", "Cancel"));
    }

    void softBar(Graphics g, String left, String right) {
        int bh = barH(), y = H - bh;
        g.setColor(BLACK);
        g.fillRect(0, y, W, bh);
        int ty = mid(uR, y, bh);
        text(g, uB, BG, left, 6, ty, Graphics.TOP | Graphics.LEFT);
        text(g, uB, BG, right, W - 6, ty, Graphics.TOP | Graphics.RIGHT);
    }

    // ---------- painting a screen ----------
    int rowH(Row r) {
        if (r.kind == 1) return r.a.length() == 0 ? 8 : uS.h + 8;
        if (r.kind == 2) return r.b != null && r.b.length() > 0 ? uR.h + uS.h + 8 : uR.h + 10;
        if (r.kind == 5) return uB.h + 18;
        return r.b != null ? uR.h + uS.h + 12 : uR.h + 14;
    }

    void paintScreen(Graphics g) {
        Scr s = cur;
        g.setColor(BG);
        g.fillRect(0, 0, W, H);
        // the orange header
        int th = uB.h + 10 + (s.sub != null ? uS.h + 4 : 0);
        g.setColor(ORANGE);
        g.fillRect(0, 0, W, th);
        int hx = xs(8);
        int badgeW = 0;
        if (s.kind == K_TRIP && s.data != null) {
            Dat.Line l = (Dat.Line) s.data;
            badgeW = badge(g, l.num, agencyColor(agency(l.agency), l.type), xs(8), 5, true) + 6;
        }
        text(g, uB, BG, fit(uB, s.title, W - 16 - badgeW), EN ? hx + badgeW : hx - badgeW, 5, as());
        if (s.sub != null) text(g, uS, 0xFFF1E8, fit(uS, s.sub, W - 16), hx, uB.h + 8, as());
        int top = th;
        if (s.note != null) {
            Vector v = wrap(uS, s.note, W - 16);
            int nh = v.size() * (uS.h + 2) + 8;
            g.setColor(0xFFF4D6);
            g.fillRect(0, top, W, nh);
            for (int i = 0; i < v.size(); i++) text(g, uS, 0x7A4A00, (String) v.elementAt(i), xs(8), top + 4 + i * (uS.h + 2), as());
            top += nh;
        }
        int bottom = H - barH();
        // keep the selection visible
        if (s.sel < s.top) s.top = s.sel;
        while (true) {
            int y = top;
            for (int i = s.top; i <= s.sel && i < s.rows.size(); i++) y += rowH((Row) s.rows.elementAt(i));
            if (y <= bottom || s.top >= s.sel) break;
            s.top++;
        }
        int y = top;
        int visible = 0;
        for (int i = s.top; i < s.rows.size() && y < bottom; i++) {
            Row r = (Row) s.rows.elementAt(i);
            int h = rowH(r);
            paintRow(g, s, r, i == s.sel && s.kind != K_HELP, y, h);
            y += h;
            if (y <= bottom) visible++;
        }
        if (s.rows.size() == 0 && s.sub == null) text(g, uR, GRAY, L("אין פריטים", "No items"), W / 2, top + 20, Graphics.TOP | Graphics.HCENTER);
        // scroll bar
        if (visible < s.rows.size()) {
            int bh = bottom - top - 4;
            int kh = Math.max(12, bh * Math.max(1, visible) / s.rows.size());
            int ky = top + 2 + (bh - kh) * s.top / Math.max(1, s.rows.size() - visible);
            int sx = EN ? W - 4 : 1;
            g.setColor(RULE);
            g.fillRect(sx, top + 2, 3, bh);
            g.setColor(ORANGE);
            g.fillRect(sx, ky, 3, kh);
        }
        softBar(g, s.softL, s.softR);
        if (toastText != null && now() < toastUntil) {
            int tw = textW(uR, toastText) + 18, tth = uR.h + 10;
            int ty = bottom - tth - 10;
            g.setColor(BLACK);
            g.fillRoundRect((W - tw) / 2, ty, tw, tth, 10, 10);
            text(g, uR, BG, toastText, W / 2, ty + 5, Graphics.TOP | Graphics.HCENTER);
        }
    }

    /** a rounded line badge; returns its width */
    int badge(Graphics g, String num, int col, int x, int y, boolean onOrange) {
        int w = Math.max(textW(uB, num) + 10, uB.h + 8), h = uB.h + 4;
        int bx = EN ? x : x - w;
        if (onOrange) {
            g.setColor(BG);
            g.fillRoundRect(bx - 1, y - 1, w + 2, h + 2, 8, 8);
        }
        g.setColor(col);
        g.fillRoundRect(bx, y, w, h, 7, 7);
        text(g, uB, BG, num, bx + w / 2, y + 2, Graphics.TOP | Graphics.HCENTER);
        return w;
    }

    void paintRow(Graphics g, Scr s, Row r, boolean sel, int y, int h) {
        if (r.kind == 1) {
            g.setColor(SECTION);
            g.fillRect(0, y, W, h);
            if (r.a.length() > 0) text(g, uS, GRAY, r.a, xs(10), y + 4, as());
            return;
        }
        if (sel) {
            g.setColor(SEL);
            g.fillRect(0, y, W, h);
            g.setColor(ORANGE);
            g.fillRect(EN ? 0 : W - 4, y, 4, h);
        }
        if (r.kind == 5) {   // the orange button
            g.setColor(sel ? ORANGE_D : ORANGE);
            g.fillRoundRect(16, y + 5, W - 32, h - 10, 12, 12);
            text(g, uB, BG, r.a, W / 2, mid(uB, y, h), Graphics.TOP | Graphics.HCENTER);
            return;
        }
        int x = xs(10);
        int endW = 0;
        if (r.end != null) {
            BFont ef = r.kind == 2 || r.kind == 6 || r.kind == 7 ? uR : uB;
            int ec = s.kind == K_STATION && r.i3 != Integer.MAX_VALUE && r.i3 - nowMin < 60 && whenNow ? ORANGE_D : INK;
            endW = Math.max(textW(ef, r.end), r.endSub != null ? textW(uS, r.endSub) : 0) + 8;
            int ey = r.endSub != null ? y + 5 : mid(ef, y, h);
            text(g, ef, ec, r.end, xe(8), ey, ae());
            if (r.endSub != null) text(g, uS, GRAY, r.endSub, xe(8), ey + ef.h + 1, ae());
        }
        if (r.kind == 2) {   // a stop on a timeline
            int lx = xs(16);
            int col = s.kind == K_TRIP && s.data != null ? agencyColor(agency(((Dat.Line) s.data).agency), ((Dat.Line) s.data).type) : ORANGE;
            g.setColor(col);
            int ya = r.i3 == 1 ? y + h / 2 : y, yb = r.i3 == 2 ? y + h / 2 : y + h;
            g.fillRect(lx - 2, ya, 4, yb - ya);
            int rr = r.on ? 6 : 4;
            g.fillArc(lx - rr, y + h / 2 - rr, 2 * rr, 2 * rr, 0, 360);
            g.setColor(BG);
            if (!r.on) g.fillArc(lx - 2, y + h / 2 - 2, 4, 4, 0, 360);
            x = xs(30);
            int tw = W - 40 - endW;
            boolean two = r.b != null && r.b.length() > 0;
            text(g, r.on ? uB : uR, INK, fit(uR, r.a, tw), x, two ? y + 3 : mid(uR, y, h), as());
            if (two) text(g, uS, GRAY, fit(uS, r.b, tw), x, y + uR.h + 4, as());
            g.setColor(RULE);
            g.fillRect(EN ? 30 : 8, y + h - 1, W - 40, 1);
            return;
        }
        if (r.kind == 3) {   // a journey: badges of its rides
            int bx = x;
            for (int k = 0; k < r.names.length; k++) {
                int bw = badge(g, r.names[k], r.badges[k], bx, y + 5, false);
                bx = EN ? bx + bw + 4 : bx - bw - 4;
                if (k + 1 < r.names.length) {
                    text(g, uS, GRAY, EN ? ">" : "<", bx, y + 7, as());
                    bx = EN ? bx + 10 : bx - 10;
                }
            }
            text(g, uB, INK, r.a, bx + (EN ? 4 : -4), y + 6, as());
            text(g, uS, GRAY, fit(uS, r.b, W - 20 - endW), x, y + uB.h + 9, as());
            g.setColor(RULE);
            g.fillRect(8, y + h - 1, W - 16, 1);
            return;
        }
        if (r.kind == 6 || r.kind == 7) {   // journey steps: walking (grey) and getting off (indented)
            g.setColor(r.kind == 6 ? 0xBDBDBD : ORANGE);
            g.fillArc(xs(14) - 4, y + h / 2 - 4, 8, 8, 0, 360);
            x = xs(26);
        }
        int bw = 0;
        if (r.badge != null) bw = badge(g, r.badge, r.badgeCol, x, r.b != null ? y + 6 : mid(uB, y, h) - 2, false) + 8;
        if (r.kind == 4) {   // a planner field: label, then the value
            text(g, uS, GRAY, r.b, x, y + 4, as());
            text(g, uB, INK, fit(uB, r.a, W - 24), x, y + uS.h + 6, as());
            g.setColor(RULE);
            g.fillRect(8, y + h - 1, W - 16, 1);
            return;
        }
        int tx = EN ? x + bw : x - bw;
        int tw = W - 20 - bw - endW;
        boolean two = r.b != null;
        text(g, s.kind == K_HOME && r.act != A_FAV ? uB : uR, INK, fit(uB, r.a, tw), tx, two ? y + 5 : mid(uR, y, h), as());
        if (two) text(g, uS, GRAY, fit(uS, r.b, tw), tx, y + uR.h + 7, as());
        g.setColor(RULE);
        g.fillRect(8, y + h - 1, W - 16, 1);
    }

    void paintStart(Graphics g) {
        g.setColor(ORANGE);
        g.fillRect(0, 0, W, H);
        // a bus
        int cx = W / 2, cy = H / 3;
        g.setColor(BG);
        g.fillRoundRect(cx - 40, cy - 34, 80, 62, 14, 14);
        g.setColor(ORANGE);
        g.fillRect(cx - 32, cy - 24, 64, 24);
        g.setColor(BG);
        g.fillRect(cx - 1, cy - 24, 2, 24);
        g.setColor(BLACK);
        g.fillArc(cx - 30, cy + 20, 16, 16, 0, 360);
        g.fillArc(cx + 14, cy + 20, 16, 16, 0, 360);
        g.setColor(BG);
        g.fillArc(cx - 25, cy + 25, 6, 6, 0, 360);
        g.fillArc(cx + 19, cy + 25, 6, 6, 0, 360);
        g.setColor(ORANGE_D);
        g.fillRect(cx - 32, cy + 6, 10, 6);
        g.fillRect(cx + 22, cy + 6, 10, 6);
        int y = cy + 50;
        text(g, fTitle, BG, L("תחבורה ציבורית", "Public Transport"), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += fTitle.h + 8;
        if (startText != null) {
            text(g, uR, BG, startText, W / 2, y, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        text(g, uB, BG, L("לא נמצא קובץ הנתונים", "The data file was not found"), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        y += uB.h + 8;
        String body = noFileWhy != null ? noFileWhy : L("העתיקו את הקובץ bus.dat לכרטיס הזיכרון: לתיקייה הראשית, לתיקייה Bus או לתיקייה של היישום. אחר כך לחצו 5 כדי לחפש שוב, או 'בחר קובץ'.", "Copy the file bus.dat to the memory card: to the main folder, to a folder named Bus or to the folder of the app. Then press 5 to look again, or 'Choose file'.");
        Vector v = wrap(uS, body, W - 30);
        for (int i = 0; i < v.size(); i++, y += uS.h + 3) text(g, uS, BG, (String) v.elementAt(i), W / 2, y, Graphics.TOP | Graphics.HCENTER);
        softBar(g, L("בחר קובץ", "Choose file"), L("יציאה", "Exit"));
    }

    private final Object plock = new Object();
    private boolean painting, again;

    void safeRepaint() {
        synchronized (plock) {
            if (painting) {
                again = true;
                return;
            }
        }
        repaint();
    }

    protected void paint(Graphics g) {
        synchronized (plock) {
            if (painting) {
                again = true;
                return;
            }
            painting = true;
        }
        try {
            W = getWidth();
            H = getHeight();
            g.setClip(0, 0, W, H);
            if (!ready || cur == null) {
                paintStart(g);
                if (cur != null && cur.kind == K_BROWSE) paintScreen(g);
                if (ov == O_MSG) paintMsg(g);
            } else {
                paintScreen(g);
                if (ov == O_INPUT) paintInput(g);
                else if (ov == O_MSG) paintMsg(g);
                else if (ov == O_BUSY) paintBusy(g);
                else if (ov == O_WHEN) paintWhen(g);
            }
        } catch (OutOfMemoryError e) {
            if (dat != null) dat.dropPats();
            System.gc();
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            synchronized (plock) {
                painting = false;
            }
        }
        boolean a;
        synchronized (plock) {
            a = again;
            again = false;
        }
        if (a) repaint();
    }

    // ---------- keys ----------
    static final int UP = 1, DOWN = 2, LEFT = 3, RIGHT = 4, FIRE = 5, SOFT_L = 6, SOFT_R = 7, NONE = 0;

    int action(int key) {
        if (key == -6 || key == -21 || key == 21) return SOFT_L;
        if (key == -7 || key == -22 || key == 22) return SOFT_R;
        if (key == -5 || key == -20 || key == 20) return FIRE;
        if (key == -1) return UP;
        if (key == -2) return DOWN;
        if (key == -3) return LEFT;
        if (key == -4) return RIGHT;
        if (key >= Canvas.KEY_NUM0 && key <= Canvas.KEY_NUM9) return NONE;
        int a = 0;
        try {
            a = getGameAction(key);
        } catch (Exception e) {
        }
        if (a == Canvas.UP) return UP;
        if (a == Canvas.DOWN) return DOWN;
        if (a == Canvas.LEFT) return LEFT;
        if (a == Canvas.RIGHT) return RIGHT;
        if (a == Canvas.FIRE) return FIRE;
        return NONE;
    }

    public void keyPressed(int key) {
        int a = action(key);
        if (ov == O_INPUT) keyInput(a, key);
        else if (ov == O_MSG) {
            if (a == SOFT_L || a == FIRE || a == SOFT_R || key == Canvas.KEY_NUM5) ov = O_NONE;
        } else if (ov == O_BUSY) {
            if (a == SOFT_R) {
                jobCancel = true;
                if (planner != null) planner.cancel = true;
                if (places != null) places.cancel = true;
                ov = O_NONE;
            }
        } else if (ov == O_WHEN) keyWhen(a, key);
        else if (!ready && (cur == null || cur.kind != K_BROWSE)) {
            if (a == SOFT_L) browse("");
            else if (a == SOFT_R) app.quit();
            else if (a == FIRE || key == Canvas.KEY_NUM5) new Thread(new Runnable() {
                public void run() { findFiles(); }
            }).start();
        } else if (cur != null) keyScreen(a, key);
        safeRepaint();
    }

    public void keyRepeated(int key) {
        int a = action(key);
        if (ov == O_NONE && cur != null && (a == UP || a == DOWN || key == Canvas.KEY_NUM2 || key == Canvas.KEY_NUM8)) keyScreen(a, key);
        safeRepaint();
    }

    void keyScreen(int a, int key) {
        Scr s = cur;
        int n = s.rows.size();
        if (a == UP || key == Canvas.KEY_NUM2) {
            int i = s.sel - 1;
            while (i >= 0 && (((Row) s.rows.elementAt(i)).kind == 1 || s.kind == K_HELP && false)) i--;
            if (s.kind == K_HELP) {
                s.top = Math.max(0, s.top - 1);
                s.sel = s.top;
                return;
            }
            if (i >= 0) s.sel = i;
        } else if (a == DOWN || key == Canvas.KEY_NUM8) {
            if (s.kind == K_HELP) {
                s.sel = Math.min(n - 1, s.sel + 3);
                return;
            }
            int i = s.sel + 1;
            while (i < n && ((Row) s.rows.elementAt(i)).kind == 1) i++;
            if (i < n) s.sel = i;
        } else if ((a == LEFT || a == RIGHT || key == Canvas.KEY_NUM4 || key == Canvas.KEY_NUM6) && s.kind == K_TIMES) {
            boolean nextDay = (a == LEFT || key == Canvas.KEY_NUM4) != EN;
            job(J_TIMES_REPLACE, s.pat, s.pos, s.day + (nextDay ? 1 : -1), 0);
        } else if (a == FIRE || key == Canvas.KEY_NUM5) {
            if (s.sel < n) choose((Row) s.rows.elementAt(s.sel));
        } else if (a == SOFT_L) {
            if (s.kind == K_TIMES) when(K_TIMES);
            else if (s.kind == K_STATION || s.kind == K_TRIP || s.kind == K_LINE || s.kind == K_NEAR) options();
            else if (s.sel < n && s.softL.length() > 0) choose((Row) s.rows.elementAt(s.sel));
        } else if (a == SOFT_R) back();
        else if (key == Canvas.KEY_NUM0 && s.kind == K_HOME) {
            // quick: refresh the clock
            clock();
            replace(home());
        }
    }
}
