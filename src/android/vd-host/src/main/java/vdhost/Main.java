package vdhost;

import android.app.ActivityThread;
import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * [T-android-vscreen-tier] Root-tier virtual display host: run by
 * {@code su -c app_process -Djava.class.path=/data/local/tmp/minis_vd_host.jar
 * /system/bin --nice-name=minis-vd-host vdhost.Main --serve}.
 *
 * Running as root (uid 0), the display it creates is owned by root, so the
 * root-lane shell commands (`am start --display`, `input -d`, `screencap -d`)
 * hit the owner branch of every permission check — the same mechanism the
 * Shizuku (shell uid) tier relies on, one privilege level up. The privileged
 * flag ladder (TRUSTED / OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED) is attempted
 * first and steps down on refusal: a ROM that ignores hidden flags still
 * ends up with a plain PUBLIC screen instead of no screen at all.
 *
 * Protocol (mirrored by VdHostProtocol.kt in the app):
 *   /data/local/tmp/minis_vd.state  — "display=", "pid=", "size=", "flags=",
 *                                     "port=", "beat=" lines; rewritten every
 *                                     heartbeat
 *   /data/local/tmp/minis_vd.stop   — sentinel: release + delete state + exit
 *   127.0.0.1:<port>                — loopback screenshot server: the client
 *                                     sends "SHOT\n", the host answers
 *                                     "JPEG <len>\n" + <len> bytes, or
 *                                     "ERR <reason>\n"
 *
 * The ImageReader DRAINS frames (count + latest RGBA copy): a virtual
 * display without a live consumer surface sleeps (WMS display-off token)
 * and then drops every injected input event, which is why the surface is
 * attached at create and never detached. Screenshots ride the host's
 * loopback JPEG lane when it is up (the "port=" line in the state file);
 * `screencap -d` on the root lane stays as the fallback for hosts that
 * predate the lane.
 *
 * Screen metrics are read from the MAIN display at start and never
 * hardcoded — a hardcoded size/dpi poisons app font caches when the task
 * moves back to the main display (vdisplay-android gotcha #4).
 */
public final class Main {

    // Public-flag values (DisplayManager constants; hidden ones by value).
    static final int FLAG_PUBLIC = 1;
    static final int FLAG_OWN_CONTENT_ONLY = 8;
    // Hidden flags by numeric value (not present in the public SDK).
    static final int FLAG_TRUSTED = 1024;
    static final int FLAG_OWN_FOCUS = 16384;
    static final int FLAG_STEAL_TOP_FOCUS_DISABLED = 65536;

    /** Flag ladder, most privileged first; the first ROM-accepted rung wins. */
    static final int[] FLAG_LADDER = {
        FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_TRUSTED | FLAG_OWN_FOCUS | FLAG_STEAL_TOP_FOCUS_DISABLED,
        FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_OWN_FOCUS | FLAG_STEAL_TOP_FOCUS_DISABLED,
        FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY | FLAG_OWN_FOCUS,
        FLAG_PUBLIC | FLAG_OWN_CONTENT_ONLY,
    };

    static final String STATE_PATH = "/data/local/tmp/minis_vd.state";
    static final String STOP_PATH = "/data/local/tmp/minis_vd.stop";
    static final long HEARTBEAT_MS = 30_000;

    static volatile int frames = 0;

    /** Port of the loopback screenshot server; -1 when the bind failed. */
    static volatile int shotPort = -1;

    /** Latest drained frame as raw RGBA_8888 bytes; replaced on every frame. */
    static final Object FRAME_LOCK = new Object();
    static byte[] latestFrame = null;
    static int frameW = 0;
    static int frameH = 0;

    /** Result of one create attempt down the ladder. */
    static final class Created {
        final VirtualDisplay display;
        final int flags;
        Created(VirtualDisplay display, int flags) { this.display = display; this.flags = flags; }
    }

    public static void main(String[] args) throws Exception {
        int[] override = sizeOverride(args);
        Looper.prepareMainLooper();
        // ActivityThread.systemMain() in a bare app_process process hands out
        // the system context (no Application here); as uid 0 the display we
        // create is root-owned and needs no package swap.
        ActivityThread thread = (ActivityThread) ActivityThread.class
            .getMethod("systemMain").invoke(null);
        Context sys = (Context) ActivityThread.class
            .getMethod("getSystemContext").invoke(thread);
        DisplayManager dm = (DisplayManager) sys.getSystemService(Context.DISPLAY_SERVICE);

        int[] size = (override != null) ? override : mainDisplayMetrics(dm);
        int w = size[0], h = size[1], dpi = size[2];

        ImageReader reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        HandlerThread drain = new HandlerThread("minis-vd-drain");
        drain.start();
        reader.setOnImageAvailableListener(r -> {
            Image img = null;
            try {
                img = r.acquireLatestImage();
                if (img != null) {
                    frames++;
                    // Copy the RGBA buffer out — the Image is only valid
                    // until close(), and the JPEG encode happens later on
                    // the SHOT path. Row-by-row copy: rowStride may exceed
                    // width*4 (alignment padding).
                    Image.Plane plane = img.getPlanes()[0];
                    ByteBuffer buf = plane.getBuffer();
                    int rowStride = plane.getRowStride();
                    int iw = img.getWidth(), ih = img.getHeight();
                    byte[] out = new byte[iw * ih * 4];
                    for (int row = 0; row < ih; row++) {
                        buf.position(row * rowStride);
                        buf.get(out, row * iw * 4, iw * 4);
                    }
                    synchronized (FRAME_LOCK) {
                        latestFrame = out;
                        frameW = iw;
                        frameH = ih;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (img != null) img.close();
            }
        }, new Handler(drain.getLooper()));

        Created created = null;
        String lastError = "no rung attempted";
        for (int flags : FLAG_LADDER) {
            try {
                VirtualDisplay vd = dm.createVirtualDisplay(
                    "minis-vd", w, h, dpi, reader.getSurface(), flags, null, null);
                if (vd != null) { created = new Created(vd, flags); break; }
                lastError = "createVirtualDisplay returned null (flags=" + flags + ")";
            } catch (Throwable t) {
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage() + " (flags=" + flags + ")";
            }
        }
        if (created == null) {
            System.out.println("VD_FAILED " + lastError);
            System.out.flush();
            Runtime.getRuntime().halt(2);
        }

        int id = created.display.getDisplay().getDisplayId();
        startShotServer();
        writeState(id, w, h, dpi, created.flags);
        System.out.println("VD_READY display=" + id + " flags=" + created.flags
            + " size=" + w + "x" + h + "@" + dpi);
        System.out.flush();

        long lastBeat = 0;
        while (true) {
            if (new File(STOP_PATH).exists()) {
                new File(STOP_PATH).delete();
                break;
            }
            long now = System.currentTimeMillis();
            if (now - lastBeat >= HEARTBEAT_MS) {
                writeState(id, w, h, dpi, created.flags);
                lastBeat = now;
            }
            Thread.sleep(1000);
        }

        created.display.release();
        reader.close();
        new File(STATE_PATH).delete();
        System.out.println("VD_STOPPED frames=" + frames);
        System.out.flush();
        // app_process keeps the framework threads alive after main returns;
        // an explicit halt is the only clean exit from a bare host process.
        Runtime.getRuntime().halt(0);
    }

    /** Rewrites the state file atomically-ish (same-dir tmp + rename). */
    static void writeState(int displayId, int w, int h, int dpi, int flags) {
        File tmp = new File(STATE_PATH + ".tmp");
        try (FileWriter fw = new FileWriter(tmp)) {
            fw.write("display=" + displayId + "\n");
            fw.write("pid=" + android.os.Process.myPid() + "\n");
            fw.write("size=" + w + "x" + h + "@" + dpi + "\n");
            fw.write("flags=" + flags + "\n");
            fw.write("port=" + shotPort + "\n");
            fw.write("beat=" + (System.currentTimeMillis() / 1000) + "\n");
        } catch (IOException e) {
            System.out.println("STATE_WRITE_FAILED " + e);
        }
        // rename fails silently if a reader holds the old file open on some
        // filesystems — delete-then-rename is the portable fallback.
        File target = new File(STATE_PATH);
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }

    /**
     * Binds the loopback screenshot server (port picked by the kernel) and
     * serves SHOT commands on a daemon thread. A bind failure is not fatal —
     * the state file then carries port=-1 and screenshots fall back to
     * `screencap -d` on the root lane.
     */
    static void startShotServer() {
        try {
            ServerSocket ss = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
            shotPort = ss.getLocalPort();
            Thread t = new Thread(() -> serveShots(ss), "minis-vd-shot");
            t.setDaemon(true);
            t.start();
        } catch (IOException e) {
            System.out.println("SHOT_BIND_FAILED " + e);
        }
    }

    /** One connection = one command: SHOT → "JPEG <len>\n" + bytes, else "ERR". */
    static void serveShots(ServerSocket ss) {
        while (true) {
            try (Socket c = ss.accept()) {
                c.setSoTimeout(10_000);
                // Read the command line byte-by-byte: no buffered reader on
                // this socket, so nothing can ever steal bytes meant for a
                // later binary reply.
                InputStream is = c.getInputStream();
                StringBuilder cmdBuf = new StringBuilder();
                while (true) {
                    int b = is.read();
                    if (b < 0 || b == '\n') break;
                    if (b != '\r') cmdBuf.append((char) b);
                }
                String cmd = cmdBuf.toString();
                OutputStream os = c.getOutputStream();
                if (!"SHOT".equals(cmd)) {
                    os.write("ERR unknown command\n".getBytes());
                } else {
                    byte[] jpeg = latestJpeg();
                    if (jpeg == null) {
                        os.write("ERR no frame rendered yet\n".getBytes());
                    } else {
                        os.write(("JPEG " + jpeg.length + "\n").getBytes());
                        os.write(jpeg);
                    }
                }
                os.flush();
            } catch (Throwable ignored) {
                // One bad client must not kill the lane; keep accepting.
            }
        }
    }

    /** Encodes the latest drained frame to JPEG; null when nothing rendered yet. */
    static byte[] latestJpeg() {
        byte[] rgba;
        int w, h;
        synchronized (FRAME_LOCK) {
            rgba = latestFrame;
            w = frameW;
            h = frameH;
        }
        if (rgba == null) return null;
        try {
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                w, h, android.graphics.Bitmap.Config.ARGB_8888);
            bmp.copyPixelsFromBuffer(ByteBuffer.wrap(rgba));
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, bos);
            bmp.recycle();
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Main display metrics via getRealMetrics (deprecated but functional everywhere). */
    static int[] mainDisplayMetrics(DisplayManager dm) {
        Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
        if (d == null) return new int[]{1080, 1920, 320};
        android.util.DisplayMetrics m = new android.util.DisplayMetrics();
        d.getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels, m.densityDpi};
    }

    /** Parses an optional --size WxH@dpi argument; null when absent. */
    static int[] sizeOverride(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--size".equals(args[i])) {
                java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)x(\\d+)@(\\d+)").matcher(args[i + 1]);
                if (m.matches()) {
                    return new int[]{
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)),
                    };
                }
            }
        }
        return null;
    }

    static boolean has(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }

    private Main() { }
}
