package com.ugallery.tools;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only shell observer: never suppresses TalkBack, injects focus or invokes node actions.
 * Focus receipts observe Android accessibility state, not synthesized or audible speech.
 * Every invocation disconnects before reporting success. No target instrumentation is started.
 */
@SuppressWarnings("deprecation")
public final class TalkBackFocusDump {
    private static int nodes;
    public static void main(String[] args) {
        HandlerThread thread = new HandlerThread("ugallery-talkback-observer");
        UiAutomation automation = null;
        AccessibilityNodeInfo root = null;
        JSONObject result = null;
        Throwable failure = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("No input arguments accepted");
            // app_process has no Application main looper; Android accessibility creates a main Handler.
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
            thread.start();
            Class<?> connectionInterface = Class.forName("android.app.IUiAutomationConnection");
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class, connectionInterface)
                .newInstance(thread.getLooper(), connection);
            // Calling connect() without flags even briefly would suppress the actual service.
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            long deadline = SystemClock.uptimeMillis() + 5000;
            do {
                root = automation.getRootInActiveWindow();
                if (root != null) break;
                SystemClock.sleep(100);
            } while (SystemClock.uptimeMillis() < deadline);
            if (root == null) throw new IllegalStateException("Null active root on preserved-service connection");
            AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            Object focused;
            try { focused = focus == null ? JSONObject.NULL : describe(focus); }
            finally { if (focus != null) focus.recycle(); }
            nodes = 0;
            JSONObject tree = tree(root, 0);
            // Inspect binding while THIS connection is active, not just before/after it.
            Process serviceDump = new ProcessBuilder("/system/bin/dumpsys", "accessibility").redirectErrorStream(true).start();
            String services = read(serviceDump.getInputStream());
            int serviceStatus = serviceDump.waitFor();
            if (serviceStatus != 0 || !services.startsWith("ACCESSIBILITY MANAGER"))
                throw new IllegalStateException("Service binding dump failed: " + serviceStatus + ": " + services);
            result = new JSONObject().put("observer", "talkback-preserved-service")
                .put("connectFlags", UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .put("uptimeMillis", SystemClock.uptimeMillis()).put("focus", focused)
                .put("nodes", nodes).put("tree", tree).put("accessibilityWhileConnected", services);
        } catch (Throwable error) { failure = error; }
        finally {
            if (root != null) root.recycle();
            if (automation != null) {
                try { UiAutomation.class.getMethod("disconnect").invoke(automation); }
                catch (Throwable error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            thread.quitSafely();
            try { thread.join(2000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); if (failure == null) failure = error; }
            if (thread.isAlive() && failure == null) failure = new IllegalStateException("Observer thread did not stop");
        }
        if (failure == null) System.out.println(result.toString());
        else failure.printStackTrace(System.err);
        System.exit(failure == null ? 0 : 1);
    }
    private static String read(InputStream source) throws Exception {
        try (InputStream input = source;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > 1048576) throw new IllegalStateException("Service dump exceeds bound");
                output.write(buffer, 0, count);
            }
            return output.toString("UTF-8");
        }
    }
    private static JSONObject describe(AccessibilityNodeInfo node) throws Exception {
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        JSONArray actions = new JSONArray();
        for (AccessibilityNodeInfo.AccessibilityAction action : node.getActionList())
            actions.put(new JSONObject().put("id", action.getId()).put("label", value(action.getLabel())));
        return new JSONObject().put("package", value(node.getPackageName()))
            .put("id", value(node.getViewIdResourceName())).put("class", value(node.getClassName()))
            .put("text", value(node.getText())).put("description", value(node.getContentDescription()))
            .put("stateDescription", value(node.getStateDescription())).put("selected", node.isSelected())
            .put("checkable", node.isCheckable()).put("checked", node.isChecked())
            .put("focused", node.isFocused()).put("accessibilityFocused", node.isAccessibilityFocused())
            .put("visible", node.isVisibleToUser()).put("enabled", node.isEnabled())
            .put("clickable", node.isClickable()).put("longClickable", node.isLongClickable())
            .put("bounds", bounds.flattenToString()).put("actions", actions);
    }
    private static JSONObject tree(AccessibilityNodeInfo node, int depth) throws Exception {
        if (++nodes > 4096 || depth > 64) throw new IllegalStateException("Tree exceeds observation bound");
        JSONObject result = describe(node); JSONArray children = new JSONArray();
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) throw new IllegalStateException("Tree changed during snapshot");
            try { children.put(tree(child, depth + 1)); }
            finally { child.recycle(); }
        }
        return result.put("children", children);
    }
    private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
}
