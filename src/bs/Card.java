package bs;

import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Vector;
import javax.microedition.io.Connector;
import javax.microedition.io.file.FileConnection;
import javax.microedition.io.file.FileSystemRegistry;

/**
 * A data file on the memory card with sections (bus.dat, and israel.map of the maps app for addresses). Everything
 * that touches JSR-75 lives here. One connection stays open; reads move forward by skipping and reopen the stream
 * to go back.
 */
final class Card {
    final String url;
    private FileConnection fc;
    private InputStream in;
    private int at;
    int size;
    final int[] sOff = new int[16], sLen = new int[16];

    /** magic: "ILBS" or "ILMP" */
    Card(String url, String magic) throws IOException {
        this.url = url;
        fc = (FileConnection) Connector.open(esc(url), Connector.READ);
        if (!fc.exists()) {
            fc.close();
            throw new IOException("missing");
        }
        size = (int) Math.min(fc.fileSize(), 2147483000L);
        byte[] h = read(0, 8);
        for (int i = 0; i < 4; i++) if (h[i] != magic.charAt(i)) throw new IOException("bad file");
        int n = u16(h, 6);
        byte[] t = read(8, n * 9);
        for (int i = 0; i < n; i++) {
            int id = t[i * 9] & 255;
            if (id < 16) {
                sOff[id] = s32(t, i * 9 + 1);
                sLen[id] = s32(t, i * 9 + 5);
            }
        }
    }

    synchronized byte[] read(int pos, int len) throws IOException {
        byte[] b = new byte[len];
        int got = 0;
        while (got < len) {
            int r = fill(pos + got, b, got, len - got);
            if (r <= 0) throw new IOException("eof");
            got += r;
        }
        return b;
    }

    private int fill(int pos, byte[] b, int off, int len) throws IOException {
        if (in == null || pos < at) {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                }
                in = null;
            }
            try {
                in = fc.openInputStream();
            } catch (IOException e) {
                try {
                    fc.close();
                } catch (IOException x) {
                }
                fc = (FileConnection) Connector.open(esc(url), Connector.READ);
                in = fc.openInputStream();
            }
            at = 0;
        }
        while (at < pos) {
            int k = (int) in.skip(pos - at);
            if (k <= 0) {
                if (in.read() < 0) return -1;
                k = 1;
            }
            at += k;
        }
        int r = in.read(b, off, len);
        if (r > 0) at += r;
        return r;
    }

    void close() {
        try {
            if (in != null) in.close();
        } catch (Throwable t) {
        }
        try {
            if (fc != null) fc.close();
        } catch (Throwable t) {
        }
        in = null;
        fc = null;
    }

    static int u16(byte[] d, int p) { return ((d[p] & 255) << 8) | (d[p + 1] & 255); }

    static int s32(byte[] d, int p) { return ((d[p] & 255) << 24) | ((d[p + 1] & 255) << 16) | ((d[p + 2] & 255) << 8) | (d[p + 3] & 255); }

    static int vint(byte[] d, int[] p) {
        int v = 0, s = 0, i = p[0];
        while (true) {
            int b = d[i++];
            v |= (b & 0x7F) << s;
            if ((b & 0x80) == 0) break;
            s += 7;
        }
        p[0] = i;
        return v;
    }

    static String str(byte[] d, int[] p) {
        int l = vint(d, p);
        String s = utf8(d, p[0], l);
        p[0] += l;
        return s;
    }

    static String utf8(byte[] d, int s, int l) {
        StringBuffer b = new StringBuffer(l);
        int e = s + l;
        while (s < e) {
            int c = d[s++] & 255;
            if (c < 0x80) b.append((char) c);
            else if (c < 0xE0) b.append((char) (((c & 0x1F) << 6) | (d[s++] & 0x3F)));
            else if (c < 0xF0) {
                b.append((char) (((c & 0x0F) << 12) | ((d[s] & 0x3F) << 6) | (d[s + 1] & 0x3F)));
                s += 2;
            } else {
                s += 3;
                b.append('?');
            }
        }
        return b.toString();
    }

    // ---------- the file system ----------
    static boolean available() {
        try {
            Class.forName("javax.microedition.io.file.FileConnection");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    static Vector roots() {
        Vector v = new Vector();
        try {
            Enumeration e = FileSystemRegistry.listRoots();
            while (e.hasMoreElements()) v.addElement(e.nextElement());
        } catch (Throwable t) {
        }
        return v;
    }

    static Vector list(String url) throws IOException {
        Vector v = new Vector();
        FileConnection c = (FileConnection) Connector.open(esc(url), Connector.READ);
        try {
            Enumeration e = c.list();
            while (e.hasMoreElements()) v.addElement(e.nextElement());
        } finally {
            c.close();
        }
        return v;
    }

    static boolean exists(String url) {
        FileConnection c = null;
        try {
            c = (FileConnection) Connector.open(esc(url), Connector.READ);
            return c.exists() && !c.isDirectory();
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                if (c != null) c.close();
            } catch (Throwable t) {
            }
        }
    }

    static String esc(String u) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            if (c > ' ' && c < 0x7F && c != '%' && c != '#') b.append(c);
            else if (c < 0x80) hex(b, c);
            else if (c < 0x800) {
                hex(b, 0xC0 | (c >> 6));
                hex(b, 0x80 | (c & 63));
            } else {
                hex(b, 0xE0 | (c >> 12));
                hex(b, 0x80 | ((c >> 6) & 63));
                hex(b, 0x80 | (c & 63));
            }
        }
        return b.toString();
    }

    static void hex(StringBuffer b, int v) {
        b.append('%');
        b.append("0123456789ABCDEF".charAt((v >> 4) & 15));
        b.append("0123456789ABCDEF".charAt(v & 15));
    }
}
