#!/usr/bin/env python3
"""Exercise the real Java acquisition block/helper with deterministic Android clock/root stubs.

No Android device, Gradle, network, application files, or alternate-window API is used.
REAL_DISPLAY_DUMP_SOURCE optionally selects pristine source for the one-shot regression.
"""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


class RealDisplayRootWaitTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = Path(os.environ.get("REAL_DISPLAY_DUMP_SOURCE", Path(__file__).with_name("RealDisplayDump.java")))
        cls.text = cls.source.read_text()
        cls.directory = tempfile.TemporaryDirectory(prefix="real-display-root-test-")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.root = Path(cls.directory.name)
        files = {
            "android/app/UiAutomation.java": """package android.app;
                import android.view.accessibility.AccessibilityNodeInfo;
                public class UiAutomation {
                    public int calls, idleCalls, nulls; public long callMillis;
                    public RuntimeException failure;
                    public final AccessibilityNodeInfo expected = new AccessibilityNodeInfo();
                    public void waitForIdle(long idle, long global) { idleCalls++; }
                    public AccessibilityNodeInfo getRootInActiveWindow() {
                        calls++; android.os.SystemClock.now += callMillis;
                        if (failure != null) throw failure;
                        return calls <= nulls ? null : expected;
                    }
                }""",
            "android/os/SystemClock.java": """package android.os;
                public final class SystemClock {
                    public static long now, slept; public static int sleeps;
                    public static long uptimeMillis() { return now; }
                    public static void sleep(long millis) {
                        if (millis <= 0 || millis > 100) throw new AssertionError("unbounded sleep");
                        now += millis; slept += millis; sleeps++;
                    }
                }""",
            "android/view/accessibility/AccessibilityNodeInfo.java": """package android.view.accessibility;
                public class AccessibilityNodeInfo {
                    public boolean recycled;
                    public void recycle() { recycled = true; }
                    public void getBoundsInScreen(android.graphics.Rect rectangle) { }
                }""",
            "android/graphics/Point.java": "package android.graphics; public class Point { public int x,y; }",
            "android/graphics/Rect.java": "package android.graphics; public class Rect { public String flattenToString() { return \"0 0 1 1\"; } }",
            "android/view/Display.java": """package android.view; public class Display {
                public static final int DEFAULT_DISPLAY=0;
                public void getSize(android.graphics.Point p) { }
                public void getRealSize(android.graphics.Point p) { }
                public int getRotation() { return 0; }
            }""",
            "android/util/Xml.java": """package android.util; public class Xml {
                public static org.xmlpull.v1.XmlPullParser newPullParser() { return null; }
            }""",
            "org/xmlpull/v1/XmlPullParser.java": """package org.xmlpull.v1;
                public interface XmlPullParser {
                    int START_TAG=2, END_DOCUMENT=1;
                    void setInput(java.io.Reader reader);
                    int nextTag(); int next(); int getEventType(); String getName();
                }""",
            "org/json/JSONObject.java": """package org.json; public class JSONObject {
                public JSONObject put(String key, Object value) { return this; }
            }""",
            "com/librestatic/lightforge/tools/RealDisplayDump.java": cls.text,
        }
        # Use the actual acquisition statements from main, including its terminal exception.
        # This also executes the original one-shot implementation when selecting pristine source.
        match = re.search(r"            automation\.waitForIdle\(1000, 10000\);\n(.*?)            Class<\?> globalClass", cls.text, re.S)
        if match is None:
            raise AssertionError("Real acquisition block no longer matches the reviewed contract")
        block = "automation.waitForIdle(1000, 10000);\n" + match[1]
        files["com/librestatic/lightforge/tools/RootWaitHarness.java"] = """package com.librestatic.lightforge.tools;
            import android.app.UiAutomation;
            import android.view.accessibility.AccessibilityNodeInfo;
            import android.os.SystemClock;
            """ + ("import static com.librestatic.lightforge.tools.RealDisplayDump.awaitActiveRoot;" if "awaitActiveRoot(automation)" in block else "") + """
            public class RootWaitHarness {
                static AccessibilityNodeInfo acquire(UiAutomation automation) {
                    AccessibilityNodeInfo root;
            """ + block + """
                    return root;
                }
                static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
                public static void main(String[] args) {
                    UiAutomation automation = new UiAutomation();
                    String name = args[0];
                    if (name.equals("transient")) {
                        automation.nulls = 2;
                        require(acquire(automation) == automation.expected, "returned a different root");
                        require(automation.calls == 3 && SystemClock.now == 200, "retry cadence changed");
                        require(!automation.expected.recycled, "caller ownership lost");
                    } else if (name.equals("timeout") || name.equals("slow-null")) {
                        automation.nulls = Integer.MAX_VALUE;
                        if (name.equals("slow-null")) automation.callMillis = 1700;
                        try { acquire(automation); throw new AssertionError("accepted missing root"); }
                        catch (IllegalStateException expected) {
                            require(expected.getMessage().equals("Null active accessibility root"), "error changed");
                        }
                        if (name.equals("timeout")) require(SystemClock.now == 5000 && automation.calls == 50,
                            "same-connection retries must exhaust exactly the bounded local deadline");
                        else require(automation.calls == 3 && SystemClock.now == 5300 && SystemClock.slept == 200,
                            "framework latency must consume retry deadline, not start another wait");
                    } else if (name.equals("exception")) {
                        RuntimeException original = new SecurityException("framework denied root");
                        automation.failure = original;
                        try { acquire(automation); throw new AssertionError("swallowed platform failure"); }
                        catch (SecurityException actual) { require(actual == original, "replaced exception"); }
                        require(automation.calls == 1 && SystemClock.now == 0, "retried non-null failure");
                    } else if (name.equals("immediate")) {
                        require(acquire(automation) == automation.expected, "returned a different root");
                        require(automation.calls == 1 && SystemClock.now == 0, "delayed available root");
                    } else throw new AssertionError("unknown case");
                    require(automation.idleCalls == 1, "reconnected/repeated idle acquisition");
                    System.out.println(name + ": PASS calls=" + automation.calls + " elapsed=" + SystemClock.now);
                }
            }"""
        for name, text in files.items():
            destination = cls.root / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(text)
        result = subprocess.run(["javac", "-d", str(cls.root / "classes"), *[str(cls.root / name) for name in files]],
                                text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stdout + result.stderr)

    def run_case(self, name):
        result = subprocess.run(["java", "-cp", str(self.root / "classes"), "com.librestatic.lightforge.tools.RootWaitHarness", name],
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(name + ": PASS", result.stdout)

    def test_null_then_exact_active_root(self):
        self.run_case("transient")

    def test_persistent_null_is_bounded_and_never_substituted(self):
        self.run_case("timeout")

    def test_slow_framework_null_consumes_deadline(self):
        self.run_case("slow-null")

    def test_platform_exception_is_not_retried(self):
        self.run_case("exception")

    def test_immediate_root_has_no_added_delay(self):
        self.run_case("immediate")

    def test_terminal_error_remains_on_main_line_36(self):
        self.assertEqual(self.text.splitlines()[35].strip(),
                         'if (root == null) throw new IllegalStateException("Null active accessibility root");')


if __name__ == "__main__":
    unittest.main(verbosity=2)
