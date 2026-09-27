package com.librestatic.lightforge.tools;

import android.app.UiAutomation;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Xml;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileReader;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

/** Shell-only, one-shot observer. Reuses the installed framework serializer, not an APK or target instrumentation. */
@SuppressWarnings("deprecation")
public final class RealDisplayDump {
    public static void main(String[] args) {
        Object wrapper = null;
        Class<?> wrapperClass = null;
        AccessibilityNodeInfo root = null;
        JSONObject receipt = null;
        Throwable failure = null;
        try {
            if (args.length != 1 || !args[0].matches("/(?:sdcard|data/local/tmp)/creation-process-[a-z0-9-]+\\.xml")) {
                throw new IllegalArgumentException("Expected one fresh creation-process XML path");
            }
            File output = new File(args[0]);
            if (output.exists()) throw new IllegalStateException("Output already exists; stale XML is not accepted");
            wrapperClass = Class.forName("com.android.uiautomator.core.UiAutomationShellWrapper");
            wrapper = wrapperClass.getConstructor().newInstance();
            wrapperClass.getMethod("connect").invoke(wrapper);
            wrapperClass.getMethod("setCompressedLayoutHierarchy", boolean.class).invoke(wrapper, false);
            UiAutomation automation = (UiAutomation) wrapperClass.getMethod("getUiAutomation").invoke(wrapper);
            automation.waitForIdle(1000, 10000);
            root = awaitActiveRoot(automation);
            if (root == null) throw new IllegalStateException("Null active accessibility root");
            Class<?> globalClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object global = globalClass.getMethod("getInstance").invoke(null);
            Display display = (Display) globalClass.getMethod("getRealDisplay", int.class).invoke(global, Display.DEFAULT_DISPLAY);
            if (display == null) throw new IllegalStateException("Default display is unavailable");
            Point app = new Point();
            Point real = new Point();
            display.getSize(app);
            display.getRealSize(real);
            if (real.x <= 0 || real.y <= 0) throw new IllegalStateException("Invalid real display dimensions");
            int rotation = display.getRotation();
            Rect rawBounds = new Rect();
            root.getBoundsInScreen(rawBounds);
            Class.forName("com.android.uiautomator.core.AccessibilityNodeInfoDumper")
                .getMethod("dumpWindowToFile", AccessibilityNodeInfo.class, File.class, int.class, int.class, int.class)
                .invoke(null, root, output, rotation, real.x, real.y);
            // The built-in serializer logs some write errors instead of throwing them.
            if (!output.isFile() || output.length() == 0) throw new IllegalStateException("Serializer output is missing");
            int nodes = 0;
            try (FileReader reader = new FileReader(output)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(reader);
                if (parser.nextTag() != XmlPullParser.START_TAG || !"hierarchy".equals(parser.getName())) {
                    throw new IllegalStateException("Invalid hierarchy output");
                }
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.getEventType() == XmlPullParser.START_TAG && "node".equals(parser.getName())) nodes++;
                }
            }
            if (nodes == 0) throw new IllegalStateException("Empty hierarchy output");
            receipt = new JSONObject().put("version", 1).put("observer", "builtin-real-display")
                .put("path", args[0]).put("appWidth", app.x).put("appHeight", app.y)
                .put("realWidth", real.x).put("realHeight", real.y).put("rotation", rotation)
                .put("rawRootBounds", rawBounds.flattenToString()).put("nodes", nodes);
        } catch (Throwable error) {
            failure = error;
        } finally {
            if (root != null) root.recycle();
            if (wrapper != null) {
                try { wrapperClass.getMethod("disconnect").invoke(wrapper); }
                catch (Throwable error) { if (failure == null) failure = error; }
            }
        }
        // No success receipt until the exclusive accessibility connection has disconnected.
        if (failure == null) System.out.println(receipt.toString());
        else failure.printStackTrace(System.err);
        System.exit(failure == null ? 0 : 1);
    }

    /** Idle events do not guarantee that the active window has exposed its root yet.
     * Keep the same connection; never substitute a different window or swallow a platform error.
     * The deadline bounds retries, not a non-cooperative framework/Binder call itself.
     */
    static AccessibilityNodeInfo awaitActiveRoot(UiAutomation automation) {
        final long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
        while (true) {
            AccessibilityNodeInfo candidate = automation.getRootInActiveWindow();
            if (candidate != null) return candidate;
            long remaining = deadline - android.os.SystemClock.uptimeMillis();
            if (remaining <= 0) return null;
            android.os.SystemClock.sleep(Math.min(100L, remaining));
            if (android.os.SystemClock.uptimeMillis() >= deadline) return null;
        }
    }
}
