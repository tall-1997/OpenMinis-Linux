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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

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
 *                                     "beat=" lines; rewritten every heartbeat
 *   /data/local/tmp/minis_vd.stop   — sentinel: release + delete state + exit
 *
 * The ImageReader DRAINS frames (count only, never stores): a virtual
 * display without a live consumer surface sleeps (WMS display-off token)
 * and then drops every injected input event, which is why the surface is
 * attached at create and never detached. Screenshots go through
 * `screencap -d` on the root lane, so no frame ever leaves this process.
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
                if (img != null) frames++;
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
