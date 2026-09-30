#!/usr/bin/env python3
"""Source contract check, complementary to native blocked-syscall tests."""
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
base = root / "feature/pdfstudio/src/main/kotlin/com/librestatic/lightforge/feature/pdfstudio"
service = (base / "PdfProcessingService.kt").read_text()
required = ['bounded("inspect")', 'bounded("preview")', 'bounded("export", id)']
if not all(value in service for value in required):
    sys.exit("FAIL: inspect, preview and export lack service-side lifetime leases")
assert 'if (operation == "export") 300_000L else 60_000L' in service
assert "android.os.Process.killProcess(android.os.Process.myPid())" in service
assert "watchdog.cancel(id)" in service
watchdog = (base / "PdfOperationWatchdog.kt").read_text()
for value in ("removeOnCancelPolicy = true", "TimeUnit.SECONDS.toNanos(5)", "deadline < lease.deadline", "active[id] === lease", "synchronized(active)", "lease.future?.cancel(false)"):
    assert value in watchdog, value
engine = (base / "PdfEngine.kt").read_text()
assert "runCatching { remote?.cancel(id) }" not in engine
assert "cancellations.execute { runCatching { processor.cancel(id) } }" in engine
manifest = (root / "feature/pdfstudio/src/main/AndroidManifest.xml").read_text()
assert 'android:isolatedProcess="true"' in manifest
assert "PdfDeadlineTestService" not in manifest
print("PASS: inspect/preview 60s; export 300s; cancellation grace <=5s; isolated termination; asynchronous cleanup")
