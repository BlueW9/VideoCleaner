/*
 * Video Cleaner - removes sensitive metadata from videos, fully offline.
 * Copyright (C) BlueW9
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.bluew9.videocleaner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public class MainActivity extends Activity {
    private static final int PICK = 1, SAVE = 2;
    private static final Charset ISO = StandardCharsets.ISO_8859_1;
    private static final Charset UTF8 = StandardCharsets.UTF_8;

    private Uri src;
    private Map<String, String> before = new LinkedHashMap<>();
    private boolean basicScan;
    private int trackNo;
    private TextView info;
    private Button pick, clean;
    private Switch replaceSwitch;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        new File(getCacheDir(), "work.mp4").delete();
        int pad = (int) (24 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("Video Cleaner");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Offline. No permissions. No network. Lossless (no re-encoding).");
        sub.setTextColor(0xFF8FA3B5);
        sub.setPadding(0, pad / 3, 0, pad);
        root.addView(sub);

        pick = button("Select video");
        root.addView(pick);

        replaceSwitch = new Switch(this);
        replaceSwitch.setText("Clean the original file (no copy)");
        replaceSwitch.setTextColor(Color.WHITE);
        root.addView(replaceSwitch);

        TextView warn = new TextView(this);
        warn.setText("On: your file is overwritten permanently.\nOff: a cleaned copy is saved and the original stays untouched.");
        warn.setTextColor(0xFF8FA3B5);
        warn.setTextSize(13);
        warn.setPadding(0, 8, 0, pad / 2);
        root.addView(warn);

        clean = button("Clean and save a copy");
        clean.setEnabled(false);
        root.addView(clean);

        info = new TextView(this);
        info.setTextColor(0xFFD6E0EA);
        info.setTextSize(14);
        info.setPadding(0, pad / 2, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(info);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        replaceSwitch.setOnCheckedChangeListener((v, on) ->
                clean.setText(on ? "Clean the original" : "Clean and save a copy"));

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(i, PICK);
        });

        clean.setOnClickListener(v -> {
            if (replaceSwitch.isChecked()) {
                new AlertDialog.Builder(this)
                        .setTitle("Replace the original?")
                        .setMessage("The original video will be overwritten with the cleaned version. This cannot be undone.")
                        .setPositiveButton("Replace", (d, w) -> startJob(src, true))
                        .setNegativeButton("Cancel", null)
                        .show();
            } else {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("video/mp4");
                i.putExtra(Intent.EXTRA_TITLE, "clean_video.mp4"); // generic name, nothing carried over
                startActivityForResult(i, SAVE);
            }
        });
    }

    private Button button(String t) {
        Button x = new Button(this);
        x.setText(t);
        x.setTextColor(Color.WHITE);
        x.setBackgroundColor(0xFF1F5F8B);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = 24;
        x.setLayoutParams(lp);
        return x;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        super.onActivityResult(req, res, d);
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        if (req == PICK) {
            src = d.getData();
            clean.setEnabled(false);
            info.setText("Scanning...");
            new Thread(() -> {
                Map<String, String> m = scan(src);
                before = m;
                runOnUiThread(() -> {
                    info.setText(detectedText(m));
                    clean.setEnabled(true);
                });
            }).start();
        } else if (req == SAVE) {
            startJob(d.getData(), false);
        }
    }

    private String detectedText(Map<String, String> m) {
        StringBuilder s = new StringBuilder();
        if (m.isEmpty()) {
            s.append("No identifying metadata found in this file.\n");
        } else {
            s.append("Detected ").append(m.size()).append(" item(s):\n\n");
            for (Map.Entry<String, String> e : m.entrySet())
                s.append("\u2022 ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        if (basicScan)
            s.append("\n(Detailed scan supports MP4/MOV/3GP. This format shows basic fields only.)");
        return s.toString();
    }

    // ------------------------------------------------------------------ job

    private void startJob(Uri dest, boolean replace) {
        clean.setEnabled(false);
        pick.setEnabled(false);
        info.setText("Cleaning...");
        new Thread(() -> {
            File tmp = new File(getCacheDir(), "work.mp4");
            boolean writing = false;
            String msg;
            try {
                if (replace) {
                    try (ParcelFileDescriptor p = getContentResolver().openFileDescriptor(src, "rw")) {
                        if (p == null) throw new IOException("null");
                    } catch (Exception e) {
                        throw new IOException("No write access to this file. Turn off \"Clean the original\" to save a copy instead.");
                    }
                }
                remux(src, tmp);
                sanitize(tmp);
                verify(src, tmp);
                Map<String, String> after = scanFile(tmp);
                if (after == null) after = new LinkedHashMap<>();
                writing = true;
                writeTo(dest, tmp);
                msg = report(before, after, replace);
            } catch (Exception e) {
                if (!replace) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), dest); } catch (Exception ignored) {}
                }
                msg = "Failed: " + e.getMessage();
                if (replace) msg += writing
                        ? "\nWriting was interrupted. The original may be damaged."
                        : "\nThe original was not changed.";
            } finally {
                tmp.delete();
            }
            final String m = msg;
            runOnUiThread(() -> {
                info.setText(m);
                pick.setEnabled(true);
            });
        }).start();
    }

    private String report(Map<String, String> b, Map<String, String> a, boolean replaced) {
        StringBuilder s = new StringBuilder(replaced
                ? "Done. The original file was cleaned in place.\n"
                : "Done. A cleaned copy was saved. The original is untouched.\n");
        StringBuilder rs = new StringBuilder();
        int removed = 0;
        for (Map.Entry<String, String> e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                removed++;
                rs.append("\u2713 ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        s.append("\nCleaned (").append(removed).append("):\n");
        s.append(removed == 0 ? "Nothing identifying was found.\n" : rs.toString());
        s.append("\nStill present (").append(a.size()).append("):\n");
        if (a.isEmpty()) s.append("No metadata fields remain.\n");
        for (Map.Entry<String, String> e : a.entrySet())
            s.append("\u2022 ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        s.append("\nAudio and video streams were copied without re-encoding.");
        return s.toString();
    }

    // ------------------------------------------------------------------ cleaning

    // Step 1: copy only audio/video samples into a fresh MP4 in private cache.
    // GPS/extra tracks and their sample data are never copied. setLocation() is never called.
    private void remux(Uri in, File out) throws Exception {
        out.delete();
        int rot = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, in);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (s != null) rot = Integer.parseInt(s);
        } finally {
            rel(r);
        }

        MediaExtractor ex = new MediaExtractor();
        MediaMuxer mx = null;
        boolean started = false;
        try {
            ex.setDataSource(this, in, null);
            mx = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            mx.setOrientationHint(rot);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            Arrays.fill(map, -1);
            int added = 0;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) {
                    map[i] = mx.addTrack(f);
                    ex.selectTrack(i);
                    added++;
                }
            }
            if (added == 0) throw new IOException("No audio/video tracks found");
            mx.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocate(8 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            while (true) {
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                int flags = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                bi.set(0, size, ex.getSampleTime(), flags);
                mx.writeSampleData(map[ex.getSampleTrackIndex()], buf, bi);
                ex.advance();
            }
            mx.stop();
            started = false;
        } finally {
            if (mx != null) {
                if (started) { try { mx.stop(); } catch (Exception ignored) {} }
                try { mx.release(); } catch (Exception ignored) {}
            }
            ex.release();
        }
    }

    // Step 2: neutralise whatever the muxer itself wrote (timestamps, handler names, tag boxes).
    // Boxes are renamed to 'free' and zeroed, so no offsets change and the file stays valid.
    private static void sanitize(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
            walk(r, 0, r.length());
        }
    }

    private static void walk(RandomAccessFile r, long pos, long end) throws IOException {
        while (pos + 8 <= end) {
            r.seek(pos);
            long size = r.readInt() & 0xFFFFFFFFL;
            byte[] t = new byte[4];
            r.readFully(t);
            String type = new String(t, ISO);
            long hdr = 8;
            if (size == 1) { size = r.readLong(); hdr = 16; }
            else if (size == 0) size = end - pos;
            if (size < hdr || pos + size > end) break;
            long body = pos + hdr, bend = pos + size;
            switch (type) {
                case "moov": case "trak": case "mdia":
                    walk(r, body, bend);
                    break;
                case "mvhd": case "tkhd": case "mdhd": {
                    r.seek(body);
                    int v = r.readUnsignedByte();
                    r.seek(body + 4);
                    r.write(new byte[v == 1 ? 16 : 8]);
                    break;
                }
                case "hdlr":
                    if (bend - body > 24) {
                        r.seek(body + 24);
                        r.write(new byte[(int) (bend - body - 24)]);
                    }
                    break;
                case "udta": case "meta": case "uuid": case "XMP_": case "xml ": case "gps ":
                    freeBox(r, pos, body, bend);
                    break;
                default:
                    break;
            }
            pos = bend;
        }
    }

    private static void freeBox(RandomAccessFile r, long pos, long body, long bend) throws IOException {
        r.seek(pos + 4);
        r.write(new byte[]{'f', 'r', 'e', 'e'});
        byte[] z = new byte[65536];
        long p = body;
        while (p < bend) {
            int n = (int) Math.min(z.length, bend - p);
            r.seek(p);
            r.write(z, 0, n);
            p += n;
        }
    }

    // Step 3: sanity check before anything touches the user's file.
    private void verify(Uri in, File out) throws IOException {
        long a = durMs(in, null), b = durMs(null, out);
        if (out.length() == 0 || b < 0) throw new IOException("The cleaned file could not be verified");
        if (a > 0 && Math.abs(a - b) > Math.max(1500, a / 20))
            throw new IOException("Verification failed (duration mismatch)");
    }

    private long durMs(Uri u, File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            if (u != null) r.setDataSource(this, u); else r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? -1 : Long.parseLong(d);
        } catch (Exception e) {
            return -1;
        } finally {
            rel(r);
        }
    }

    private static void rel(MediaMetadataRetriever r) {
        try { r.release(); } catch (Exception ignored) {}
    }

    // Step 4: write result to destination (new file, or over the original) and truncate.
    private void writeTo(Uri dest, File tmp) throws IOException {
        ParcelFileDescriptor p = getContentResolver().openFileDescriptor(dest, "rw");
        if (p == null) throw new IOException("Cannot open destination");
        try (FileInputStream in = new FileInputStream(tmp);
             FileOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(p)) {
            FileChannel ic = in.getChannel(), oc = out.getChannel();
            long n = ic.size(), pos = 0;
            while (pos < n) pos += ic.transferTo(pos, n - pos, oc);
            oc.truncate(n);
            out.getFD().sync();
        }
    }

    // ------------------------------------------------------------------ scanning

    private Map<String, String> scan(Uri u) {
        basicScan = false;
        try {
            ParcelFileDescriptor p = getContentResolver().openFileDescriptor(u, "r");
            if (p != null) {
                try (FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(p)) {
                    trackNo = 0;
                    Map<String, String> m = scanChannel(in.getChannel());
                    if (m != null) return m;
                }
            }
        } catch (Exception ignored) {}
        basicScan = true;
        return basic(u);
    }

    private Map<String, String> scanFile(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            trackNo = 0;
            return scanChannel(in.getChannel());
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, String> basic(Uri u) {
        Map<String, String> m = new LinkedHashMap<>();
        Map<Integer, String> keys = new LinkedHashMap<>();
        keys.put(MediaMetadataRetriever.METADATA_KEY_LOCATION, "GPS location");
        keys.put(MediaMetadataRetriever.METADATA_KEY_DATE, "Creation date");
        keys.put(MediaMetadataRetriever.METADATA_KEY_TITLE, "Title");
        keys.put(MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Artist / author");
        keys.put(MediaMetadataRetriever.METADATA_KEY_WRITER, "Writer");
        keys.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "Composer");
        keys.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album");
        keys.put(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "Album artist");
        keys.put(MediaMetadataRetriever.METADATA_KEY_GENRE, "Genre");
        keys.put(MediaMetadataRetriever.METADATA_KEY_YEAR, "Year");
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, u);
            for (Map.Entry<Integer, String> e : keys.entrySet()) {
                String v = r.extractMetadata(e.getKey());
                if (v != null && !v.isEmpty()) m.put(e.getValue(), v);
            }
        } catch (Exception ignored) {
        } finally {
            rel(r);
        }
        return m;
    }

    private static final class Box { long pos, body, end; String type; }

    private Map<String, String> scanChannel(FileChannel ch) throws IOException {
        byte[] head = rd(ch, 0, 12);
        if (head.length < 12 || !"ftyp".equals(new String(head, 4, 4, ISO))) return null;
        Map<String, String> m = new LinkedHashMap<>();
        scanBoxes(ch, 0, ch.size(), "", m);
        return m;
    }

    private void scanBoxes(FileChannel ch, long pos, long end, String path, Map<String, String> m) throws IOException {
        Box b;
        while ((b = nextBox(ch, pos, end)) != null) {
            pos = b.end;
            String t = b.type;
            switch (t) {
                case "ftyp": {
                    byte[] x = rd(ch, b.body, 4);
                    if (x.length == 4) put(m, "Container brand", new String(x, ISO).trim());
                    break;
                }
                case "moov": case "mdia":
                    scanBoxes(ch, b.body, b.end, path + t + "/", m);
                    break;
                case "trak":
                    trackNo++;
                    scanBoxes(ch, b.body, b.end, path + "trak/", m);
                    break;
                case "mvhd":
                    times(ch, b.body, m);
                    break;
                case "hdlr":
                    if (path.endsWith("mdia/")) handler(ch, b, m);
                    break;
                case "udta":
                    scanBoxes(ch, b.body, b.end, path + "udta/", m);
                    break;
                case "meta":
                    meta(ch, b, m);
                    break;
                case "uuid": case "XMP_": case "xml ": case "gps ": case "GPMF":
                    put(m, "Embedded '" + t.trim() + "' block", (b.end - b.pos) + " bytes");
                    break;
                default:
                    if (path.endsWith("udta/")) udtaItem(ch, b, m);
                    break;
            }
        }
    }

    private void times(FileChannel ch, long body, Map<String, String> m) throws IOException {
        byte[] x = rd(ch, body, 20);
        if (x.length < 12) return;
        long c, md;
        if (x[0] == 1) {
            if (x.length < 20) return;
            c = (u32(x, 4) << 32) | u32(x, 8);
            md = (u32(x, 12) << 32) | u32(x, 16);
        } else {
            c = u32(x, 4);
            md = u32(x, 8);
        }
        if (c > 0) put(m, "Creation time (movie)", fmt(c));
        if (md > 0 && md != c) put(m, "Modified time (movie)", fmt(md));
    }

    private static String fmt(long s) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date((s - 2082844800L) * 1000L));
    }

    private void handler(FileChannel ch, Box b, Map<String, String> m) throws IOException {
        byte[] x = rd(ch, b.body, (int) Math.min(b.end - b.body, 160));
        if (x.length < 24) return;
        String ht = new String(x, 8, 4, ISO).trim();
        String name = new String(x, 24, x.length - 24, ISO).replaceAll("[\\p{Cntrl}]", "").trim();
        if (ht.equals("vide") || ht.equals("soun")) {
            if (!name.isEmpty()) put(m, "Track " + trackNo + " handler name (" + ht + ")", name);
        } else {
            put(m, "Extra data track #" + trackNo + " (" + ht + ")", name.isEmpty() ? "present" : name);
        }
    }

    private void udtaItem(FileChannel ch, Box b, Map<String, String> m) throws IOException {
        String t = b.type;
        if (t.equals("free") || t.equals("skip")) return;
        if (t.charAt(0) == '\u00a9') {
            byte[] x = rd(ch, b.body, (int) Math.min(b.end - b.body, 264));
            put(m, labelFor(t), x.length > 4 ? tidy(new String(x, 4, x.length - 4, UTF8)) : "present");
        } else {
            put(m, "Vendor box '" + t.trim() + "'", (b.end - b.pos) + " bytes");
        }
    }

    private void meta(FileChannel ch, Box b, Map<String, String> m) throws IOException {
        byte[] h = rd(ch, b.body, 8);
        if (h.length < 8) return;
        long start = b.body;
        if (!"hdlr".equals(new String(h, 4, 4, ISO))) start += 4;
        List<String> keys = new ArrayList<>();
        Box c;
        long pos = start;
        while ((c = nextBox(ch, pos, b.end)) != null) {
            pos = c.end;
            if (c.type.equals("keys")) {
                byte[] k = rd(ch, c.body, 8);
                if (k.length < 8) continue;
                long cnt = u32(k, 4), p = c.body + 8;
                for (long i = 0; i < cnt && p + 8 <= c.end; i++) {
                    byte[] e = rd(ch, p, 8);
                    long sz = u32(e, 0);
                    if (sz < 8 || p + sz > c.end) break;
                    byte[] nm = rd(ch, p + 8, (int) Math.min(sz - 8, 200));
                    keys.add(new String(nm, UTF8));
                    p += sz;
                }
            } else if (c.type.equals("ilst")) {
                Box it;
                long ip = c.body;
                while ((it = nextBox(ch, ip, c.end)) != null) {
                    ip = it.end;
                    String name = it.type;
                    long idx = u32(rd(ch, it.pos + 4, 4), 0);
                    if (idx >= 1 && idx <= keys.size()) name = keys.get((int) idx - 1);
                    Box d = nextBox(ch, it.body, it.end);
                    String val = "present";
                    if (d != null && d.type.equals("data")) {
                        byte[] x = rd(ch, d.body, (int) Math.min(d.end - d.body, 264));
                        if (x.length >= 8) {
                            if (u32(x, 0) == 1) val = tidy(new String(x, 8, x.length - 8, UTF8));
                            else val = "(binary data, " + (d.end - d.body - 8) + " bytes)";
                        }
                    }
                    put(m, labelFor(name), val);
                }
            }
        }
    }

    private static String labelFor(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        if (n.equals("\u00a9xyz") || l.contains("location")) return "GPS location";
        if (n.equals("\u00a9mak") || l.endsWith("make")) return "Camera make";
        if (n.equals("\u00a9mod") || l.endsWith("model")) return "Camera model";
        if (n.equals("\u00a9swr") || l.endsWith("software")) return "Software";
        if (n.equals("\u00a9day") || l.contains("creationdate")) return "Creation date";
        if (n.equals("\u00a9too")) return "Encoding tool";
        if (n.equals("\u00a9nam")) return "Title";
        if (n.equals("\u00a9cmt")) return "Comment";
        if (n.equals("\u00a9ART")) return "Artist / author";
        if (n.equals("\u00a9wrt")) return "Writer";
        if (n.equals("\u00a9cpy")) return "Copyright";
        if (l.contains("android.version")) return "Android version tag";
        return "Tag: " + n.replace("\u00a9", "(c)");
    }

    private static String tidy(String s) {
        s = s.replaceAll("[\\p{Cntrl}]", "").trim();
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    private static void put(Map<String, String> m, String k, String v) {
        String key = k;
        int i = 2;
        while (m.containsKey(key)) key = k + " #" + (i++);
        m.put(key, v);
    }

    private static Box nextBox(FileChannel ch, long pos, long end) throws IOException {
        if (pos + 8 > end) return null;
        byte[] h = rd(ch, pos, 16);
        if (h.length < 8) return null;
        long size = u32(h, 0);
        Box b = new Box();
        b.pos = pos;
        b.type = new String(h, 4, 4, ISO);
        long hdr = 8;
        if (size == 1) {
            if (h.length < 16) return null;
            size = (u32(h, 8) << 32) | u32(h, 12);
            hdr = 16;
        } else if (size == 0) {
            size = end - pos;
        }
        if (size < hdr || pos + size > end) return null;
        b.body = pos + hdr;
        b.end = pos + size;
        return b;
    }

    private static byte[] rd(FileChannel ch, long pos, int len) throws IOException {
        long avail = ch.size() - pos;
        if (avail < 0) avail = 0;
        ByteBuffer bb = ByteBuffer.allocate((int) Math.min(len, avail));
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos + bb.position());
            if (n < 0) break;
        }
        return Arrays.copyOf(bb.array(), bb.position());
    }

    private static long u32(byte[] b, int o) {
        return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }
}
