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
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int PICK = 1, SAVE = 2;
    private Uri src;
    private TextView info;
    private Button pick, clean;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
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
        clean = button("Clean and save a copy");
        clean.setEnabled(false);
        root.addView(pick);
        root.addView(clean);

        info = new TextView(this);
        info.setTextColor(0xFFD6E0EA);
        info.setTextSize(15);
        info.setGravity(Gravity.START);
        info.setPadding(0, pad, 0, 0);
        ScrollView sv = new ScrollView(this);
        sv.addView(info);
        root.addView(sv);
        setContentView(root);

        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            startActivityForResult(i, PICK);
        });
        clean.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_TITLE, "clean_video.mp4"); // generic name, nothing carried over
            startActivityForResult(i, SAVE);
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
            info.setText("Metadata found in this video:\n\n" + describe(src));
            clean.setEnabled(true);
        } else if (req == SAVE) {
            final Uri out = d.getData();
            clean.setEnabled(false);
            pick.setEnabled(false);
            info.setText("Cleaning...");
            new Thread(() -> {
                String msg;
                try {
                    remux(src, out);
                    msg = "Done. Saved a cleaned copy.\n\nMetadata still readable in the copy:\n\n" + describe(out);
                } catch (Exception e) {
                    try { DocumentsContract.deleteDocument(getContentResolver(), out); } catch (Exception ignored) {}
                    msg = "Failed: " + e.getMessage();
                }
                final String m = msg;
                runOnUiThread(() -> { info.setText(m); pick.setEnabled(true); });
            }).start();
        }
    }

    private String describe(Uri u) {
        Map<Integer, String> keys = new LinkedHashMap<>();
        keys.put(MediaMetadataRetriever.METADATA_KEY_LOCATION, "GPS location");
        keys.put(MediaMetadataRetriever.METADATA_KEY_DATE, "Creation date");
        keys.put(MediaMetadataRetriever.METADATA_KEY_TITLE, "Title");
        keys.put(MediaMetadataRetriever.METADATA_KEY_AUTHOR, "Author");
        keys.put(MediaMetadataRetriever.METADATA_KEY_WRITER, "Writer");
        keys.put(MediaMetadataRetriever.METADATA_KEY_COMPOSER, "Composer");
        keys.put(MediaMetadataRetriever.METADATA_KEY_ALBUM, "Album");
        keys.put(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST, "Album artist");
        keys.put(MediaMetadataRetriever.METADATA_KEY_GENRE, "Genre");
        keys.put(MediaMetadataRetriever.METADATA_KEY_YEAR, "Year");
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        StringBuilder sb = new StringBuilder();
        try {
            r.setDataSource(this, u);
            for (Map.Entry<Integer, String> e : keys.entrySet()) {
                String v = r.extractMetadata(e.getKey());
                if (v != null && !v.isEmpty()) sb.append(e.getValue()).append(": ").append(v).append('\n');
            }
        } catch (Exception e) {
            sb.append("(could not read: ").append(e.getMessage()).append(")\n");
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
        return sb.length() == 0 ? "None of the listed fields." : sb.toString();
    }

    // Copies only audio/video samples into a fresh MP4. Nothing else is carried over:
    // no GPS, no device info, no tags, no extra tracks. setLocation() is never called.
    private void remux(Uri in, Uri out) throws Exception {
        int rot = 0;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, in);
            String s = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if (s != null) rot = Integer.parseInt(s);
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }

        MediaExtractor ex = new MediaExtractor();
        ParcelFileDescriptor pfd = null;
        MediaMuxer mx = null;
        boolean started = false;
        try {
            ex.setDataSource(this, in, null);
            pfd = getContentResolver().openFileDescriptor(out, "rw");
            if (pfd == null) throw new Exception("Cannot open output");
            mx = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
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
            if (added == 0) throw new Exception("No audio/video tracks found");

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
            if (pfd != null) { try { pfd.close(); } catch (Exception ignored) {} }
        }
    }
}
