#!/usr/bin/env python3
"""One real process death: draft recovery or an explicitly armed publication. Root builds/installs; this runner never installs or resets apps.

Native instrumentation seeds owned media and exits before the normal app is launched.
The host is the sole accessibility observer for setup and restoration; the killed app is not instrumented. Cleanup is exact MediaStore row CAS; denied
or changed rows are retained and reported. Requires Pillow for owned red/blue preview assertions.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import struct
import threading
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.ugallery.app.pdfacceptance"
ACTIVITY = PACKAGE + "/com.ugallery.app.MainActivity"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
TEST = "com.ugallery.app.CreationProcessRestorationAppDeviceTest#seedOwnedPhotosForHostProcessRestoration"
MOTION_TEST = "com.ugallery.app.CreationProcessRestorationAppDeviceTest#seedOwnedMotionForHostProcessRestoration"
MOTION_VERIFY_TEST = "com.ugallery.app.CreationProcessRestorationAppDeviceTest#verifyOwnedMotionHasNoSavedKeyFrame"
VIDEO_EDITOR_TEST = "com.ugallery.app.CreationProcessRestorationAppDeviceTest#seedOwnedVideoForHostProcessRestoration"
MANUAL_MEMORY_VERIFY_TEST = "com.ugallery.app.CreationProcessRestorationAppDeviceTest#verifyOwnedManualMemoryAfterProcessRestoration"
COLLAGE_PUBLICATION_SEED = "com.ugallery.app.CollagePublicationProcessAppDeviceTest#seedOwnedCollagePublicationForHost"
COLLAGE_PUBLICATION_VERIFY = "com.ugallery.app.CollagePublicationProcessAppDeviceTest#verifyOwnedCollagePublicationBeforeSourceCleanup"
GIF_PUBLICATION_SEED = "com.ugallery.app.GifPublicationProcessAppDeviceTest#seedOwnedGifPublicationForHost"
GIF_PUBLICATION_VERIFY = "com.ugallery.app.GifPublicationProcessAppDeviceTest#verifyOwnedGifPublicationBeforeSourceCleanup"
MOTION_PUBLICATION_SCENARIOS = ("motion-frame-publication", "motion-clip-publication")
MOTION_PUBLICATION_SEED = "com.ugallery.app.MotionPublicationProcessAppDeviceTest#seedOwnedMotionPublicationForHost"
MOTION_PUBLICATION_VERIFY = "com.ugallery.app.MotionPublicationProcessAppDeviceTest#verifyOwnedMotionPublicationBeforeSourceCleanup"
SCENARIOS = ("memory-video", "collage-draft", "gif-draft", "motion-draft", "video-editor-draft", "manual-memory-draft", "manual-memory-commit", "collage-publication", "gif-publication", *MOTION_PUBLICATION_SCENARIOS)
COLLAGE_OUTPUT_PATH = "Pictures/UGallery/Collage/"
GIF_OUTPUT_PATH = "Pictures/UGallery/GIF/"
MOTION_IMAGE_PATH = "Pictures/UGallery/Motion/"
MOTION_VIDEO_PATH = "Movies/UGallery/Motion/"
VIDEO_EDITOR_OUTPUT_PATH = "Movies/UGallery/"
MEMORY_VIDEO_OUTPUT_PATH = "Movies/UGallery/Memories/"
CREATION_OUTPUT_PATHS = (COLLAGE_OUTPUT_PATH, GIF_OUTPUT_PATH, MOTION_IMAGE_PATH, MOTION_VIDEO_PATH, VIDEO_EDITOR_OUTPUT_PATH, MEMORY_VIDEO_OUTPUT_PATH)
DEVICES = {"emulator-5554": ("UGallery_M2_API30", "30"),
           "127.0.0.1:5563": ("UGallery_PDF_API35", "35")}
FIELDS = ("_id", "_display_name", "relative_path", "owner_package_name",
          "generation_added", "generation_modified", "is_pending")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def observer_remote_path(digest):
    require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest),
            "Exact observer SHA256 is required")
    return "/data/local/tmp/ugallery-ui-observer-" + digest + ".jar"


def real_display_receipt(output, expected_path):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid real-display observer receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {
        "version", "observer", "path", "appWidth", "appHeight", "realWidth", "realHeight",
        "rotation", "rawRootBounds", "nodes"}, "Unexpected real-display observer receipt fields")
    require(type(receipt["version"]) is int and receipt["version"] == 1
            and receipt["observer"] == "builtin-real-display" and receipt["path"] == expected_path,
            "Real-display observer identity/path differs")
    for field in ("appWidth", "appHeight", "realWidth", "realHeight", "nodes"):
        require(type(receipt[field]) is int and receipt[field] > 0, "Invalid real-display observer dimensions/count")
    require(receipt["appWidth"] <= receipt["realWidth"] and receipt["appHeight"] <= receipt["realHeight"],
            "Real-display observer reports inconsistent dimensions")
    require(type(receipt["rotation"]) is int and receipt["rotation"] in (0, 1, 2, 3),
            "Invalid real-display observer rotation")
    require(isinstance(receipt["rawRootBounds"], str)
            and re.fullmatch(r"-?\d+ -?\d+ -?\d+ -?\d+", receipt["rawRootBounds"]),
            "Missing raw accessibility root bounds")
    return receipt


def bounds(node):
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    require(match is not None, "Missing UI bounds")
    x1, y1, x2, y2 = map(int, match.groups())
    require(x2 > x1 and y2 > y1, "Empty UI bounds")
    return x1, y1, x2, y2


def ancestors(node, nodes):
    parents = {child: parent for parent in nodes for child in parent}
    result = []
    while node in parents:
        node = parents[node]
        result.append(node)
    return result


def visible_bounds(node, nodes):
    rectangle = bounds(node)
    chain = ancestors(node, nodes)
    for parent in chain:
        if parent.get("scrollable") == "true" or parent is chain[-1]:
            if parent.get("bounds"):
                x1, y1, x2, y2 = bounds(parent)
                a, b, c, d = rectangle
                rectangle = max(a, x1), max(b, y1), min(c, x2), min(d, y2)
    return rectangle


def center_visible(node, nodes):
    a, b, c, d = bounds(node)
    x, y = (a+c)//2, (b+d)//2
    x1, y1, x2, y2 = visible_bounds(node, nodes)
    return x1 <= x < x2 and y1 <= y < y2


def creation_action_text_visible(node, nodes):
    """A clipped Create action may expose a thin button edge but no readable label."""
    for child in node.iter("node"):
        if child is node or child.get("package") != PACKAGE or not child.get("text", "").strip():
            continue
        coordinates = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", child.get("bounds", ""))
        if coordinates:
            x1, y1, x2, y2 = map(int, coordinates.groups())
            if x2 > x1 and y2 > y1 and center_visible(child, nodes):
                return True
    return False


def require_selected_tile(node, tag):
    require(node.get("package") == PACKAGE and node.get("resource-id") == tag
            and node.get("checkable") == "true" and node.get("checked") == "true",
            "Restored selection does not contain the exact owned source")


def parse_row(output):
    if output.strip() == "No result found.":
        return None
    lines = [line for line in output.splitlines() if line.startswith("Row: ")]
    require(len(lines) == 1, "MediaStore query was denied or ambiguous")
    parts = lines[0].split(" ", 2)[2].split(", ")
    row = dict(part.split("=", 1) for part in parts)
    require(set(row) == set(FIELDS), "Unexpected MediaStore query columns")
    return row


def parse_creation_outputs(output, expected_path):
    """A narrow, read-only output inventory; provider errors are never an empty baseline."""
    require(expected_path in CREATION_OUTPUT_PATHS, "Unexpected creation output path")
    if output.strip() == "No result found.":
        return ()
    lines = output.splitlines()
    require(bool(lines), "Creation output inventory is empty or denied")
    rows = []
    ids = set()
    for index, line in enumerate(lines):
        require(line.startswith(f"Row: {index} "), "Unrecognized collage output inventory")
        parts = line.split(" ", 2)[2].split(", ")
        require(len(parts) == len(FIELDS) and all("=" in part for part in parts),
                "Unexpected creation inventory columns")
        row = dict(part.split("=", 1) for part in parts)
        require(set(row) == set(FIELDS), "Unexpected creation inventory columns")
        require(row["owner_package_name"] == PACKAGE and row["relative_path"] == expected_path,
                "Creation output inventory escaped owned path")
        require(re.fullmatch(r"[1-9][0-9]*", row["_id"]) and row["_id"] not in ids,
                "Creation output identity is invalid or duplicated")
        for field in ("generation_added", "generation_modified", "is_pending"):
            require(re.fullmatch(r"[0-9]+", row[field]), "Invalid collage output generation")
        require(row["is_pending"] in ("0", "1"), "Invalid collage output pending state")
        ids.add(row["_id"])
        rows.append(tuple(row[field] for field in FIELDS))
    return tuple(sorted(rows, key=lambda row: int(row[0])))

def parse_collage_outputs(output):
    return parse_creation_outputs(output, COLLAGE_OUTPUT_PATH)


def playback_button_text(node, tag):
    require(tag in ("creation-gif-play", "motion-play"), "Unexpected playback control")
    require(node.get("package") == PACKAGE and node.get("resource-id") == tag
            and node.get("enabled") == "true", "Playback control is not ready")
    labels = {child.get("text", "").strip() for child in node.iter("node")
              if child.get("package") == PACKAGE and child.get("text", "").strip()}
    require(len(labels) == 1, "Playback control text is missing or ambiguous")
    return next(iter(labels))


def gif_play_text(node):
    return playback_button_text(node, "creation-gif-play")


def require_motion_frame_selected(node):
    require(node.get("package") == PACKAGE and node.get("resource-id") == "motion-frame-4"
            and node.get("enabled") == "true", "Expected Motion frame control is not ready")
    require(node.get("selected") == "true" or
            (node.get("checkable") == "true" and node.get("checked") == "true"),
            "Motion frame 4 is not selected")


def verify_motion_source_bytes(data, metadata):
    from PIL import Image
    for field in ("byteSize", "videoOffset", "videoLength"):
        require(type(metadata.get(field)) is int and metadata[field] > 0, "Missing Motion fixture byte envelope")
    size, offset, length = (metadata[field] for field in ("byteSize", "videoOffset", "videoLength"))
    require(isinstance(data, bytes) and 32 <= len(data) == size <= 4_194_304
            and offset >= 9 and length >= 12 and offset + length == size,
            "Motion provider bytes differ from the exact fixture envelope")
    require(data.startswith(b"\xff\xd8") and data[offset-9:offset] == b"\xff\xd9" + bytes(7)
            and data[offset+4:offset+8] == b"ftyp", "Motion source is not the expected JPEG plus MP4 fixture")
    require(b"http://ns.adobe.com/xap/1.0/\x00" in data[:offset]
            and b"MotionPhoto" in data[:offset], "Motion fixture XMP is missing")
    with Image.open(io.BytesIO(data[:offset-7])) as photo:
        require(photo.format == "JPEG" and photo.size == (320, 240), "Unexpected Motion still dimensions")
        photo.verify()
    return hashlib.sha256(data).hexdigest()


def verify_motion_keyframe_receipt(output, fixture_uuid, media_id):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid Motion keyframe verification receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {
        "fixtureUuid", "mediaId", "baselineKeyFrameAbsent", "keyFrameRowsVerified", "sourceCurrent", "status"},
        "Unexpected Motion keyframe receipt fields")
    require(receipt["fixtureUuid"] == fixture_uuid and type(receipt["mediaId"]) is int
            and receipt["mediaId"] == media_id and receipt["status"] == "PASS",
            "Motion keyframe receipt identity/status differs")
    require(all(receipt[field] is True for field in ("baselineKeyFrameAbsent", "keyFrameRowsVerified", "sourceCurrent")),
            "Motion keyframe absence was not verified while source remained current")
    return receipt


def manual_memory_fingerprint(rows, title):
    """The repository's manual-v1 receipt format; identities and both generations matter."""
    ordered = sorted(rows, key=lambda row: row["ordinal"])
    require(len(ordered) == 2 and [row["ordinal"] for row in ordered] == [0, 1]
            and len({row["_id"] for row in ordered}) == 2, "Manual memory fixture must have two distinct ordered sources")
    data = bytearray()
    def text(value):
        encoded = value.encode("utf-8")
        data.extend(struct.pack(">i", len(encoded))); data.extend(encoded)
    def key(row):
        text("external_primary"); data.extend(struct.pack(">q", row["_id"]))
    text("manual-v1")
    data.extend(struct.pack(">i", 2))
    for row in ordered:
        key(row)
        data.extend(struct.pack(">qq", row["generation_added"], row["generation_modified"]))
    data.extend(struct.pack(">i", 2))
    for row in reversed(ordered):
        key(row)
    text(title.strip()); data.extend(b"\x01")
    return "manual-v1:" + hashlib.sha256(data).hexdigest()


def verify_manual_memory_receipt(output, fixture_uuid, rows, title):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid manual memory verification receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {
        "fixtureUuid", "momentId", "requestFingerprint", "orderedMediaIds", "includeSpecialMedia",
        "baselineManualMemoryAbsent", "manualMemoryRowsVerified", "sourcesCurrent", "manualMemoryCleaned", "status"},
        "Unexpected manual memory receipt fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["status"] == "PASS", "Manual memory receipt identity/status differs")
    try:
        require(isinstance(receipt["momentId"], str) and str(uuid.UUID(receipt["momentId"])) == receipt["momentId"],
                "Manual memory receipt ID is not canonical")
    except (ValueError, TypeError, AttributeError) as failure:
        raise RuntimeError("Invalid manual memory receipt ID") from failure
    require(receipt["requestFingerprint"] == manual_memory_fingerprint(rows, title), "Manual memory receipt request differs")
    expected_ids = [row["_id"] for row in sorted(rows, key=lambda row: row["ordinal"], reverse=True)]
    require(isinstance(receipt["orderedMediaIds"], list) and all(type(value) is int for value in receipt["orderedMediaIds"])
            and receipt["orderedMediaIds"] == expected_ids, "Manual memory receipt order differs")
    require(all(receipt[field] is True for field in ("includeSpecialMedia", "baselineManualMemoryAbsent",
            "manualMemoryRowsVerified", "sourcesCurrent", "manualMemoryCleaned")),
            "Manual memory verification/cleanup did not complete with sources present")
    return receipt


def verify_manual_memory_absence_receipt(output, fixture_uuid):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid manual memory absence receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "sourcesCurrent", "manualMemoryAbsent", "status"},
            "Unexpected manual memory absence receipt fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["status"] == "PASS"
            and receipt["sourcesCurrent"] is True and receipt["manualMemoryAbsent"] is True,
            "Manual memory absence/current sources were not verified")
    return receipt


def verify_manual_commit_gate(output, fixture_uuid, rows, title, pid, uid):
    try:
        receipt = json.loads(output)
    except (ValueError, TypeError) as failure:
        raise RuntimeError("Invalid manual commit gate receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "token", "draftId", "phase", "pid", "uid",
            "requestFingerprint", "deadlineElapsedRealtimeMillis"}, "Unexpected manual commit gate fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["phase"] == "AFTER_ROOM_COMMIT" and
            receipt["requestFingerprint"] == manual_memory_fingerprint(rows, title), "Manual commit gate identity/request differs")
    for field in ("token", "draftId"):
        try:
            require(isinstance(receipt[field], str) and str(uuid.UUID(receipt[field])) == receipt[field], "Noncanonical manual commit " + field)
        except (ValueError, TypeError, AttributeError) as failure:
            raise RuntimeError("Invalid manual commit " + field) from failure
    require(type(receipt["pid"]) is int and receipt["pid"] == pid and pid > 1 and type(receipt["uid"]) is int and receipt["uid"] == uid,
            "Manual commit gate process identity differs")
    require(type(receipt["deadlineElapsedRealtimeMillis"]) is int and receipt["deadlineElapsedRealtimeMillis"] > 0,
            "Manual commit gate deadline missing")
    return receipt


def collage_publication_destination_row(destination):
    require(isinstance(destination, dict) and set(destination) == {"uri", "ownerPackage", "displayName", "relativePath", "mimeType",
            "generationAdded", "generationModified", "sizeBytes", "pending", "trashed"}, "Unexpected collage destination fields")
    uri = destination["uri"]
    match = re.fullmatch(r"content://media/(external|external_primary)/images/media/([1-9][0-9]*)", uri) if isinstance(uri, str) else None
    require(match is not None and destination["ownerPackage"] == PACKAGE and destination["relativePath"] == COLLAGE_OUTPUT_PATH
            and destination["mimeType"] == "image/png" and destination["pending"] is False and destination["trashed"] is False,
            "Collage destination is not the exact owned published PNG")
    require(isinstance(destination["displayName"], str) and re.fullmatch(r"UGallery-collage-[0-9a-f-]{36}\.png", destination["displayName"]),
            "Unexpected collage destination name")
    for field in ("generationAdded", "generationModified", "sizeBytes"):
        require(type(destination[field]) is int and destination[field] >= 0, "Invalid collage destination generation/size")
    require(32 <= destination["sizeBytes"] <= 67108864, "Collage output size is outside the bounded PNG contract")
    return dict(_id=int(match[2]), _display_name=destination["displayName"], relative_path=destination["relativePath"],
                owner_package_name=destination["ownerPackage"], generation_added=destination["generationAdded"],
                generation_modified=destination["generationModified"], is_pending=0, uri=uri)


def verify_collage_publication_gate(output, fixture_uuid, pid, uid):
    try: gate = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid collage publication gate") from failure
    require(isinstance(gate, dict) and set(gate) == {"fixtureUuid", "sessionId", "token", "phase", "pid", "uid",
            "deadlineElapsedRealtimeMillis", "destination", "renderSha256", "renderSizeBytes"}, "Unexpected collage gate fields")
    require(gate["fixtureUuid"] == fixture_uuid and gate["phase"] == "AFTER_MEDIASTORE_COMMIT", "Collage gate fixture/phase differs")
    for field in ("sessionId", "token"):
        try: require(isinstance(gate[field], str) and str(uuid.UUID(gate[field])) == gate[field], "Noncanonical collage " + field)
        except (ValueError, TypeError, AttributeError) as failure: raise RuntimeError("Invalid collage " + field) from failure
    require(type(gate["pid"]) is int and gate["pid"] == pid and pid > 1 and type(gate["uid"]) is int and gate["uid"] == uid,
            "Collage gate process identity differs")
    require(type(gate["deadlineElapsedRealtimeMillis"]) is int and gate["deadlineElapsedRealtimeMillis"] > 0,
            "Collage gate deadline missing")
    collage_publication_destination_row(gate["destination"])
    require(isinstance(gate["renderSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", gate["renderSha256"])
            and type(gate["renderSizeBytes"]) is int and gate["renderSizeBytes"] == gate["destination"]["sizeBytes"],
            "Collage gate render identity differs")
    return gate


def verify_collage_publication_receipt(output, fixture_uuid, gate):
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid collage publication verification receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "sessionId", "token", "outputUri", "outputSha256", "sourcesCurrent",
            "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned", "status"}, "Unexpected collage verification fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["sessionId"] == gate["sessionId"] and receipt["token"] == gate["token"]
            and receipt["outputUri"] == gate["destination"]["uri"] and receipt["outputSha256"] == gate["renderSha256"] and receipt["status"] == "PASS",
            "Collage verification identity/status differs")
    require(all(receipt[field] is True for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned")),
            "Collage publication verification/cleanup is incomplete")
    return receipt


def require_collage_handoff_chooser(output):
    require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*(?:ChooserActivity|ResolverActivity)", output) is not None,
            "Collage handoff chooser is not actually resumed")


def verify_collage_handoff_receipt(output, fixture_uuid, gate, action, pid, uid):
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid collage handoff receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "sessionId", "token", "pid", "uid", "phase", "action", "uri",
            "mimeType", "targetReadGrant", "targetClipUris", "chooserReadGrant", "chooserClipUris"}, "Unexpected collage handoff receipt fields")
    uri = gate["destination"]["uri"]
    require(action in ("android.intent.action.VIEW", "android.intent.action.SEND") and receipt["fixtureUuid"] == fixture_uuid
            and receipt["sessionId"] == gate["sessionId"] and receipt["token"] == gate["token"] and receipt["phase"] == "DISPATCHED"
            and receipt["action"] == action and receipt["uri"] == uri and receipt["mimeType"] == "image/png",
            "Collage handoff action/output identity differs")
    require(type(receipt["pid"]) is int and receipt["pid"] == pid and pid > 1 and type(receipt["uid"]) is int and receipt["uid"] == uid,
            "Collage handoff did not originate from the restored process")
    require(receipt["targetReadGrant"] is True and receipt["chooserReadGrant"] is True and
            receipt["targetClipUris"] == [uri] and receipt["chooserClipUris"] == [uri],
            "Collage handoff must grant read access to exactly one identical PNG in both intents")
    return receipt


def gif_publication_destination_row(destination):
    require(isinstance(destination, dict) and set(destination) == {"uri", "ownerPackage", "displayName", "relativePath", "mimeType",
            "generationAdded", "generationModified", "sizeBytes", "pending", "trashed"}, "Unexpected gif destination fields")
    uri = destination["uri"]
    match = re.fullmatch(r"content://media/(external|external_primary)/images/media/([1-9][0-9]*)", uri) if isinstance(uri, str) else None
    require(match is not None and destination["ownerPackage"] == PACKAGE and destination["relativePath"] == GIF_OUTPUT_PATH
            and destination["mimeType"] == "image/gif" and destination["pending"] is False and destination["trashed"] is False,
            "GIF destination is not the exact owned published GIF")
    require(isinstance(destination["displayName"], str) and re.fullmatch(r"UGallery-GIF-[0-9a-f-]{36}\.gif", destination["displayName"]),
            "Unexpected gif destination name")
    for field in ("generationAdded", "generationModified", "sizeBytes"):
        require(type(destination[field]) is int and destination[field] >= 0, "Invalid gif destination generation/size")
    require(destination["generationModified"] >= destination["generationAdded"], "GIF publication generation moved backwards")
    require(32 <= destination["sizeBytes"] <= 67108864, "GIF output size is outside the bounded GIF contract")
    return dict(_id=int(match[2]), _display_name=destination["displayName"], relative_path=destination["relativePath"],
                owner_package_name=destination["ownerPackage"], generation_added=destination["generationAdded"],
                generation_modified=destination["generationModified"], is_pending=0, uri=uri)


def verify_gif_publication_gate(output, fixture_uuid, pid, uid):
    try: gate = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid gif publication gate") from failure
    require(isinstance(gate, dict) and set(gate) == {"fixtureUuid", "sessionId", "token", "phase", "pid", "uid",
            "deadlineElapsedRealtimeMillis", "destination", "renderSha256", "renderSizeBytes"}, "Unexpected gif gate fields")
    require(gate["fixtureUuid"] == fixture_uuid and gate["phase"] == "AFTER_MEDIASTORE_COMMIT", "GIF gate fixture/phase differs")
    for field in ("sessionId", "token"):
        try: require(isinstance(gate[field], str) and str(uuid.UUID(gate[field])) == gate[field], "Noncanonical gif " + field)
        except (ValueError, TypeError, AttributeError) as failure: raise RuntimeError("Invalid gif " + field) from failure
    require(type(gate["pid"]) is int and gate["pid"] == pid and pid > 1 and type(gate["uid"]) is int and gate["uid"] == uid,
            "GIF gate process identity differs")
    require(type(gate["deadlineElapsedRealtimeMillis"]) is int and gate["deadlineElapsedRealtimeMillis"] > 0,
            "GIF gate deadline missing")
    gif_publication_destination_row(gate["destination"])
    require(gate["destination"]["displayName"] == "UGallery-GIF-" + gate["token"] + ".gif", "GIF filename/token differs")
    require(isinstance(gate["renderSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", gate["renderSha256"])
            and type(gate["renderSizeBytes"]) is int and gate["renderSizeBytes"] == gate["destination"]["sizeBytes"],
            "GIF gate render identity differs")
    return gate


def verify_gif_publication_receipt(output, fixture_uuid, gate):
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid gif publication verification receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "sessionId", "token", "outputUri", "outputSha256", "sourcesCurrent",
            "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned", "status"}, "Unexpected gif verification fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["sessionId"] == gate["sessionId"] and receipt["token"] == gate["token"]
            and receipt["outputUri"] == gate["destination"]["uri"] and receipt["outputSha256"] == gate["renderSha256"] and receipt["status"] == "PASS",
            "GIF verification identity/status differs")
    require(all(receipt[field] is True for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned")),
            "GIF publication verification/cleanup is incomplete")
    return receipt


def require_gif_handoff_chooser(output):
    require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*(?:ChooserActivity|ResolverActivity)", output) is not None,
            "GIF handoff chooser is not actually resumed")


def verify_gif_handoff_receipt(output, fixture_uuid, gate, action, pid, uid):
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid gif handoff receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "sessionId", "token", "pid", "uid", "phase", "action", "uri",
            "mimeType", "targetReadGrant", "targetClipUris", "chooserReadGrant", "chooserClipUris"}, "Unexpected gif handoff receipt fields")
    uri = gate["destination"]["uri"]
    require(action in ("android.intent.action.VIEW", "android.intent.action.SEND") and receipt["fixtureUuid"] == fixture_uuid
            and receipt["sessionId"] == gate["sessionId"] and receipt["token"] == gate["token"] and receipt["phase"] == "DISPATCHED"
            and receipt["action"] == action and receipt["uri"] == uri and receipt["mimeType"] == "image/gif",
            "GIF handoff action/output identity differs")
    require(type(receipt["pid"]) is int and receipt["pid"] == pid and pid > 1 and type(receipt["uid"]) is int and receipt["uid"] == uid,
            "GIF handoff did not originate from the restored process")
    require(receipt["targetReadGrant"] is True and receipt["chooserReadGrant"] is True and
            receipt["targetClipUris"] == [uri] and receipt["chooserClipUris"] == [uri],
            "GIF handoff must grant read access to exactly one identical GIF in both intents")
    return receipt


def motion_publication_destination_row(destination, kind):
    require(kind in ("Frame", "Clip"), "Invalid Motion publication kind")
    collection, mime, path, suffix = ("images", "image/jpeg", MOTION_IMAGE_PATH, "jpg") if kind == "Frame" else ("video", "video/mp4", MOTION_VIDEO_PATH, "mp4")
    require(isinstance(destination, dict) and set(destination) == {"uri", "ownerPackage", "displayName", "relativePath", "mimeType",
            "generationAdded", "generationModified", "sizeBytes", "pending", "trashed"}, "Unexpected motion destination fields")
    uri = destination["uri"]
    match = re.fullmatch(r"content://media/(external|external_primary)/" + collection + r"/media/([1-9][0-9]*)", uri) if isinstance(uri, str) else None
    require(match is not None and destination["ownerPackage"] == PACKAGE and destination["relativePath"] == path
            and destination["mimeType"] == mime and destination["pending"] is False and destination["trashed"] is False,
            "Motion destination is not the exact owned published Motion")
    require(isinstance(destination["displayName"], str) and re.fullmatch(r"UGallery-Motion-[0-9a-f-]{36}\." + suffix, destination["displayName"]),
            "Unexpected motion destination name")
    for field in ("generationAdded", "generationModified", "sizeBytes"):
        require(type(destination[field]) is int and destination[field] >= 0, "Invalid motion destination generation/size")
    require(destination["generationModified"] >= destination["generationAdded"], "Motion publication generation moved backwards")
    # Small owned acceptance fixture read bound only; not a Motion product/export limit.
    require(32 <= destination["sizeBytes"] <= 67108864, "Owned Motion fixture exceeds its reader bound")
    return dict(_id=int(match[2]), _display_name=destination["displayName"], relative_path=destination["relativePath"],
                owner_package_name=destination["ownerPackage"], generation_added=destination["generationAdded"],
                generation_modified=destination["generationModified"], is_pending=0, uri=uri)


def verify_motion_publication_gate(output, fixture_uuid, pid, uid, kind):
    try: gate = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid motion publication gate") from failure
    require(isinstance(gate, dict) and set(gate) == {"fixtureUuid", "publicationId", "token", "phase", "pid", "uid",
            "deadlineElapsedRealtimeMillis", "destination", "renderSha256", "renderSizeBytes"}, "Unexpected motion gate fields")
    require(gate["fixtureUuid"] == fixture_uuid and gate["phase"] == "AFTER_MEDIASTORE_COMMIT", "Motion gate fixture/phase differs")
    for field in ("publicationId", "token"):
        try: require(isinstance(gate[field], str) and str(uuid.UUID(gate[field])) == gate[field], "Noncanonical motion " + field)
        except (ValueError, TypeError, AttributeError) as failure: raise RuntimeError("Invalid motion " + field) from failure
    require(type(gate["pid"]) is int and gate["pid"] == pid and pid > 1 and type(gate["uid"]) is int and gate["uid"] == uid,
            "Motion gate process identity differs")
    require(type(gate["deadlineElapsedRealtimeMillis"]) is int and gate["deadlineElapsedRealtimeMillis"] > 0,
            "Motion gate deadline missing")
    motion_publication_destination_row(gate["destination"], kind)
    require(gate["destination"]["displayName"] == "UGallery-Motion-" + gate["token"] + (".jpg" if kind == "Frame" else ".mp4"), "Motion filename/token differs")
    require(isinstance(gate["renderSha256"], str) and re.fullmatch(r"[0-9a-f]{64}", gate["renderSha256"])
            and type(gate["renderSizeBytes"]) is int and gate["renderSizeBytes"] == gate["destination"]["sizeBytes"],
            "Motion gate render identity differs")
    return gate


def verify_motion_publication_receipt(output, fixture_uuid, gate):
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid motion publication verification receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "publicationId", "token", "outputUri", "outputSha256", "sourcesCurrent",
            "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned", "status"}, "Unexpected motion verification fields")
    require(receipt["fixtureUuid"] == fixture_uuid and receipt["publicationId"] == gate["publicationId"] and receipt["token"] == gate["token"]
            and receipt["outputUri"] == gate["destination"]["uri"] and receipt["outputSha256"] == gate["renderSha256"] and receipt["status"] == "PASS",
            "Motion verification identity/status differs")
    require(all(receipt[field] is True for field in ("sourcesCurrent", "outputVerified", "outputCountOne", "publicationRetired", "outputCleaned", "gateCleaned")),
            "Motion publication verification/cleanup is incomplete")
    return receipt


def require_motion_handoff_chooser(output):
    require(re.search(r"(?:mResumedActivity|topResumedActivity)[^\n]*(?:ChooserActivity|ResolverActivity)", output) is not None,
            "Motion handoff chooser is not actually resumed")


def verify_motion_handoff_receipt(output, fixture_uuid, gate, action, pid, uid, kind):
    require(kind in ("Frame", "Clip"), "Invalid Motion publication kind")
    try: receipt = json.loads(output)
    except (ValueError, TypeError) as failure: raise RuntimeError("Invalid motion handoff receipt") from failure
    require(isinstance(receipt, dict) and set(receipt) == {"fixtureUuid", "publicationId", "token", "pid", "uid", "phase", "action", "uri",
            "mimeType", "targetReadGrant", "targetClipUris", "chooserReadGrant", "chooserClipUris"}, "Unexpected motion handoff receipt fields")
    uri = gate["destination"]["uri"]
    require(action in ("android.intent.action.VIEW", "android.intent.action.SEND") and receipt["fixtureUuid"] == fixture_uuid
            and receipt["publicationId"] == gate["publicationId"] and receipt["token"] == gate["token"] and receipt["phase"] == "DISPATCHED"
            and receipt["action"] == action and receipt["uri"] == uri and receipt["mimeType"] == ("image/jpeg" if kind == "Frame" else "video/mp4"),
            "Motion handoff action/output identity differs")
    require(type(receipt["pid"]) is int and receipt["pid"] == pid and pid > 1 and type(receipt["uid"]) is int and receipt["uid"] == uid,
            "Motion handoff did not originate from the restored process")
    require(receipt["targetReadGrant"] is True and receipt["chooserReadGrant"] is True and
            receipt["targetClipUris"] == [uri] and receipt["chooserClipUris"] == [uri],
            "Motion handoff must grant read access to exactly one identical Motion in both intents")
    return receipt


def editor_timecodes(node, tag):
    require(tag in ("video-editor-position-value", "video-editor-trim-value")
            and node.get("package") == PACKAGE and node.get("resource-id") == tag,
            "Unexpected video editor value control")
    text = node.get("text", "")
    matches = re.findall(r"(?<![-0-9:])([0-9]+):([0-5][0-9])\.([0-9]{3})(?![0-9])", text)
    require(len(matches) == 2, "Video editor value must expose two exact millisecond timecodes")
    return text, tuple(int(minutes)*60000 + int(seconds)*1000 + int(millis) for minutes, seconds, millis in matches)


def verify_video_source_bytes(data, metadata):
    require(type(metadata.get("byteSize")) is int and 12 <= metadata["byteSize"] <= 4_194_304
            and type(metadata.get("durationMillis")) is int and 1000 <= metadata["durationMillis"] <= 60000,
            "Invalid owned video fixture metadata")
    require(isinstance(data, bytes) and len(data) == metadata["byteSize"] and data[4:8] == b"ftyp",
            "Video provider bytes differ from the exact MP4 fixture")
    return hashlib.sha256(data).hexdigest()


def collage_crop_description(node):
    require(node.get("package") == PACKAGE
            and node.get("resource-id") == "creation-collage-zoom-value", "Collage crop value is not ready")
    description = node.get("text", "")
    # Compare the UI's localized label + formatted numeric value, not guessed slider pixels.
    label, separator, number = description.rpartition(": ")
    require(bool(label.strip()) and separator and re.fullmatch(r"\d+(?:[.,٫]\d+)?", number),
            "Collage crop control has no observable numeric value")
    require(1 <= float(number.replace(",", ".").replace("٫", ".")) <= 3,
            "Collage crop control is outside its zoom range")
    return description


def saved_stopped_activity(output, task_id):
    """Inspect only the exact target ActivityRecord, never another app's saved task."""
    lines = output.splitlines()
    starts = [i for i, line in enumerate(lines) if "Hist " in line and ACTIVITY in line
              and re.search(r"\bt" + str(task_id) + r"\b", line)]
    require(len(starts) == 1, "Exact owned task ActivityRecord is missing or ambiguous")
    start = starts[0]
    indentation = len(lines[start]) - len(lines[start].lstrip())
    end = next((i for i in range(start + 1, len(lines)) if lines[i].strip()
                and len(lines[i]) - len(lines[i].lstrip()) <= indentation), len(lines))
    block = "\n".join(lines[start:end])
    return (bool(re.search(r"\bmHaveState=true\b", block))
            and bool(re.search(r"\bstate=STOPPED\b", block))
            and bool(re.search(r"\bfinishing=false\b", block))), block


def uninstrumented_process(output, expected_pid=None, expected_uid=None):
    require(output.lstrip().startswith("ACTIVITY MANAGER RUNNING PROCESSES (dumpsys activity processes)")
            and "All known processes:" in output and "mProcessesReady=true" in output,
            "Unrecognized or incomplete process dump")
    lines = output.splitlines()
    require(not any("ActiveInstrumentation{" in line and
                    (TEST_PACKAGE + "/" in line or PACKAGE + "/" in line) for line in lines),
            "Target has an ActiveInstrumentation record")
    pattern = re.compile(r"^  \*APP\* UID (\d+) ProcessRecord\{[^ ]+ (\d+):" + re.escape(PACKAGE) + r"/[^}]+\}")
    matches = [(index, pattern.match(line)) for index, line in enumerate(lines) if pattern.match(line)]
    require(len(matches) <= 1, "Ambiguous target ProcessRecord")
    if not matches:
        require(expected_pid is None, "Expected normal target ProcessRecord missing")
        return None
    start, match = matches[0]
    end = next((index for index in range(start + 1, len(lines)) if lines[index].strip()
                and len(lines[index]) - len(lines[index].lstrip()) <= 2), len(lines))
    block = "\n".join(lines[start:end])
    uid, pid = map(int, match.groups())
    require("packageList={" + PACKAGE + "}" in block and re.search(r"(?m)^    pid=" + str(pid) + r"\b", block),
            "Target ProcessRecord identity fields differ")
    require("ActiveInstrumentation" not in block, "Target process is instrumented")
    require(expected_pid is None or pid == expected_pid, "Normal target PID changed")
    require(expected_uid is None or uid == expected_uid, "Normal target UID changed")
    return dict(pid=pid, uid=uid, processRecord=block)


class Probe:
    def __init__(self, args):
        self.args = args
        self.fixture = str(uuid.uuid4())
        self.name = "creation-process-" + self.fixture
        self.out = Path(args.evidence).resolve()
        self.out.mkdir(parents=True, exist_ok=False)
        self.events = []
        self.phase = "preflight"
        self.process = None
        self.threads = []
        self.stdout = []
        self.stderr = []
        self.manifest = None
        self.old_pid = None
        self.new_pid = None
        self.death_confirmed = False
        self.ui_complete = False
        self.cleanup_complete = False
        self.dumps = []
        self.ui_nodes = []
        self.accessibility_owner = "native"
        self.native_logged = False
        self.native_completed = False
        self.kill_requests = {}
        self.scenario = args.scenario
        self.no_auto_export_verified = False
        self.no_auto_play_verified = False
        self.keyframe_rows_verified = False
        self.manual_memory_verified = False
        self.manual_memory_absence_verified = False
        self.manual_commit_gate = None
        self.commit_recovery_opened = False
        self.collage_publication_gate = None
        self.collage_export_attempted = False
        self.collage_publication_verified = False
        self.gif_publication_gate = None
        self.gif_export_attempted = False
        self.gif_publication_verified = False
        self.motion_publication_gate = None
        self.motion_export_attempted = False
        self.motion_publication_verified = False
        self.motion_publication_kind = "Frame" if self.scenario == "motion-frame-publication" else "Clip" if self.scenario == "motion-clip-publication" else None
        self.manual_save_executed = False

    def record(self, **record):
        self.events.append(record)
        (self.out / "commands.json").write_text(json.dumps(self.events, indent=2) + "\n")

    def call(self, *args, timeout=20, check=True, binary=False, include_stderr=False):
        command = ["rtk", "proxy", "adb", "-s", self.args.serial, *map(str, args)]
        try:
            result = subprocess.run(command, capture_output=True, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            self.record(command=command, input=self.phase,
                        stdout=(error.stdout or b"").decode(errors="replace"),
                        stderr=(error.stderr or b"").decode(errors="replace"), exit=124,
                        result="Command timeout; never treated as confirmed process death")
            raise RuntimeError("Command timed out during " + self.phase) from error
        output = result.stdout if binary else result.stdout.decode(errors="replace")
        error = result.stderr.decode(errors="replace")
        self.record(command=command, input=self.phase,
                    stdout=dict(size=len(output), sha256=hashlib.sha256(output).hexdigest()) if binary else output,
                    stderr=error, exit=result.returncode)
        require(not check or result.returncode == 0, "Command failed during " + self.phase)
        return (output, result.returncode, error) if include_stderr else (output, result.returncode)

    def shell(self, *args, **kwargs):
        return self.call("shell", shlex.join(map(str, args)), **kwargs)

    def guard_device(self):
        require(self.args.serial in DEVICES, "Unassigned serial")
        expected_avd, expected_api = DEVICES[self.args.serial]
        avd = self.shell("getprop", "ro.boot.qemu.avd_name")[0].strip()
        if not avd:
            avd = self.shell("getprop", "ro.kernel.qemu.avd_name")[0].strip()
        api = self.shell("getprop", "ro.build.version.sdk")[0].strip()
        qemu = self.shell("getprop", "ro.kernel.qemu")[0].strip()
        if not qemu:
            qemu = self.shell("getprop", "ro.boot.qemu")[0].strip()
        require((avd, api, qemu) == (expected_avd, expected_api, "1"), "Assigned emulator identity changed")

    def apk(self, package, expected):
        output = self.shell("pm", "path", package)[0]
        paths = [line[8:] for line in output.splitlines() if line.startswith("package:")]
        require(len(paths) == 1, "Expected one exact installed APK for " + package)
        digest = self.shell("sha256sum", paths[0])[0].split()[0]
        require(digest == expected, "Installed APK hash differs: " + package)

    def read_manifest(self):
        output, code = self.call("exec-out", "run-as", PACKAGE, "cat", "files/" + self.name + "/fixture.json", check=False)
        if code:
            return None
        value = json.loads(output)
        require(value["version"] == 1 and value["package"] == PACKAGE and value["fixtureUuid"] == self.fixture,
                "Fixture manifest ownership differs")
        require(value["pid"] > 1 and value["uid"] >= 10000, "Invalid fixture process identity")
        maximum = 1 if self.scenario in ("motion-draft", "video-editor-draft", *MOTION_PUBLICATION_SCENARIOS) else 2
        require(0 <= len(value["rows"]) <= maximum, "Unexpected fixture source count")
        self.manifest = value
        (self.out / "fixture.json").write_text(json.dumps(value, indent=2) + "\n")
        return value

    def start_native(self):
        command = ["rtk", "proxy", "adb", "-s", self.args.serial, "shell",
                   shlex.join(["am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
                               *(["-e", "armManualCommitGap", "true"] if self.scenario == "manual-memory-commit" else []),
                               *(["-e", "motionPublicationKind", self.motion_publication_kind] if self.scenario in MOTION_PUBLICATION_SCENARIOS else []),
                               "-e", "class", MOTION_PUBLICATION_SEED if self.scenario in MOTION_PUBLICATION_SCENARIOS else GIF_PUBLICATION_SEED if self.scenario == "gif-publication" else COLLAGE_PUBLICATION_SEED if self.scenario == "collage-publication" else MOTION_TEST if self.scenario == "motion-draft" else VIDEO_EDITOR_TEST if self.scenario == "video-editor-draft" else TEST, RUNNER])]
        self.native_command = command
        self.process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        def drain(pipe, destination):
            for line in pipe:
                destination.append(line)
            pipe.close()
        for pipe, destination in ((self.process.stdout, self.stdout), (self.process.stderr, self.stderr)):
            thread = threading.Thread(target=drain, args=(pipe, destination), daemon=True)
            thread.start()
            self.threads.append(thread)

    def process_identity(self, pid):
        require(isinstance(pid, int) and pid > 1, "Invalid target PID")
        pids = self.shell("pidof", PACKAGE, check=False)[0].split()
        require(pids == [str(pid)], "Exact fixture process is not the sole target process")
        command = self.call("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/cmdline")[0]
        require(command.rstrip("\x00\r\n") == PACKAGE, "PID command line differs")
        uid = int(self.shell("run-as", PACKAGE, "id", "-u")[0].strip())
        require(uid == self.manifest["uid"], "Fixture UID changed")
        stat = self.call("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/stat")[0]
        return stat.rsplit(")", 1)[1].split()[19]  # Linux stat field 22: process start ticks.

    def proc_exists(self, pid):
        require(isinstance(pid, int) and pid > 1, "Invalid target PID")
        # adb exec-out does not preserve the remote cat exit status on every emulator.
        # A marker proves run-as actually executed test; denied/missing commands are not death.
        script = f'test -d /proc/{pid}; status=$?; printf "UGALLERY_PROC_STATUS:%s\\n" "$status"; exit "$status"'
        output, code = self.shell("run-as", PACKAGE, "sh", "-c", script, check=False)
        require(code in (0, 1) and output.strip() == f"UGALLERY_PROC_STATUS:{code}",
                "Invalid process-directory observation; death unconfirmed")
        return code == 0

    def await_owned_terminal(self, pid, timeout=15):
        deadline = time.monotonic() + timeout
        while self.proc_exists(pid):
            require(time.monotonic() < deadline, "Target PID still alive; death unconfirmed")
            time.sleep(.15)
        self.record(result="Owned PID directory absent after bounded terminal wait", pid=pid)

    def kill_owned(self, pid):
        self.guard_device()
        if pid in self.kill_requests:
            # An issued kill is never repeated just because the death observer timed out/failed.
            self.await_owned_terminal(pid)
            return
        initial = self.process_identity(pid)
        require(self.process_identity(pid) == initial, "Target PID was reused before kill")
        require(self.proc_exists(pid), "Target disappeared before the kill request")
        self.kill_requests[pid] = initial
        if self.args.serial == "emulator-5554":
            # API30 SELinux forbids runas_app -> untrusted_app SIGKILL. ActivityManager's
            # ordinary background-process kill preserves the task; never use force-stop.
            method = "ActivityManager background kill"
            self.shell("am", "kill", "--user", "0", PACKAGE)
        else:
            method = "SIGKILL"
            self.shell("run-as", PACKAGE, "kill", "-9", str(pid))
        self.await_owned_terminal(pid)
        self.record(result="Exact owned PID terminated after " + method + " request", pid=pid, startTicks=initial)

    def native_finished(self):
        if self.native_logged:
            return
        self.process.wait(timeout=60)
        for thread in self.threads:
            thread.join(timeout=2)
        self.record(command=self.native_command, input=self.fixture, stdout="".join(self.stdout),
                    stderr="".join(self.stderr), exit=self.process.returncode,
                    result="Native seed instrumentation exited; not a process-death test")
        (self.out / "native.stdout").write_text("".join(self.stdout))
        (self.out / "native.stderr").write_text("".join(self.stderr))
        self.native_logged = True

    def task_ids(self, resumed=False):
        output = self.shell("dumpsys", "activity", "activities", PACKAGE)[0]
        lines = [line for line in output.splitlines() if ACTIVITY in line and
                 (not resumed or "ResumedActivity" in line)]
        return {int(match.group(1)) for line in lines for match in re.finditer(r"\bt(\d+)\b", line)}

    def require_no_instrumentation(self):
        output = self.shell("dumpsys", "activity", "processes")[0]
        state = uninstrumented_process(output, expected_pid=self.old_pid, expected_uid=self.manifest["uid"])
        self.record(assertion="Recognized process dump; target has no ActiveInstrumentation", target=state)

    def launch_normal(self):
        self.shell("am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
                   "-f", "0x10200000", "-n", ACTIVITY)

    def tap(self, node, long=False):
        require(node in self.ui_nodes, "UI target does not belong to the current observed hierarchy")
        if not center_visible(node, self.ui_nodes):
            attribute = next((key for key in ("resource-id", "content-desc", "text") if node.get(key)), None)
            require(attribute is not None, "Clipped UI target has no stable identity")
            node = self.find(attribute, node.get(attribute))
        require(center_visible(node, self.ui_nodes), "UI target center remains outside clipped viewport")
        require(node.get("enabled") == "true", "UI control disabled")
        if node.get("resource-id") == "create-collage":
            require(creation_action_text_visible(node, self.ui_nodes), "Create collage label remains clipped")
        x1, y1, x2, y2 = bounds(node)
        x, y = (x1+x2)//2, (y1+y2)//2
        if long:
            self.shell("input", "swipe", x, y, x, y, "800")
        else:
            self.shell("input", "tap", x, y)

    def scroll_towards(self, node):
        a, b, c, d = bounds(node)
        x, y = (a+c)//2, (b+d)//2
        for parent in ancestors(node, self.ui_nodes):
            if parent.get("scrollable") != "true":
                continue
            x1, y1, x2, y2 = visible_bounds(parent, self.ui_nodes)
            if x1 <= x < x2 and y1 <= y < y2:
                continue
            require(x2-x1 >= 4 and y2-y1 >= 4, "Scroll viewport is clipped entirely")
            left, right = x1+(x2-x1)//4, x2-(x2-x1)//4
            upper, lower = y1+(y2-y1)//4, y2-(y2-y1)//4
            if y < y1 or y >= y2:
                start, end = ((x1+x2)//2, upper if y < y1 else lower), ((x1+x2)//2, lower if y < y1 else upper)
            else:
                start, end = (left if x < x1 else right, (y1+y2)//2), (right if x < x1 else left, (y1+y2)//2)
            self.record(observer="scroll-clipped-candidate", target=node.get("resource-id"),
                        viewport=[x1, y1, x2, y2], start=start, end=end)
            self.shell("input", "swipe", *start, *end, "250")
            return
        raise RuntimeError("Clipped UI target has no usable scrolling ancestor")

    def setup_normal_app(self):
        self.require_no_instrumentation()
        self.launch_normal()
        # Native seeding ends its task; a normal launcher starts at Photos. Verify the
        # actual grid instead of tapping compact navigation labels with zero-size bounds.
        self.find("resource-id", "timeline_grid", timeout=30, scroll=False)
        rows = sorted(self.manifest["rows"], key=lambda row: row["ordinal"])
        if self.scenario == "video-editor-draft":
            self.setup_video_editor(rows[0])
            self.stop_normal_task()
            return
        if self.scenario in ("motion-draft", *MOTION_PUBLICATION_SCENARIOS):
            self.setup_motion(rows[0])
            if self.scenario in MOTION_PUBLICATION_SCENARIOS:
                self.setup_motion_publication_gate()
            self.stop_normal_task()
            return
        self.tap(self.find("resource-id", "media_external_primary_" + str(rows[0]["_id"]), timeout=45), long=True)
        self.click("media_external_primary_" + str(rows[1]["_id"]))
        self.find("text", self.manifest["selectionLabel"])
        self.tap(self.find("label", self.manifest["createLabel"]))
        if self.scenario in ("collage-draft", "collage-publication"):
            self.setup_collage()
            if self.scenario == "collage-publication":
                self.setup_collage_publication_gate()
        elif self.scenario in ("gif-draft", "gif-publication"):
            self.setup_gif()
            if self.scenario == "gif-publication":
                self.setup_gif_publication_gate()
        elif self.scenario in ("manual-memory-draft", "manual-memory-commit"):
            self.setup_manual_memory()
            if self.scenario == "manual-memory-commit":
                self.setup_manual_memory_commit_gate()
        else:
            self.setup_video()
        self.stop_normal_task()

    def manual_memory_title(self):
        return "Manual process " + self.fixture

    def manual_memory_card_position(self, tag, expected_label, timeout=18):
        deadline = time.monotonic() + timeout
        labels = {self.manifest["manualPhoto1Label"], self.manifest["manualPhoto2Label"]}
        require(expected_label in labels and tag.startswith("manual-moment-source-external_primary:"),
                "Unexpected manual memory card/position")
        swipes = 0
        while time.monotonic() < deadline:
            card = self.find("resource-id", tag, timeout=max(.1, deadline-time.monotonic()))
            a, b, c, d = bounds(card)
            visible_labels = []
            for child in card.iter("node"):
                if child.get("package") != PACKAGE or child.get("text") not in labels:
                    continue
                rectangle = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", child.get("bounds", ""))
                if rectangle:
                    x1, y1, x2, y2 = map(int, rectangle.groups())
                    if x2 > x1 and y2 > y1 and a <= (x1+x2)//2 < c and b <= (y1+y2)//2 < d and center_visible(child, self.ui_nodes):
                        visible_labels.append(child.get("text"))
            if visible_labels:
                require(visible_labels == [expected_label], "Manual memory reviewed order changed")
                return card
            containers = [node for node in ancestors(card, self.ui_nodes)
                          if node.get("package") == PACKAGE and node.get("resource-id") == "manual-moment-list"
                          and node.get("scrollable") == "true"]
            require(len(containers) == 1 and swipes < 3, "Manual memory card position remained hidden after bounded reveal")
            left, top, right, bottom = visible_bounds(containers[0], self.ui_nodes)
            require(right-left >= 4 and bottom-top >= 4, "Manual memory list has no usable viewport")
            self.record(observer="reveal-manual-card-position", target=tag, expected=expected_label,
                        cardBounds=[a, b, c, d], viewport=[left, top, right, bottom])
            self.shell("input", "swipe", (left+right)//2, bottom-(bottom-top)//4,
                       (left+right)//2, top+(bottom-top)//4, "250")
            swipes += 1
            time.sleep(.15)  # The next find must acquire fresh XML, never reuse a clipped card.
        raise RuntimeError("Manual memory card position remained hidden after bounded reveal")

    def manual_memory_review(self, label):
        self.find("resource-id", "manual-moment-screen", scroll=False)
        title = self.find("resource-id", "manual-moment-title")
        require(title.get("text") == self.manual_memory_title(), "Manual memory title changed")
        consent = self.find("resource-id", "manual-moment-include-special", checked=True)
        require(consent.get("checkable") == "true", "Manual memory consent is not a checkbox")
        rows = sorted(self.manifest["rows"], key=lambda row: row["ordinal"])
        for index, row in enumerate(reversed(rows), 1):
            tag = "manual-moment-source-external_primary:" + str(row["_id"])
            self.manual_memory_card_position(tag, self.manifest["manualPhoto" + str(index) + "Label"])
        save = self.find("resource-id", "manual-moment-save")
        require(save.get("enabled") == "true", "Restored manual review is not saveable")
        require(not any(node.get("package") == PACKAGE and node.get("resource-id") == "moment-screen"
                        for node in self.ui_nodes), "Manual memory opened before explicit Save")
        self.record(assertion="Manual review title/order/consent remains saveable", position=label,
                    title=self.manual_memory_title(), order=[1, 0], includeSpecialMedia=True, exit=0)

    def enter_manual_memory_title(self, title):
        expected = self.manual_memory_title()
        require(title.get("text", "") == "", "New manual draft inherited a title")
        self.tap(title)
        deadline = time.monotonic() + 8
        while True:
            current = self.find("resource-id", "manual-moment-title", scroll=False)
            require(current.get("text", "") == "", "Title changed before host typing")
            if current.get("focused") == "true":
                break
            require(time.monotonic() < deadline, "Manual title never received input focus")
            time.sleep(.1)
        # Shell key injection can outrun IME readiness. Acknowledge each exact prefix;
        # never repair, retype or silently accept dropped characters during setup.
        for start in range(0, len(expected), 8):
            chunk = expected[start:start + 8]
            self.shell("input", "text", chunk.replace(" ", "%s"))
            prefix = expected[:start + len(chunk)]
            deadline = time.monotonic() + 8
            while True:
                current = self.find("resource-id", "manual-moment-title", scroll=False)
                actual = current.get("text", "")
                require(current.get("focused") == "true", "Manual title lost input focus")
                require(prefix.startswith(actual), "Manual title differs from injected prefix")
                if actual == prefix:
                    break
                require(time.monotonic() < deadline, "Manual title did not acknowledge injected prefix")
                time.sleep(.1)
        self.record(assertion="Focused manual title acknowledged every exact injected prefix", title=expected, exit=0)

    def dismiss_manual_keyboard_if_shown(self):
        output = self.shell("dumpsys", "input_method")[0]
        shown = re.findall(r"\bmInputShown=(true|false)\b", output)
        require(output.startswith("Current Input Method Manager state:") and len(shown) == 1,
                "IME visibility is unverified; no Back sent")
        self.record(assertion="Observed IME visibility before optional dismissal", inputShown=shown[0], exit=0)
        if shown[0] == "true":
            self.shell("input", "keyevent", "KEYCODE_BACK")
        # With a hardware keyboard, Back would cancel the review rather than hide an IME.
        self.find("resource-id", "manual-moment-screen", scroll=False)

    def setup_manual_memory(self):
        for field in ("manualPhoto1Label", "manualPhoto2Label"):
            require(isinstance(self.manifest.get(field), str) and self.manifest[field], "Manual memory label missing: " + field)
        self.manual_output_baseline = {path: self.creation_outputs(path) for path in CREATION_OUTPUT_PATHS}
        self.click("create-memory")
        self.find("resource-id", "manual-moment-screen", scroll=False)
        title = self.find("resource-id", "manual-moment-title")
        self.enter_manual_memory_title(title)
        self.dismiss_manual_keyboard_if_shown()
        consent = self.find("resource-id", "manual-moment-include-special")
        require(consent.get("checkable") == "true" and consent.get("checked") == "false",
                "New manual draft inherited consent")
        self.tap(consent)
        rows = sorted(self.manifest["rows"], key=lambda row: row["ordinal"])
        self.click("manual-moment-up-external_primary:" + str(rows[1]["_id"]))
        self.manual_memory_review("before-process-death")
        self.draft_evidence = dict(title=self.manual_memory_title(), order=[1, 0], includeSpecialMedia=True,
                                   saveExecuted=False, commitInterruptionVerified=False, draftUuidUiCompared=False)

    def setup_manual_memory_commit_gate(self):
        require(self.manifest.get("armManualCommitGap") is True, "Manual commit pause was not explicitly armed by seed")
        pids = self.shell("pidof", PACKAGE)[0].split()
        require(len(pids) == 1, "Commit-gate app process is ambiguous")
        pid = int(pids[0])
        self.process_identity(pid)
        self.manual_save_executed = True
        self.click("manual-moment-save")
        deadline = time.monotonic() + 30
        path = "files/" + self.name + "/manual-commit-gate.json"
        while time.monotonic() < deadline:
            exists_output, exists_code, exists_error = self.shell("run-as", PACKAGE, "test", "-f", path,
                                                                   check=False, include_stderr=True)
            require(exists_code in (0, 1) and not exists_output.strip() and not exists_error.strip(),
                    "Manual commit gate existence probe failed")
            if exists_code == 1:
                time.sleep(.25)
                continue
            output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
            if code == 0:
                require(not error.strip(), "Manual commit gate read returned an error")
                self.manual_commit_gate = verify_manual_commit_gate(output, self.fixture, self.manifest["rows"],
                    self.manual_memory_title(), pid, self.manifest["uid"])
                (self.out / "manual-commit-gate.json").write_text(output)
                break
            raise RuntimeError("Manual commit gate disappeared or could not be read")
        require(self.manual_commit_gate is not None, "Post-commit gate was not observed")
        self.find("resource-id", "manual-moment-progress", scroll=False)
        save = self.find("resource-id", "manual-moment-save", scroll=False)
        require(save.get("enabled") == "false" and not any(node.get("package") == PACKAGE and
                node.get("resource-id") == "moment-screen" for node in self.ui_nodes), "Commit callback completed before the pause")
        self.require_manual_commit_gate_held()
        self.draft_evidence.update(saveExecuted=True, postCommitGate=self.manual_commit_gate,
                                    commitInterruptionVerified=False, callbackAcknowledged=False)

    def require_manual_commit_gate_held(self):
        gate = self.manual_commit_gate
        require(gate is not None, "Manual commit gate was not captured")
        uptime = self.shell("cat", "/proc/uptime")[0].split()
        require(len(uptime) == 2 and re.fullmatch(r"[0-9]+\.[0-9]+", uptime[0]) is not None,
                "Device uptime could not validate the commit pause deadline")
        require(int(float(uptime[0])*1000) < gate["deadlineElapsedRealtimeMillis"], "Manual commit gate deadline expired")
        for name in ("manual-commit-outcome.json", "manual-commit-release.json"):
            output, code = self.shell("run-as", PACKAGE, "test", "!", "-e", "files/" + self.name + "/" + name, check=False)
            require(code == 0 and not output.strip(), "Manual commit gate was cancelled or released")

    def verify_manual_memory_commit_restored(self):
        require(self.manual_commit_gate is not None and self.manual_save_executed, "Missing original post-commit gate")
        self.find("resource-id", "manual-memory-recovery-committed", timeout=30, scroll=False)
        open_button = self.find("resource-id", "manual-memory-recovery-open", scroll=False)
        require(open_button.get("enabled") == "true", "Committed recovery is not explicitly openable")
        self.tap(open_button)
        self.commit_recovery_opened = True
        self.verify_manual_memory_result()

    def verify_manual_memory_restored(self):
        self.manual_memory_review("after-process-death")
        self.phase = "manual-memory-explicit-save-and-open"
        self.manual_save_executed = True  # A failed tap/observer response may still have delivered Save.
        self.click("manual-moment-save")
        self.verify_manual_memory_result()

    def verify_manual_memory_result(self):
        self.find("resource-id", "moment-screen", timeout=30, scroll=False)
        self.find("text", self.manual_memory_title())
        rows = sorted(self.manifest["rows"], key=lambda row: row["ordinal"], reverse=True)
        for index, row in enumerate(rows):
            tag = "moment-image-loaded-external_primary:" + str(row["_id"]) + "-" + str(row["generation_modified"])
            self.find("resource-id", tag, timeout=30, scroll=False)
            if index == 0:
                self.click("moment-next")
        # Moment has no global selection toolbar, regardless of whether Save consumed the
        # selection. Its absence here is not evidence that the selection model was cleared.
        require(not any(node.get("package") == PACKAGE and node.get("text") == self.manifest["selectionLabel"]
                        for node in self.ui_nodes), "Global selection toolbar is visible inside Moment")
        self.record(assertion="Moment excludes the global selection toolbar", scenario=self.scenario, exit=0)
        self.phase = "manual-memory-back-collections-selection"
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.find("resource-id", "memories-browser-screen", timeout=30, scroll=False)
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.find("resource-id", "collections-all-memories", timeout=30)
        require(not any(node.get("package") == PACKAGE and node.get("resource-id") in
                        ("moment-screen", "memories-browser-screen", "timeline_grid") for node in self.ui_nodes),
                "Selection must be checked on Root Collections after returning from Moment")
        selection_present = any(node.get("package") == PACKAGE and node.get("text") == self.manifest["selectionLabel"]
                                for node in self.ui_nodes)
        if self.scenario == "manual-memory-commit":
            require(selection_present, "Committed recovery unexpectedly consumed the current selection on Collections")
        else:
            require(not selection_present, "Committed manual memory did not consume selection on Collections")
        # Tapping Photos would call selectRoot and clear selection; do not alter it to inspect
        # tiles. This host proves the count on Collections, not the exact selected key set.
        self.record(assertion="Selection count verified on Root Collections after two real Back actions",
                    selectionRetained=selection_present, scenario=self.scenario, exactSelectedKeysVerified=False, exit=0)
        for row in rows:
            require(self.source_hash(row) == row["sha256"], "Manual memory original changed")
        actual = {path: self.creation_outputs(path) for path in CREATION_OUTPUT_PATHS}
        require(actual == self.manual_output_baseline, "Manual memory produced unexpected media outputs")
        self.no_auto_export_verified = True
        self.record(assertion="One explicit Save opens reviewed ordered memory; original hashes/output inventory unchanged",
                    title=self.manual_memory_title(), order=[1, 0], before=self.manual_output_baseline, after=actual, exit=0)

    def verify_manual_memory_absence_before_source_cleanup(self):
        require(self.native_completed and not self.manual_save_executed and self.accessibility_owner == "host",
                "Absence recovery requires completed seed and no attempted Save")
        self.accessibility_owner = "native-verifier"
        self.phase = "native-manual-memory-absence-before-source-cleanup"
        self.record(observer="handoff-to-failed-draft-absence-verifier", requiresSourcesPresent=True, saveAttempted=False)
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
                                         "-e", "manualMemoryExpected", "absent", "-e", "class", MANUAL_MEMORY_VERIFY_TEST, RUNNER,
                                         timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output
                and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "Manual memory absence verifier did not complete successfully; sources retained")
        path = "files/" + self.name + "/manual-memory-absence.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path,
                                         check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Manual memory absence receipt unavailable; sources retained")
        receipt = verify_manual_memory_absence_receipt(output, self.fixture)
        (self.out / "manual-memory-absence.json").write_text(json.dumps(receipt, indent=2) + "\n")
        self.manual_memory_absence_verified = True
        self.record(assertion="No attributable manual memory; sources still current before failed-draft cleanup", receipt=receipt, exit=0)

    def verify_manual_memory_before_source_cleanup(self):
        require(self.ui_complete and self.death_confirmed and self.native_completed and self.manual_save_executed
                and self.accessibility_owner == "host", "Manual verifier requires completed normal-app Save/restoration")
        self.accessibility_owner = "native-verifier"
        self.phase = "native-manual-memory-check-and-exact-memory-cleanup-before-sources"
        self.record(observer="handoff-to-post-ui-native-verifier", normalAppUiComplete=True, requiresSourcesPresent=True)
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
                                         "-e", "class", MANUAL_MEMORY_VERIFY_TEST, RUNNER,
                                         timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output
                and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "Manual memory native verifier did not complete successfully; sources retained")
        path = "files/" + self.name + "/manual-memory-verification.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path,
                                         check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Manual memory receipt unavailable; sources retained")
        receipt = verify_manual_memory_receipt(output, self.fixture, self.manifest["rows"], self.manual_memory_title())
        (self.out / "manual-memory-verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        if self.scenario == "manual-memory-commit":
            require(self.commit_recovery_opened and self.manual_commit_gate is not None and receipt["momentId"] == self.manual_commit_gate["draftId"],
                    "Recovered memory differs from original post-commit gate")
        self.manual_memory_verified = True
        self.record(assertion="Exact manual memory verified before deletion, memory cleanup confirmed with sources current",
                    receipt=receipt, exit=0)

    def setup_video(self):
        self.memory_video_output_baseline = self.creation_outputs(MEMORY_VIDEO_OUTPUT_PATH)
        self.click("create-memory-video")
        self.find("resource-id", "memory-video-screen", scroll=False)
        self.preview((255, 0, 0), "before-position1-red")
        self.click("memory-video-later")
        self.find("text", self.manifest["position2"])
        self.click("memory-video-seconds-4")
        self.find("resource-id", "memory-video-seconds-4", checked=True)
        self.preview((255, 0, 0), "before-position2-red")
        self.click("memory-video-previous")
        self.find("text", self.manifest["position1"])
        self.preview((0, 0, 255), "before-position1-blue")
        self.click("memory-video-next")
        self.find("text", self.manifest["position2"])
        self.preview((255, 0, 0), "before-final-position2-red")
        self.draft_evidence = dict(order=[1, 0], seconds=4, current=1)
        self.require_no_memory_video_export("before-home")

    def stop_normal_task(self):
        pids = self.shell("pidof", PACKAGE)[0].split()
        require(len(pids) == 1, "Normal app process is ambiguous")
        self.old_pid = int(pids[0])
        require(self.old_pid != self.manifest["pid"], "Seed instrumentation process was reused")
        self.process_identity(self.old_pid)
        tasks = self.task_ids(resumed=True)
        require(len(tasks) == 1, "Normal app task is ambiguous")
        self.task_id = next(iter(tasks))
        self.shell("input", "keyevent", "KEYCODE_HOME")
        deadline = time.monotonic() + 15
        while True:
            output = self.shell("dumpsys", "activity", "activities", PACKAGE)[0]
            stopped_saved, block = saved_stopped_activity(output, self.task_id)
            if stopped_saved:
                break
            require(time.monotonic() < deadline, "Normal task did not stop/save before process death")
            time.sleep(.2)
        self.require_no_instrumentation()
        self.record(assertion="Normal uninstrumented task stopped with saved state", pid=self.old_pid,
                    taskId=self.task_id, activityRecord=block, scenario=self.scenario, **self.draft_evidence)
        (self.out / "normal-process.json").write_text(json.dumps(dict(
            pid=self.old_pid, seedPid=self.manifest["pid"], taskId=self.task_id,
            state="STOPPED", haveState=True, instrumentationActive=False,
            scenario=self.scenario, **self.draft_evidence), indent=2) + "\n")

    def guard_observer(self):
        digest = getattr(self.args, "observer_sha256", None)
        if digest is None:
            return None
        require(self.args.serial in DEVICES, "Real-display observer requires an assigned API30/API35 lane")
        path = observer_remote_path(digest)
        output, code = self.shell("sha256sum", path)
        require(code == 0 and output.split() == [digest, path], "Installed observer hash/path mismatch")
        return path

    def tree(self, timeout=75):
        require(self.accessibility_owner == "host" and self.native_completed,
                "Host accessibility begins only after native seed instrumentation completes")
        deadline = time.monotonic() + timeout
        null_root = "ERROR: null root node returned by UiTestAutomationBridge."
        # Retry only this observed terminal accessibility error, never the fixture or kill.
        # Each command is synchronous; at most two observers run sequentially.
        for attempt in (1, 2):
            remaining = deadline - time.monotonic()
            require(remaining > 0, "UI observer budget exhausted; UI state unverified")
            path = "/sdcard/" + self.name + "-" + str(uuid.uuid4()) + ".xml"
            self.dumps.append(path)
            observer_path = self.guard_observer()
            remaining = deadline - time.monotonic()
            require(remaining > 0, "UI observer budget exhausted before dump")
            receipt = None
            if observer_path is None:
                output, code, error = self.shell("uiautomator", "dump", path,
                                               timeout=min(35, remaining), check=False, include_stderr=True)
            else:
                output, code, error = self.shell("env", "CLASSPATH=/system/framework/uiautomator.jar:" + observer_path,
                                               "app_process", "/system/bin", "com.ugallery.tools.RealDisplayDump", path,
                                               timeout=min(35, remaining), check=False, include_stderr=True)
            builtin_null = observer_path is None and code == 0 and output.strip() == "" and error.strip() == null_root
            real_display_null = observer_path is not None and code == 1 and output == "" and re.fullmatch(
                r"java\.lang\.IllegalStateException: Null active accessibility root\n"
                r"\tat com\.ugallery\.tools\.RealDisplayDump\.main\(RealDisplayDump\.java:36\)\n"
                r"\tat com\.android\.internal\.os\.RuntimeInit\.nativeFinishInit\(Native Method\)\n"
                r"\tat com\.android\.internal\.os\.RuntimeInit\.main\(RuntimeInit\.java:[0-9]+\)\n?", error) is not None
            if builtin_null or real_display_null:
                self.record(observer="transient-null-root", attempt=attempt, path=path,
                            result="Terminal dump returned no root; XML not read; UI state unverified")
                remaining = deadline - time.monotonic()
                require(attempt < 2 and remaining > .5,
                        "UI observer null-root persisted within bounded wait; UI state unverified")
                time.sleep(.5)
                continue
            require(code == 0 and not error.strip(), "UI observer dump success unconfirmed; XML not read")
            if observer_path is None:
                require(output.strip() == "UI hierchary dumped to: " + path,
                        "UI observer dump success unconfirmed; XML not read")
            else:
                receipt = real_display_receipt(output, path)
                self.record(observer="verified-real-display-serializer", receipt=receipt)
            remaining = deadline - time.monotonic()
            require(remaining > 0, "UI observer budget exhausted before XML read")
            xml, code, error = self.call("exec-out", "cat", path, timeout=min(5, remaining),
                                         check=False, include_stderr=True)
            require(code == 0 and not error.strip() and xml.lstrip().startswith("<"),
                    "UI observer XML unavailable: missing/denied file or non-XML response")
            try:
                root = ET.fromstring(xml)
            except ET.ParseError as failure:
                self.record(observer="invalid-xml", attempt=attempt, path=path, reason=str(failure))
                raise RuntimeError("UI observer returned invalid XML; UI state unverified") from failure
            nodes = list(root.iter("node"))
            require(root.tag == "hierarchy" and nodes, "UI observer XML has no usable hierarchy")
            if receipt is not None:
                require(len(nodes) == receipt["nodes"], "Real-display observer receipt/XML node count mismatch")
            (self.out / "last-ui.xml").write_text(xml)
            self.record(observer="verified-hierarchy", attempt=attempt, path=path, nodes=len(nodes))
            self.ui_nodes = nodes
            return nodes

    def expand_creation_sheet(self, candidate):
        """Reveal the measured Create sheet, never use the unmeasured action as tap coordinates."""
        require(candidate in self.ui_nodes and candidate.get("package") == PACKAGE
                and candidate.get("resource-id") == "create-collage", "Unexpected hidden Create action")
        chain = ancestors(candidate, self.ui_nodes)
        # This observed acceptance lane uses the platform's English handle description.
        # Another locale must supply its actual label rather than guess a screen coordinate.
        sheets = [parent for parent in chain if any(n.get("resource-id") == "create-memory-video"
                  and n.get("package") == PACKAGE for n in parent.iter("node"))
                  and any(n.get("content-desc") == "Drag handle" and n.get("package") == PACKAGE
                          for n in parent.iter("node"))]
        require(bool(sheets), "Hidden action has no observed Create sheet")
        sheet = sheets[0]
        handles = [n for n in sheet.iter("node") if n.get("package") == PACKAGE
                   and n.get("content-desc") == "Drag handle"]
        require(len(handles) == 1, "Create sheet handle is missing or ambiguous")
        handle = handles[0]
        require(center_visible(handle, self.ui_nodes), "Create sheet handle is clipped")
        x1, y1, x2, y2 = bounds(handle)
        start = ((x1+x2)//2, (y1+y2)//2)
        viewport = visible_bounds(chain[-1], self.ui_nodes)
        left, top, right, bottom = viewport
        end = (start[0], top+(bottom-top)//4)
        require(left <= start[0] < right and top <= start[1] < bottom
                and left <= end[0] < right and top <= end[1] < start[1],
                "Create sheet has no measured upward expansion space")
        self.record(observer="expand-observed-create-sheet", target=candidate.get("resource-id"),
                    sheet=bounds(sheet), viewport=viewport, handle=bounds(handle), start=start, end=end)
        self.shell("input", "swipe", *start, *end, "350")

    def find(self, attribute, value, checked=False, timeout=18, scroll=True):
        deadline = time.monotonic() + timeout
        backwards = False
        previous_signature = None
        waiting_checked = False
        expanded_creation_sheet = False
        waiting_unmeasured = False
        while time.monotonic() < deadline:
            nodes = self.tree()
            self.ui_nodes = nodes
            decline = [node for node in nodes if node.get("package") == PACKAGE
                       and node.get("text") == self.manifest.get("declineLabel")]
            if len(decline) == 1:
                self.tap(decline[0])
                time.sleep(.15)
                continue
            selected_attribute = attribute
            if attribute == "label":
                selected_attribute = "content-desc" if any(node.get("package") == PACKAGE and node.get("content-desc") == value for node in nodes) else "text"
            matches = [node for node in nodes if node.get("package") == PACKAGE and node.get(selected_attribute) == value]
            if len(matches) == 1:
                candidate = matches[0]
                coordinates = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", candidate.get("bounds", ""))
                if coordinates:
                    x1, y1, x2, y2 = map(int, coordinates.groups())
                    if x1 == x2 or y1 == y2 or (candidate.get("resource-id") == "create-collage"
                            and not creation_action_text_visible(candidate, nodes)):
                        require(scroll, "UI target is unmeasured and scrolling is disabled")
                        waiting_unmeasured = True
                        containers = [parent for parent in ancestors(candidate, nodes)
                                      if parent.get("scrollable") == "true"
                                      and parent.get("package") == PACKAGE]
                        if containers:
                            # Prefer real content scrolling even for Create actions in an expanded
                            # sheet. Zero bounds carry no direction or usable tap coordinates.
                            left, top, right, bottom = visible_bounds(containers[0], nodes)
                            require(right-left >= 4 and bottom-top >= 4, "Unmeasured target scroll viewport is clipped")
                            self.shell("input", "swipe", (left+right)//2, bottom-(bottom-top)//4,
                                       (left+right)//2, top+(bottom-top)//4, "250")
                        elif candidate.get("resource-id") == "create-collage" and not expanded_creation_sheet:
                            self.expand_creation_sheet(candidate)
                            expanded_creation_sheet = True
                        time.sleep(.15)
                        continue
                if not center_visible(candidate, nodes):
                    require(scroll, "UI target is clipped and scrolling is disabled")
                    self.scroll_towards(candidate)
                    time.sleep(.15)
                    continue  # Reacquire hierarchy after moving the candidate; never tap old bounds.
                if checked and candidate.get("checked") != "true":
                    waiting_checked = True
                    time.sleep(.15)
                    continue  # Present but not checked is a state wait, not a search/scroll.
                return candidate
            if scroll:
                signature = tuple(tuple(sorted(n.attrib.items())) for n in nodes if n.get("package") == PACKAGE)
                if signature == previous_signature:
                    backwards = not backwards
                previous_signature = signature
                scrollables = [n for n in nodes if n.get("package") == PACKAGE and n.get("scrollable") == "true"]
                if scrollables:
                    x1, y1, x2, y2 = visible_bounds(max(scrollables, key=lambda n: (bounds(n)[2]-bounds(n)[0])*(bounds(n)[3]-bounds(n)[1])), nodes)
                    require(x2-x1 >= 4 and y2-y1 >= 4, "Search scroll viewport is clipped entirely")
                    upper, lower = y1+(y2-y1)//4, y2-(y2-y1)//4
                    self.shell("input", "swipe", (x1+x2)//2, upper if backwards else lower,
                               (x1+x2)//2, lower if backwards else upper, "250")
            time.sleep(.15)
        if waiting_unmeasured:
            raise RuntimeError(f"UI target remained unmeasured after bounded reveal: {attribute}={value}")
        if waiting_checked:
            raise RuntimeError(f"UI target present but checked state did not become true: {attribute}={value}")
        raise RuntimeError(f"Restored UI missing {attribute}={value}, checked={checked}")

    def click(self, tag):
        self.tap(self.find("resource-id", tag))

    def preview(self, expected, label, preview_tag=None, preview_description=None):
        from PIL import Image
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if preview_tag:
                node = self.find("resource-id", preview_tag)
            elif preview_description is not None:
                node = self.find("content-desc", preview_description, scroll=False)
            else:
                node = self.find("content-desc", self.manifest["previewLabel"])
            x1, y1, x2, y2 = bounds(node)
            png = self.call("exec-out", "screencap", "-p", binary=True)[0]
            (self.out / (label + ".png")).write_bytes(png)
            with Image.open(io.BytesIO(png)) as bitmap:
                actual = bitmap.convert("RGB").getpixel(((x1+x2)//2, (y1+y2)//2))
            if all(abs(a-b) < 30 for a, b in zip(actual, expected)):
                self.record(assertion="Owned source preview order", position=label, rgb=actual, exit=0)
                return
            time.sleep(.15)
        raise RuntimeError("Restored preview color/order differs: " + label)

    def creation_outputs(self, relative_path):
        require(relative_path in CREATION_OUTPUT_PATHS, "Unexpected creation output path")
        where = "owner_package_name='" + PACKAGE + "' AND relative_path='" + relative_path + "'"
        collection = "video" if relative_path in (MOTION_VIDEO_PATH, VIDEO_EDITOR_OUTPUT_PATH, MEMORY_VIDEO_OUTPUT_PATH) else "images"
        output = self.shell("content", "query", "--user", "0", "--uri",
                            "content://media/external/" + collection + "/media?includePending=1",
                            "--projection", ":".join(FIELDS), "--where", where, "--sort", "_id ASC")[0]
        return parse_creation_outputs(output, relative_path)

    def collage_outputs(self):
        return self.creation_outputs(COLLAGE_OUTPUT_PATH)

    def require_no_collage_export(self, label):
        actual = self.collage_outputs()
        require(actual == self.collage_output_baseline, "Owned collage outputs changed without export")
        self.record(assertion="Owned collage output inventory unchanged, including pending rows",
                    position=label, columns=FIELDS, before=self.collage_output_baseline, after=actual, exit=0)

    def collage_ready(self):
        self.find("resource-id", "creation-collage-screen", scroll=False)
        node = self.find("resource-id", "creation-collage-export")
        deadline = time.monotonic() + 15
        while node.get("enabled") != "true":
            require(time.monotonic() < deadline, "Collage did not finish source/render validation")
            time.sleep(.15)
            node = self.find("resource-id", "creation-collage-export")
        forbidden = {"creation-collage-saved", "creation-collage-cancel", "creation-collage-error",
                     "creation-collage-publication-uncertain"}
        require(not any(n.get("package") == PACKAGE and n.get("resource-id") in forbidden
                        for n in self.ui_nodes), "Collage is not an unpublished editable draft")

    def collage_preview(self, expected, label):
        from PIL import Image
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            node = self.find("resource-id", "creation-collage-preview")
            x1, y1, x2, y2 = bounds(node)
            points = [(x1 + (x2-x1)*part//4, (y1+y2)//2) for part in (1, 3)]
            visible = visible_bounds(node, self.ui_nodes)
            require(all(visible[0] <= x < visible[2] and visible[1] <= y < visible[3]
                        for x, y in points), "Collage preview cells are clipped")
            png = self.call("exec-out", "screencap", "-p", binary=True)[0]
            (self.out / (label + ".png")).write_bytes(png)
            with Image.open(io.BytesIO(png)) as bitmap:
                image = bitmap.convert("RGB")
                actual = [image.getpixel(point) for point in points]
            if all(all(abs(a-b) < 30 for a, b in zip(rgb, wanted))
                   for rgb, wanted in zip(actual, expected)):
                self.record(assertion="Owned Grid2 source preview order", position=label, rgb=actual, exit=0)
                return
            time.sleep(.15)
        raise RuntimeError("Restored collage preview order differs: " + label)

    def setup_collage(self):
        self.collage_output_baseline = self.collage_outputs()
        self.click("create-collage")
        self.collage_ready()
        self.find("resource-id", "creation-collage-template-Grid2", checked=True)
        self.collage_preview(((255, 0, 0), (0, 0, 255)), "before-grid2-red-blue")
        self.find("resource-id", "creation-collage-slot-0", checked=True)
        self.click("creation-collage-later")
        self.collage_ready()
        self.find("resource-id", "creation-collage-slot-1", checked=True)
        default = collage_crop_description(self.find("resource-id", "creation-collage-zoom-value"))
        node = self.find("resource-id", "creation-collage-zoom")
        self.tap(node)  # Midpoint of the observed, visible slider; no guessed absolute coordinates.
        self.collage_ready()
        self.collage_zoom = collage_crop_description(self.find("resource-id", "creation-collage-zoom-value"))
        require(self.collage_zoom != default, "Collage crop gesture did not change its observed value")
        self.collage_preview(((0, 0, 255), (255, 0, 0)), "before-grid2-blue-red")
        self.require_no_collage_export("before-home")
        self.draft_evidence = dict(order=[1, 0], current=1, template="Grid2", zoom=self.collage_zoom,
                                   defaultZoom=default, cropVisualAppearanceVerified=False)

    def verify_collage_restored(self):
        self.phase = "verify-restored-collage-order-crop"
        self.collage_ready()
        self.find("resource-id", "creation-collage-template-Grid2", checked=True)
        self.find("resource-id", "creation-collage-slot-1", checked=True)
        actual = collage_crop_description(self.find("resource-id", "creation-collage-zoom-value"))
        require(actual == self.collage_zoom, "Restored collage crop differs from the edited source")
        self.record(assertion="Restored selected source retains observed nondefault crop", zoom=actual, exit=0)
        self.collage_preview(((0, 0, 255), (255, 0, 0)), "restored-grid2-blue-red")
        self.require_no_collage_export("restored-draft")

    def require_one_collage_publication(self, label):
        gate = self.collage_publication_gate
        require(gate is not None, "Missing owned collage publication gate")
        row = collage_publication_destination_row(gate["destination"])
        expected = tuple(str(row[field]) for field in FIELDS)
        require(all(item[0] != str(row["_id"]) for item in self.collage_output_baseline), "Collage output already existed at baseline")
        actual = self.collage_outputs()
        require(actual == tuple(sorted((*self.collage_output_baseline, expected), key=lambda item: int(item[0]))),
                "Collage output is missing, changed or published more than once")
        self.record(assertion="Exactly one unchanged owned output beyond baseline", position=label, output=expected, exit=0)

    def require_collage_publication_gate_held(self):
        gate = self.collage_publication_gate
        require(gate is not None, "Missing collage publication gate")
        uptime = self.shell("cat", "/proc/uptime")[0].split()
        require(len(uptime) == 2 and re.fullmatch(r"[0-9]+\.[0-9]+", uptime[0]) is not None
                and int(float(uptime[0])*1000) < gate["deadlineElapsedRealtimeMillis"], "Collage publication gate expired")
        for name in ("collage-publication-outcome.json", "collage-publication-release.json"):
            output, code = self.shell("run-as", PACKAGE, "test", "!", "-e", "files/" + self.name + "/" + name, check=False)
            require(code == 0 and not output.strip(), "Collage publication callback was cancelled or released")

    def setup_collage_publication_gate(self):
        require(self.manifest.get("armCollagePublication") is True, "Collage publication gate was not explicitly armed")
        pids = self.shell("pidof", PACKAGE)[0].split()
        require(len(pids) == 1, "Collage publishing process is ambiguous")
        pid = int(pids[0]); self.process_identity(pid)
        self.collage_export_attempted = True
        self.click("creation-collage-export")
        deadline = time.monotonic() + 35
        path = "files/" + self.name + "/collage-publication-gate.json"
        while time.monotonic() < deadline:
            output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
            require(code in (0, 1) and not output.strip() and not error.strip(), "Collage gate existence check failed")
            if code == 1:
                time.sleep(.25); continue
            output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
            require(code == 0 and not error.strip(), "Collage publication gate read failed")
            self.collage_publication_gate = verify_collage_publication_gate(output, self.fixture, pid, self.manifest["uid"])
            (self.out / "collage-publication-gate.json").write_text(output)
            break
        require(self.collage_publication_gate is not None, "Post-publication/pre-callback gate was not reached")
        self.find("resource-id", "creation-collage-cancel", scroll=True)  # Publishing inserts progress above Cancel; reveal its actual scroll container.
        require(not any(node.get("package") == PACKAGE and node.get("resource-id") == "creation-collage-saved" for node in self.ui_nodes),
                "Collage callback completed before its gate")
        self.require_collage_publication_gate_held()
        self.require_one_collage_publication("before-process-death")
        self.draft_evidence.update(exportExecuted=True, publicationGate=self.collage_publication_gate, publicationInterruptionVerified=False)

    def collage_result_ready(self):
        self.find("resource-id", "creation-collage-saved", timeout=30, scroll=True)
        for tag in ("creation-collage-open", "creation-collage-share"):
            require(self.find("resource-id", tag).get("enabled") == "true", "Recovered collage action is not enabled")
        require(self.find("resource-id", "creation-collage-export").get("enabled") == "false", "Recovered publication permits re-export")

    def collage_result_handoff(self, tag, action):
        require((tag, action) in (("creation-collage-open", "android.intent.action.VIEW"),
                                 ("creation-collage-share", "android.intent.action.SEND")), "Unexpected collage result action")
        filename = "collage-publication-handoff-" + action.rsplit(".", 1)[-1] + ".json"
        path = "files/" + self.name + "/" + filename
        output, code, error = self.shell("run-as", PACKAGE, "test", "!", "-e", path, check=False, include_stderr=True)
        require(code == 0 and not output.strip() and not error.strip(), "Collage handoff receipt already exists or absence is unverified")
        self.process_identity(self.new_pid)
        self.click(tag)
        deadline = time.monotonic() + 15
        receipt = None
        while time.monotonic() < deadline:
            if receipt is None:
                output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
                require(code in (0, 1) and not output.strip() and not error.strip(), "Collage handoff receipt existence check failed")
                if code == 0:
                    output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
                    require(code == 0 and not error.strip(), "Collage handoff receipt could not be read")
                    receipt = verify_collage_handoff_receipt(output, self.fixture, self.collage_publication_gate,
                                                           action, self.new_pid, self.manifest["uid"])
                    (self.out / filename).write_text(output)
            activities = self.shell("dumpsys", "activity", "activities")[0]
            try: require_collage_handoff_chooser(activities)
            except RuntimeError:
                time.sleep(.25); continue
            if receipt is not None:
                self.record(assertion="Fresh exact dispatched PNG intent plus resumed chooser; no recipient selected",
                            action=action, receipt=receipt, activities=activities, exit=0)
                break
            time.sleep(.25)
        else: raise RuntimeError("Exact handoff receipt and resumed chooser were not jointly observed")
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.collage_result_ready()
        self.require_one_collage_publication("after-" + action.rsplit(".", 1)[-1].lower())

    def verify_collage_publication_restored(self):
        self.collage_result_ready()
        self.require_one_collage_publication("restored-result")
        self.collage_preview(((0, 0, 255), (255, 0, 0)), "restored-published-blue-red")
        self.collage_result_handoff("creation-collage-open", "android.intent.action.VIEW")
        self.collage_result_handoff("creation-collage-share", "android.intent.action.SEND")
        for row in self.manifest["rows"]:
            require(self.source_hash(row) == row["sha256"], "Owned collage source changed")
        self.no_auto_export_verified = True  # One user export only; no recovery/handoff re-export.

    def verify_collage_publication_before_source_cleanup(self):
        require(self.ui_complete and self.death_confirmed and self.native_completed and self.collage_export_attempted
                and self.accessibility_owner == "host", "Publication verifier requires completed normal-app process/handoff proof")
        self.accessibility_owner = "native-verifier"
        self.phase = "native-collage-publication-predelete-proof-and-output-cas"
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
            "-e", "class", COLLAGE_PUBLICATION_VERIFY, RUNNER, timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "Collage native verification did not complete; preserve sources")
        path = "files/" + self.name + "/collage-publication-verification.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Collage final verification receipt unavailable")
        receipt = verify_collage_publication_receipt(output, self.fixture, self.collage_publication_gate)
        (self.out / "collage-publication-verification.json").write_text(json.dumps(receipt, indent=2)+"\n")
        self.collage_publication_verified = True
        self.record(assertion="Native publication proof and exact output cleanup complete before source CAS", receipt=receipt, exit=0)

    def require_one_gif_publication(self, label):
        gate = self.gif_publication_gate
        require(gate is not None, "Missing owned gif publication gate")
        row = gif_publication_destination_row(gate["destination"])
        expected = tuple(str(row[field]) for field in FIELDS)
        require(all(item[0] != str(row["_id"]) for item in self.gif_output_baseline), "GIF output already existed at baseline")
        actual = self.creation_outputs(GIF_OUTPUT_PATH)
        require(actual == tuple(sorted((*self.gif_output_baseline, expected), key=lambda item: int(item[0]))),
                "GIF output is missing, changed or published more than once")
        self.record(assertion="Exactly one unchanged owned output beyond baseline", position=label, output=expected, exit=0)

    def require_gif_publication_gate_held(self):
        gate = self.gif_publication_gate
        require(gate is not None, "Missing gif publication gate")
        uptime = self.shell("cat", "/proc/uptime")[0].split()
        require(len(uptime) == 2 and re.fullmatch(r"[0-9]+\.[0-9]+", uptime[0]) is not None
                and int(float(uptime[0])*1000) < gate["deadlineElapsedRealtimeMillis"], "GIF publication gate expired")
        for name in ("gif-publication-outcome.json", "gif-publication-release.json"):
            output, code = self.shell("run-as", PACKAGE, "test", "!", "-e", "files/" + self.name + "/" + name, check=False)
            require(code == 0 and not output.strip(), "GIF publication callback was cancelled or released")

    def setup_gif_publication_gate(self):
        require(self.manifest.get("armGifPublication") is True, "GIF publication gate was not explicitly armed")
        pids = self.shell("pidof", PACKAGE)[0].split()
        require(len(pids) == 1, "GIF publishing process is ambiguous")
        pid = int(pids[0]); self.process_identity(pid)
        self.gif_export_attempted = True
        self.click("creation-gif-export")
        deadline = time.monotonic() + 35
        path = "files/" + self.name + "/gif-publication-gate.json"
        while time.monotonic() < deadline:
            output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
            require(code in (0, 1) and not output.strip() and not error.strip(), "GIF gate existence check failed")
            if code == 1:
                time.sleep(.25); continue
            output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
            require(code == 0 and not error.strip(), "GIF publication gate read failed")
            self.gif_publication_gate = verify_gif_publication_gate(output, self.fixture, pid, self.manifest["uid"])
            (self.out / "gif-publication-gate.json").write_text(output)
            break
        require(self.gif_publication_gate is not None, "Post-publication/pre-callback gate was not reached")
        self.find("resource-id", "creation-gif-cancel", scroll=True)  # Reveal progress footer inside its actual scroll container; never tap Cancel.
        require(not any(node.get("package") == PACKAGE and node.get("resource-id") == "creation-gif-saved" for node in self.ui_nodes),
                "GIF callback completed before its gate")
        self.require_gif_publication_gate_held()
        self.require_one_gif_publication("before-process-death")
        self.draft_evidence.update(exportExecuted=True, publicationGate=self.gif_publication_gate, publicationInterruptionVerified=False)

    def gif_result_ready(self):
        self.find("resource-id", "creation-gif-saved", timeout=30, scroll=True)
        for tag in ("creation-gif-open", "creation-gif-share"):
            require(self.find("resource-id", tag).get("enabled") == "true", "Recovered gif action is not enabled")
        require(self.find("resource-id", "creation-gif-export").get("enabled") == "false", "Recovered publication permits re-export")

    def gif_result_handoff(self, tag, action):
        require((tag, action) in (("creation-gif-open", "android.intent.action.VIEW"),
                                 ("creation-gif-share", "android.intent.action.SEND")), "Unexpected gif result action")
        filename = "gif-publication-handoff-" + action.rsplit(".", 1)[-1] + ".json"
        path = "files/" + self.name + "/" + filename
        output, code, error = self.shell("run-as", PACKAGE, "test", "!", "-e", path, check=False, include_stderr=True)
        require(code == 0 and not output.strip() and not error.strip(), "GIF handoff receipt already exists or absence is unverified")
        self.process_identity(self.new_pid)
        self.click(tag)
        deadline = time.monotonic() + 15
        receipt = None
        while time.monotonic() < deadline:
            if receipt is None:
                output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
                require(code in (0, 1) and not output.strip() and not error.strip(), "GIF handoff receipt existence check failed")
                if code == 0:
                    output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
                    require(code == 0 and not error.strip(), "GIF handoff receipt could not be read")
                    receipt = verify_gif_handoff_receipt(output, self.fixture, self.gif_publication_gate,
                                                           action, self.new_pid, self.manifest["uid"])
                    (self.out / filename).write_text(output)
            activities = self.shell("dumpsys", "activity", "activities")[0]
            try: require_gif_handoff_chooser(activities)
            except RuntimeError:
                time.sleep(.25); continue
            if receipt is not None:
                self.record(assertion="Fresh exact dispatched GIF intent plus resumed chooser; no recipient selected",
                            action=action, receipt=receipt, activities=activities, exit=0)
                break
            time.sleep(.25)
        else: raise RuntimeError("Exact handoff receipt and resumed chooser were not jointly observed")
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.gif_result_ready()
        self.require_one_gif_publication("after-" + action.rsplit(".", 1)[-1].lower())

    def verify_gif_publication_restored(self):
        self.gif_result_ready()
        self.require_one_gif_publication("restored-result")
        # Frame order/duration are proved against actual GIF bytes natively before deletion.
        require(self.find("resource-id", "creation-gif-seconds-4", checked=True).get("checked") == "true",
                "Published GIF duration control differs")
        self.gif_result_handoff("creation-gif-open", "android.intent.action.VIEW")
        self.gif_result_handoff("creation-gif-share", "android.intent.action.SEND")
        for row in self.manifest["rows"]:
            require(self.source_hash(row) == row["sha256"], "Owned gif source changed")
        self.no_auto_export_verified = True  # One user export only; no recovery/handoff re-export.

    def verify_gif_publication_before_source_cleanup(self):
        require(self.ui_complete and self.death_confirmed and self.native_completed and self.gif_export_attempted
                and self.accessibility_owner == "host", "Publication verifier requires completed normal-app process/handoff proof")
        self.accessibility_owner = "native-verifier"
        self.phase = "native-gif-publication-predelete-proof-and-output-cas"
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
            "-e", "class", GIF_PUBLICATION_VERIFY, RUNNER, timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "GIF native verification did not complete; preserve sources")
        path = "files/" + self.name + "/gif-publication-verification.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "GIF final verification receipt unavailable")
        receipt = verify_gif_publication_receipt(output, self.fixture, self.gif_publication_gate)
        (self.out / "gif-publication-verification.json").write_text(json.dumps(receipt, indent=2)+"\n")
        self.gif_publication_verified = True
        self.record(assertion="Native publication proof and exact output cleanup complete before source CAS", receipt=receipt, exit=0)

    def require_one_motion_publication(self, label):
        gate = self.motion_publication_gate
        require(gate is not None, "Missing owned motion publication gate")
        row = motion_publication_destination_row(gate["destination"], self.motion_publication_kind)
        expected = tuple(str(row[field]) for field in FIELDS)
        target_path = MOTION_IMAGE_PATH if self.motion_publication_kind == "Frame" else MOTION_VIDEO_PATH
        for path, baseline in self.motion_output_baseline.items():
            require(all(item[0] != str(row["_id"]) for item in baseline) if path == target_path else True,
                    "Motion destination existed before export")
            wanted = tuple(sorted((*baseline, expected), key=lambda item: int(item[0]))) if path == target_path else baseline
            require(self.creation_outputs(path) == wanted, "Motion output changed, missing or extra output appeared")
        self.record(assertion="Exactly one unchanged owned output beyond baseline", position=label, output=expected, exit=0)

    def require_motion_publication_gate_held(self):
        gate = self.motion_publication_gate
        require(gate is not None, "Missing motion publication gate")
        uptime = self.shell("cat", "/proc/uptime")[0].split()
        require(len(uptime) == 2 and re.fullmatch(r"[0-9]+\.[0-9]+", uptime[0]) is not None
                and int(float(uptime[0])*1000) < gate["deadlineElapsedRealtimeMillis"], "Motion publication gate expired")
        for name in ("motion-publication-outcome.json", "motion-publication-release.json"):
            output, code = self.shell("run-as", PACKAGE, "test", "!", "-e", "files/" + self.name + "/" + name, check=False)
            require(code == 0 and not output.strip(), "Motion publication callback was cancelled or released")

    def setup_motion_publication_gate(self):
        require(self.manifest.get("armMotionPublication") is True and self.manifest.get("motionPublicationKind") == self.motion_publication_kind, "Motion publication gate was not explicitly armed")
        pids = self.shell("pidof", PACKAGE)[0].split()
        require(len(pids) == 1, "Motion publishing process is ambiguous")
        pid = int(pids[0]); self.process_identity(pid)
        self.motion_export_attempted = True
        self.click("motion-export-jpeg" if self.motion_publication_kind == "Frame" else "motion-export-mp4")
        deadline = time.monotonic() + 35
        path = "files/" + self.name + "/motion-publication-gate.json"
        while time.monotonic() < deadline:
            output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
            require(code in (0, 1) and not output.strip() and not error.strip(), "Motion gate existence check failed")
            if code == 1:
                time.sleep(.25); continue
            output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
            require(code == 0 and not error.strip(), "Motion publication gate read failed")
            self.motion_publication_gate = verify_motion_publication_gate(output, self.fixture, pid, self.manifest["uid"], self.motion_publication_kind)
            (self.out / "motion-publication-gate.json").write_text(output)
            break
        require(self.motion_publication_gate is not None, "Post-publication/pre-callback gate was not reached")
        self.find("resource-id", "motion-screen", scroll=False)  # Gate receipt proves commit; no guessed Cancel selector.
        require(not any(node.get("package") == PACKAGE and node.get("resource-id") == "motion-exported" for node in self.ui_nodes),
                "Motion callback completed before its gate")
        self.require_motion_publication_gate_held()
        self.require_one_motion_publication("before-process-death")
        self.draft_evidence.update(exportExecuted=True, publicationGate=self.motion_publication_gate, publicationInterruptionVerified=False)

    def motion_result_ready(self):
        self.find("resource-id", "motion-exported", timeout=30, scroll=True)
        for tag in ("motion-open", "motion-share"):
            require(self.find("resource-id", tag).get("enabled") == "true", "Recovered motion action is not enabled")
        require(not any(n.get("package") == PACKAGE and n.get("resource-id") in ("motion-export-jpeg", "motion-export-mp4")
                        and n.get("enabled") == "true" for n in self.ui_nodes), "Recovered publication permits re-export")

    def motion_result_handoff(self, tag, action):
        require((tag, action) in (("motion-open", "android.intent.action.VIEW"),
                                 ("motion-share", "android.intent.action.SEND")), "Unexpected motion result action")
        filename = "motion-publication-handoff-" + action.rsplit(".", 1)[-1] + ".json"
        path = "files/" + self.name + "/" + filename
        output, code, error = self.shell("run-as", PACKAGE, "test", "!", "-e", path, check=False, include_stderr=True)
        require(code == 0 and not output.strip() and not error.strip(), "Motion handoff receipt already exists or absence is unverified")
        self.process_identity(self.new_pid)
        self.click(tag)
        deadline = time.monotonic() + 15
        receipt = None
        while time.monotonic() < deadline:
            if receipt is None:
                output, code, error = self.shell("run-as", PACKAGE, "test", "-f", path, check=False, include_stderr=True)
                require(code in (0, 1) and not output.strip() and not error.strip(), "Motion handoff receipt existence check failed")
                if code == 0:
                    output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
                    require(code == 0 and not error.strip(), "Motion handoff receipt could not be read")
                    receipt = verify_motion_handoff_receipt(output, self.fixture, self.motion_publication_gate,
                                                           action, self.new_pid, self.manifest["uid"], self.motion_publication_kind)
                    (self.out / filename).write_text(output)
            activities = self.shell("dumpsys", "activity", "activities")[0]
            try: require_motion_handoff_chooser(activities)
            except RuntimeError:
                time.sleep(.25); continue
            if receipt is not None:
                self.record(assertion="Fresh exact dispatched Motion intent plus resumed chooser; no recipient selected",
                            action=action, receipt=receipt, activities=activities, exit=0)
                break
            time.sleep(.25)
        else: raise RuntimeError("Exact handoff receipt and resumed chooser were not jointly observed")
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.motion_result_ready()
        self.require_one_motion_publication("after-" + action.rsplit(".", 1)[-1].lower())

    def verify_motion_publication_restored(self):
        self.motion_result_ready()
        self.require_one_motion_publication("restored-result")
        # Actual JPEG pixels/MP4 slice and duration are verified natively before deletion.
        self.motion_result_handoff("motion-open", "android.intent.action.VIEW")
        self.motion_result_handoff("motion-share", "android.intent.action.SEND")
        for row in self.manifest["rows"]:
            require(self.source_hash(row) == row["sha256"], "Owned motion source changed")
        self.no_auto_export_verified = True  # One user export only; no recovery/handoff re-export.

    def verify_motion_publication_before_source_cleanup(self):
        require(self.ui_complete and self.death_confirmed and self.native_completed and self.motion_export_attempted
                and self.accessibility_owner == "host", "Publication verifier requires completed normal-app process/handoff proof")
        self.accessibility_owner = "native-verifier"
        self.phase = "native-motion-publication-predelete-proof-and-output-cas"
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
            "-e", "motionPublicationKind", self.motion_publication_kind, "-e", "class", MOTION_PUBLICATION_VERIFY, RUNNER, timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "Motion native verification did not complete; preserve sources")
        path = "files/" + self.name + "/motion-publication-verification.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path, check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Motion final verification receipt unavailable")
        receipt = verify_motion_publication_receipt(output, self.fixture, self.motion_publication_gate)
        (self.out / "motion-publication-verification.json").write_text(json.dumps(receipt, indent=2)+"\n")
        self.motion_publication_verified = True
        self.record(assertion="Native publication proof and exact output cleanup complete before source CAS", receipt=receipt, exit=0)

    def require_no_gif_export(self, label):
        actual = self.creation_outputs(GIF_OUTPUT_PATH)
        require(actual == self.gif_output_baseline, "Owned GIF outputs changed without export")
        self.record(assertion="Owned GIF output inventory unchanged, including pending rows",
                    position=label, columns=FIELDS, before=self.gif_output_baseline, after=actual, exit=0)

    def setup_gif(self):
        self.gif_output_baseline = self.creation_outputs(GIF_OUTPUT_PATH)
        self.click("create-gif")
        self.find("resource-id", "creation-gif-screen", scroll=False)
        self.preview((255, 0, 0), "before-gif-position1-red", preview_tag="creation-gif-preview")
        self.gif_position1 = self.find("resource-id", "creation-gif-position").get("text", "")
        require(bool(self.gif_position1.strip()), "Initial GIF position text is missing")
        self.gif_play_label = gif_play_text(self.find("resource-id", "creation-gif-play"))
        self.click("creation-gif-later")
        self.gif_position2 = self.find("resource-id", "creation-gif-position").get("text", "")
        require(bool(self.gif_position2.strip()) and self.gif_position2 != self.gif_position1,
                "GIF reorder did not advance to its second position")
        self.click("creation-gif-seconds-4")
        self.find("resource-id", "creation-gif-seconds-4", checked=True)
        self.preview((255, 0, 0), "before-gif-position2-red", preview_tag="creation-gif-preview")
        self.click("creation-gif-previous")
        require(self.find("resource-id", "creation-gif-position").get("text") == self.gif_position1,
                "GIF previous did not restore the first position")
        self.preview((0, 0, 255), "before-gif-position1-blue", preview_tag="creation-gif-preview")
        self.click("creation-gif-next")
        require(self.find("resource-id", "creation-gif-position").get("text") == self.gif_position2,
                "GIF next did not restore the second position")
        self.preview((255, 0, 0), "before-final-gif-position2-red", preview_tag="creation-gif-preview")
        require(gif_play_text(self.find("resource-id", "creation-gif-play")) == self.gif_play_label,
                "GIF unexpectedly started playback before Home")
        self.require_no_gif_export("before-home")
        self.draft_evidence = dict(order=[1, 0], seconds=4, current=1, playbackStarted=False,
                                   position1=self.gif_position1, position2=self.gif_position2,
                                   pausedButtonText=self.gif_play_label)

    def verify_gif_restored(self):
        self.phase = "verify-restored-gif-order-duration-index-paused"
        self.find("resource-id", "creation-gif-screen", scroll=False)
        self.find("resource-id", "creation-gif-seconds-4", checked=True)
        require(self.find("resource-id", "creation-gif-position").get("text") == self.gif_position2,
                "Restored GIF position differs")
        require(gif_play_text(self.find("resource-id", "creation-gif-play")) == self.gif_play_label,
                "Restored GIF resumed playback automatically")
        paused_at = time.monotonic()
        self.preview((255, 0, 0), "restored-gif-position2-red", preview_tag="creation-gif-preview")
        # Observe a complete frame interval without touching Play/Next; avoid a false paused
        # claim inferred only from a single instantaneous frame or an even number of loops.
        remaining = 4.25 - (time.monotonic() - paused_at)
        if remaining > 0:
            time.sleep(remaining)
        require(gif_play_text(self.find("resource-id", "creation-gif-play")) == self.gif_play_label
                and self.find("resource-id", "creation-gif-position").get("text") == self.gif_position2,
                "Restored GIF did not remain paused at its saved position")
        self.no_auto_play_verified = True
        self.record(assertion="Restored GIF remained paused without playback input", secondsPerFrame=4,
                    observedAtLeastSeconds=4.25, position=self.gif_position2, playbackButtonText=self.gif_play_label, exit=0)
        self.click("creation-gif-previous")
        require(self.find("resource-id", "creation-gif-position").get("text") == self.gif_position1,
                "Restored GIF previous did not reach first position")
        self.preview((0, 0, 255), "restored-gif-position1-blue", preview_tag="creation-gif-preview")
        self.require_no_gif_export("restored-draft")

    def require_no_motion_export(self, label):
        actual = {path: self.creation_outputs(path) for path in (MOTION_IMAGE_PATH, MOTION_VIDEO_PATH)}
        require(actual == self.motion_output_baseline, "Owned Motion outputs changed without export")
        self.record(assertion="Owned Motion JPEG/MP4 output inventories unchanged, including pending rows",
                    position=label, columns=FIELDS, before=self.motion_output_baseline, after=actual, exit=0)

    def verify_motion_state(self, label):
        self.find("resource-id", "motion-screen", scroll=False)
        frame = self.find("resource-id", "motion-frame-4")
        require_motion_frame_selected(frame)
        require(self.find("resource-id", "motion-time").get("text") == self.manifest["expectedFrame4TimeLabel"],
                "Motion selected frame timestamp differs")
        require(playback_button_text(self.find("resource-id", "motion-play"), "motion-play") == self.manifest["playLabel"],
                "Motion playback is not paused")
        self.record(assertion="Motion frame 4 selected at expected time with playback paused", position=label,
                    time=self.manifest["expectedFrame4TimeLabel"], selected=frame.get("selected"),
                    checkable=frame.get("checkable"), checked=frame.get("checked"), exit=0)

    def setup_motion(self, row):
        for field in ("motionLabel", "playLabel", "expectedFrame4TimeLabel", "viewerMoreLabel", "viewerDetailsLabel", "viewerPhotoLabel"):
            require(isinstance(self.manifest.get(field), str) and bool(self.manifest[field].strip()),
                    "Missing Motion fixture UI label: " + field)
        self.motion_output_baseline = {path: self.creation_outputs(path) for path in (MOTION_IMAGE_PATH, MOTION_VIDEO_PATH)}
        self.tap(self.find("resource-id", "media_external_primary_" + str(row["_id"]), timeout=45))
        self.find("text", self.manifest["motionLabel"])
        self.preview((255, 0, 255), "before-motion-original-magenta", preview_description=self.manifest["viewerPhotoLabel"])
        self.tap(self.find("text", self.manifest["motionLabel"]))
        self.find("resource-id", "motion-frame-5")
        self.click("motion-frame-4")
        self.verify_motion_state("before-home")
        self.require_no_motion_export("before-home")
        self.draft_evidence = dict(frameIndex=4, time=self.manifest["expectedFrame4TimeLabel"],
                                   playbackStarted=False, setKeyFrameExecuted=False, keyFrameRowsVerified=False)

    def verify_motion_restored(self):
        self.phase = "verify-restored-motion-frame-time-paused"
        self.verify_motion_state("restored-draft")
        self.require_no_motion_export("restored-draft")
        self.no_auto_play_verified = True

    def verify_motion_back(self, row):
        self.phase = "back-motion-original-viewer-details-and-source-hash"
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.find("text", self.manifest["motionLabel"])
        self.preview((255, 0, 255), "restored-motion-original-magenta", preview_description=self.manifest["viewerPhotoLabel"])
        self.tap(self.find("content-desc", self.manifest["viewerMoreLabel"]))
        self.tap(self.find("text", self.manifest["viewerDetailsLabel"]))
        self.find("text", row["_display_name"])
        self.record(assertion="Motion Back returned to original viewer source", displayName=row["_display_name"], exit=0)
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.find("text", self.manifest["motionLabel"])
        require(self.source_hash(row) == row["sha256"], "Owned Motion original changed")
        self.require_no_motion_export("back-original-viewer")
        self.no_auto_export_verified = True

    def verify_motion_no_saved_keyframe(self, row):
        require(self.ui_complete and self.death_confirmed and self.native_completed
                and self.accessibility_owner == "host", "Motion verifier requires completed normal-app restoration")
        # Every tree command is terminal before it returns; the real-display observer's
        # success receipt is emitted only after disconnect. No UI or normal PID assertions follow.
        self.accessibility_owner = "native-verifier"
        self.phase = "native-motion-keyframe-check-before-source-cleanup"
        self.record(observer="handoff-to-post-ui-native-verifier", normalAppUiComplete=True,
                    requiresSourcePresent=True, source=row["uri"])
        output, code, error = self.shell("am", "instrument", "-w", "-r", "-e", "fixtureUuid", self.fixture,
                                         "-e", "class", MOTION_VERIFY_TEST, RUNNER,
                                         timeout=90, check=False, include_stderr=True)
        require(code == 0 and not error.strip() and "OK (1 test)" in output
                and "FAILURES!!!" not in output and "INSTRUMENTATION_FAILED" not in output,
                "Motion pre-cleanup native verifier did not complete successfully; source retained")
        path = "files/" + self.name + "/keyframe-verification.json"
        output, code, error = self.call("exec-out", "run-as", PACKAGE, "cat", path,
                                         check=False, include_stderr=True)
        require(code == 0 and not error.strip(), "Motion pre-cleanup receipt unavailable; source retained")
        receipt = verify_motion_keyframe_receipt(output, self.fixture, row["_id"])
        (self.out / "keyframe-verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
        self.keyframe_rows_verified = True
        self.record(assertion="No saved Motion keyframe with source present before CAS cleanup", receipt=receipt, exit=0)

    def require_no_video_editor_export(self, label):
        actual = self.creation_outputs(VIDEO_EDITOR_OUTPUT_PATH)
        require(actual == self.video_editor_output_baseline, "Owned edited-video outputs changed without export")
        self.record(assertion="Owned edited-video output inventory unchanged, including pending rows",
                    position=label, columns=FIELDS, before=self.video_editor_output_baseline, after=actual, exit=0)

    def adjust_video_editor_trim(self):
        # Drag each nearest thumb inside the freshly observed RangeSlider. Capture actual
        # resulting values afterwards; Material padding means percentages are not timestamps.
        for start_fraction, end_fraction in ((.05, .2), (.95, .8)):
            node = self.find("resource-id", "video-editor-trim", scroll=False)
            require(node in self.ui_nodes and node.get("package") == PACKAGE and node.get("enabled") == "true",
                    "Video editor trim control is not ready")
            left, top, right, bottom = bounds(node)
            start = (left + int((right-left)*start_fraction), (top+bottom)//2)
            end = (left + int((right-left)*end_fraction), (top+bottom)//2)
            x1, y1, x2, y2 = visible_bounds(node, self.ui_nodes)
            require(start != end and all(x1 <= x < x2 and y1 <= y < y2 for x, y in (start, end)),
                    "Video editor trim gesture is clipped")
            self.record(observer="measured-video-editor-trim", bounds=[left, top, right, bottom], start=start, end=end)
            self.shell("input", "swipe", *start, *end, "400")

    def video_editor_values(self):
        trim_text, (start, end) = editor_timecodes(self.find("resource-id", "video-editor-trim-value", scroll=False),
                                                  "video-editor-trim-value")
        position_text, (position, position_end) = editor_timecodes(
            self.find("resource-id", "video-editor-position-value", scroll=False), "video-editor-position-value")
        require(0 < start < position < end < self.manifest["durationMillis"] and position_end == end,
                "Video editor trim/position is not a nontrivial interior draft")
        return dict(trimText=trim_text, positionText=position_text, startMillis=start, endMillis=end, positionMillis=position)

    def settle_video_fixture_preview(self):
        duration = self.manifest.get("durationMillis")
        require(type(duration) is int and 1000 <= duration <= 60000, "Invalid fixture playback settle duration")
        seconds = (duration + 1000) / 1000
        self.record(observer="fixture-playback-settle", seconds=seconds,
                    result="Allow initial Viewer playback to finish before accessibility lookup; not UI verification")
        time.sleep(seconds)

    def setup_video_editor(self, row):
        labels = ("viewerEditLabel", "viewerMoreLabel", "viewerDetailsLabel", "editorAudioLabel", "editorOriginalAudioLabel",
                  "editorPlayLabel", "editorPauseLabel", "editorDiscardTitleLabel", "editorDiscardConfirmLabel")
        require(all(isinstance(self.manifest.get(label), str) and self.manifest[label].strip() for label in labels),
                "Missing video editor fixture UI labels")
        self.video_editor_output_baseline = self.creation_outputs(VIDEO_EDITOR_OUTPUT_PATH)
        self.tap(self.find("resource-id", "media_external_primary_" + str(row["_id"]), timeout=45))
        self.settle_video_fixture_preview()
        self.tap(self.find("text", self.manifest["viewerEditLabel"], scroll=False))
        self.find("resource-id", "video-editor-preview", scroll=False)
        self.find("content-desc", self.manifest["editorPlayLabel"], scroll=False)
        self.adjust_video_editor_trim()
        self.tap(self.find("resource-id", "video-editor-position", scroll=False))
        self.editor_draft = self.video_editor_values()
        self.tap(self.find("text", self.manifest["editorAudioLabel"]))
        self.find("text", self.manifest["editorOriginalAudioLabel"], scroll=False)
        self.find("content-desc", self.manifest["editorPlayLabel"], scroll=False)
        require(self.video_editor_values() == self.editor_draft, "Video editor draft changed while opening Audio tab")
        self.require_no_video_editor_export("before-home")
        self.draft_evidence = dict(self.editor_draft, tab="Audio", playbackStarted=False, exportExecuted=False)

    def verify_video_editor_restored(self):
        self.phase = "verify-restored-video-editor-trim-position-audio-paused"
        self.find("resource-id", "video-editor-preview", scroll=False)
        require(self.video_editor_values() == self.editor_draft, "Restored video editor trim/position differs")
        # This body exists only for Audio tab. Do not click the tab after restoration.
        self.find("text", self.manifest["editorOriginalAudioLabel"], scroll=False)
        self.find("content-desc", self.manifest["editorPlayLabel"], scroll=False)
        require(self.video_editor_values() == self.editor_draft, "Restored video editor did not remain paused")
        self.no_auto_play_verified = True
        self.require_no_video_editor_export("restored-draft")
        self.record(assertion="Video editor restored exact trim/position, Audio tab and paused playback", draft=self.editor_draft, exit=0)

    def verify_video_editor_back(self, row):
        self.phase = "discard-video-editor-draft-back-original-details-and-source-hash"
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.find("text", self.manifest["editorDiscardTitleLabel"], scroll=False)
        self.tap(self.find("text", self.manifest["editorDiscardConfirmLabel"], scroll=False))
        self.tap(self.find("content-desc", self.manifest["viewerMoreLabel"], scroll=False))
        self.tap(self.find("text", self.manifest["viewerDetailsLabel"], scroll=False))
        self.find("text", row["_display_name"])
        self.shell("input", "keyevent", "KEYCODE_BACK")
        require(self.source_hash(row) == row["sha256"], "Owned video original changed")
        self.require_no_video_editor_export("back-original-viewer")
        self.no_auto_export_verified = True
        self.record(assertion="Discarded editor draft returned to original video", displayName=row["_display_name"], exit=0)

    def require_no_memory_video_export(self, label):
        actual = self.creation_outputs(MEMORY_VIDEO_OUTPUT_PATH)
        require(actual == self.memory_video_output_baseline, "Owned memory-video outputs changed without export")
        self.record(assertion="Owned memory-video output inventory unchanged, including pending rows",
                    position=label, columns=FIELDS, before=self.memory_video_output_baseline, after=actual, exit=0)

    def verify_video_restored(self):
        self.phase = "verify-restored-editor-order-duration"
        self.find("resource-id", "memory-video-screen", scroll=False)
        self.find("text", self.manifest["position2"])
        self.find("resource-id", "memory-video-seconds-4", checked=True)
        self.preview((255, 0, 0), "restored-position2-red")
        self.click("memory-video-previous")
        self.find("text", self.manifest["position1"])
        self.preview((0, 0, 255), "restored-position1-blue")
        self.require_no_memory_video_export("restored-draft")

    def validate_source(self, row):
        index = row["ordinal"]
        motion = getattr(self, "scenario", None) in ("motion-draft", *MOTION_PUBLICATION_SCENARIOS)
        video = getattr(self, "scenario", None) == "video-editor-draft"
        require(index == 0 if motion or video else index in (0, 1), "Invalid source ordinal")
        collection = "video" if video else "images"
        require(re.fullmatch(r"content://media/external/" + collection + r"/media/[1-9][0-9]*", row["uri"]), "Unexpected source URI")
        require(int(row["uri"].rsplit("/", 1)[1]) == row["_id"], "Source URI/ID mismatch")
        suffix = ".mp4" if video else ".jpg" if motion else ".png"
        folder = "Movies/" if video else "Pictures/"
        require(row["_display_name"] == self.name + f"-{index}" + suffix and row["relative_path"] == folder + self.name + "/"
                and row["owner_package_name"] == PACKAGE, "Source ownership changed")
        require(row["_data"] == "/storage/emulated/0/" + row["relative_path"] + row["_display_name"], "Unexpected source path")
        for field in ("generation_added", "generation_modified", "is_pending"):
            require(isinstance(row[field], int) and row[field] >= 0, "Invalid source snapshot")

    def current_row(self, row):
        output = self.shell("content", "query", "--user", "0", "--uri", row["uri"],
                            "--projection", ":".join(FIELDS))[0]
        return parse_row(output)

    def source_hash(self, row):
        from PIL import Image
        self.validate_source(row)
        # Shell UID reads via the provider; run-as filesystem reads may be denied by FUSE.
        if self.args.serial == "emulator-5554":
            # API30 shell Content.Read has a provider attribution bug. The shell can read
            # this exact journaled public path; run-as FUSE cannot. Validate PNG bytes below.
            data, code = self.call("exec-out", "cat", row["_data"], binary=True)
        else:
            data, code = self.call("exec-out", "content", "read", "--user", "0", "--uri", row["uri"], binary=True)
        if getattr(self, "scenario", None) == "video-editor-draft":
            require(code == 0, "Video provider read failed")
            return verify_video_source_bytes(data, self.manifest)
        if getattr(self, "scenario", None) in ("motion-draft", *MOTION_PUBLICATION_SCENARIOS):
            require(code == 0, "Motion provider read failed")
            return verify_motion_source_bytes(data, self.manifest)
        require(code == 0 and isinstance(data, bytes) and 32 <= len(data) <= 65_536,
                "Provider read failed or returned unexpected fixture size")
        require(data.startswith(b"\x89PNG\r\n\x1a\n") and data.endswith(b"\x00\x00\x00\x00IEND\xaeB`\x82"),
                "Provider read did not return exact PNG bytes; error text is not a source hash")
        with Image.open(io.BytesIO(data)) as image:
            require(image.format == "PNG" and image.size == (80, 80), "Unexpected owned fixture image")
            image.verify()
        return hashlib.sha256(data).hexdigest()

    def cleanup(self):
        require(getattr(self, "scenario", None) != "motion-draft" or getattr(self, "keyframe_rows_verified", False),
                "Motion cleanup requires pre-deletion keyframe verification; source retained")
        require(getattr(self, "scenario", None) not in ("manual-memory-draft", "manual-memory-commit") or getattr(self, "manual_memory_verified", False)
                or getattr(self, "manual_memory_absence_verified", False),
                "Manual memory cleanup requires pre-deletion verification and memory cleanup; sources retained")
        require(getattr(self, "scenario", None) not in MOTION_PUBLICATION_SCENARIOS or getattr(self, "motion_publication_verified", False),
                "Motion publication requires native predelete/output-cleanup proof")
        require(getattr(self, "scenario", None) != "gif-publication" or getattr(self, "gif_publication_verified", False),
                "GIF publication sources require native predelete/output-cleanup proof")
        require(getattr(self, "scenario", None) != "collage-publication" or getattr(self, "collage_publication_verified", False),
                "Collage publication sources require native predelete/output-cleanup proof")
        self.phase = "cleanup-exact-owned-rows"
        self.guard_device()
        retained = []
        deleted = []
        value = self.read_manifest()
        if value is None:
            self.record(result="Cleanup ownership manifest unavailable; no row deletion attempted")
            return
        for row in value["rows"]:
            try:
                self.validate_source(row)
                current = self.current_row(row)
                if current is None:
                    deleted.append(row["uri"])
                    continue
                require(all(str(row[field]) == current[field] for field in FIELDS), "Source snapshot changed; retained")
                if row.get("sha256"):
                    require(self.source_hash(row) == row["sha256"], "Source bytes changed; retained")
                where = " AND ".join(field + "=" + (str(row[field]) if isinstance(row[field], int)
                                     else "'" + row[field] + "'") for field in FIELDS)
                self.shell("content", "delete", "--user", "0", "--uri", row["uri"], "--where", where)
                require(self.current_row(row) is None, "Deletion unconfirmed; owned row retained")
                deleted.append(row["uri"])
            except Exception as error:
                retained.append(dict(uri=row.get("uri"), reason=str(error)))
        self.cleanup_complete = not retained
        cleanup = dict(deletedOrAlreadyAbsent=deleted, retained=retained, complete=self.cleanup_complete,
                       note="Private UUID manifest/screenshots retained as evidence; no Room/global directory deletion")
        (self.out / "cleanup.json").write_text(json.dumps(cleanup, indent=2) + "\n")
        self.record(cleanup=cleanup)
        if self.dumps:
            self.shell("rm", "-f", *self.dumps, check=False)

    def execute(self):
        failure = None
        cleanup_error = None
        try:
            from PIL import Image  # Fail before native setup if this small host dependency is missing.
            self.guard_device()
            self.guard_observer()
            self.apk(PACKAGE, self.args.apk_sha256)
            self.apk(TEST_PACKAGE, self.args.test_apk_sha256)
            require(RUNNER + " (target=" + PACKAGE + ")" in self.shell("pm", "list", "instrumentation")[0], "Unexpected instrumentation target")
            self.phase = "native-seed-only"
            self.start_native()
            self.native_finished()
            native_output = "".join(self.stdout)
            require(self.process.returncode == 0 and "OK (1 test)" in native_output and "FAILURES!!!" not in native_output,
                    "Native seed did not complete successfully")
            value = self.read_manifest()
            require(value is not None and value["state"] == "SEEDED" and len(value["rows"]) == (1 if self.scenario in ("motion-draft", "video-editor-draft", *MOTION_PUBLICATION_SCENARIOS) else 2),
                    "Native seed manifest incomplete")
            self.native_completed = True
            self.accessibility_owner = "host"
            self.phase = "normal-app-selection-edit-home"
            self.setup_normal_app()
            self.phase = "kill-verified-uninstrumented-stopped-process"
            if self.scenario == "manual-memory-commit":
                require(self.manual_commit_gate["pid"] == self.old_pid, "Stopped task is not the post-commit process")
                self.require_manual_commit_gate_held()
            if self.scenario == "collage-publication":
                require(self.collage_publication_gate["pid"] == self.old_pid, "Stopped process differs from the publication gate")
                self.require_collage_publication_gate_held()
            if self.scenario == "gif-publication":
                require(self.gif_publication_gate["pid"] == self.old_pid, "Stopped process differs from the GIF publication gate")
                self.require_gif_publication_gate_held()
            if self.scenario in MOTION_PUBLICATION_SCENARIOS:
                require(self.motion_publication_gate["pid"] == self.old_pid, "Stopped process differs from Motion publication gate")
                self.require_motion_publication_gate_held()
            self.kill_owned(self.old_pid)
            self.death_confirmed = True
            self.phase = "restore-existing-task"
            require(self.task_id in self.task_ids(), "Saved task was removed; restoration not proved")
            self.launch_normal()
            pids = self.shell("pidof", PACKAGE)[0].split()
            require(len(pids) == 1, "Restored process is ambiguous")
            self.new_pid = int(pids[0])
            require(self.new_pid != self.old_pid, "Recreation is not process death")
            self.process_identity(self.new_pid)
            require(self.task_ids(resumed=True) == {self.task_id}, "Restoration did not resume the original task")
            if self.scenario in MOTION_PUBLICATION_SCENARIOS:
                self.verify_motion_publication_restored()
            elif self.scenario == "gif-publication":
                self.verify_gif_publication_restored()
            elif self.scenario == "collage-publication":
                self.verify_collage_publication_restored()
            elif self.scenario == "collage-draft":
                self.verify_collage_restored()
            elif self.scenario == "gif-draft":
                self.verify_gif_restored()
            elif self.scenario == "motion-draft":
                self.verify_motion_restored()
            elif self.scenario == "video-editor-draft":
                self.verify_video_editor_restored()
            elif self.scenario == "manual-memory-commit":
                self.verify_manual_memory_commit_restored()
            elif self.scenario == "manual-memory-draft":
                self.verify_manual_memory_restored()
            else:
                self.verify_video_restored()
            if self.scenario == "motion-draft":
                self.verify_motion_back(value["rows"][0])
            elif self.scenario == "video-editor-draft":
                self.verify_video_editor_back(value["rows"][0])
            elif self.scenario in ("collage-publication", "gif-publication", *MOTION_PUBLICATION_SCENARIOS):
                pass  # Result remains visible after chooser handoffs; sources are verified without discarding it.
            elif self.scenario in ("manual-memory-draft", "manual-memory-commit"):
                pass  # Result verification already returned through MemoriesBrowser to Collections and checked selection there.
            else:
                self.phase = "back-original-selection-and-source-hashes"
                self.shell("input", "keyevent", "KEYCODE_BACK")
                self.find("resource-id", "timeline_grid", scroll=False)
                self.find("text", value["selectionLabel"])
                for row in value["rows"]:
                    tile = self.find("resource-id", "media_external_primary_" + str(row["_id"]))
                    require_selected_tile(tile, "media_external_primary_" + str(row["_id"]))
                    require(self.source_hash(row) == row["sha256"], "Owned original changed")
                if self.scenario == "collage-draft":
                    self.require_no_collage_export("back-original-selection")
                    self.no_auto_export_verified = True
                elif self.scenario == "gif-draft":
                    self.require_no_gif_export("back-original-selection")
                    self.no_auto_export_verified = True
                elif self.scenario == "memory-video":
                    self.require_no_memory_video_export("back-original-selection")
                    self.no_auto_export_verified = True
            self.ui_complete = True
            if self.scenario == "motion-draft":
                self.verify_motion_no_saved_keyframe(value["rows"][0])
            elif self.scenario in ("manual-memory-draft", "manual-memory-commit"):
                self.verify_manual_memory_before_source_cleanup()
            elif self.scenario in MOTION_PUBLICATION_SCENARIOS:
                self.verify_motion_publication_before_source_cleanup()
            elif self.scenario == "gif-publication":
                self.verify_gif_publication_before_source_cleanup()
            elif self.scenario == "collage-publication":
                self.verify_collage_publication_before_source_cleanup()
        except Exception as error:
            failure = dict(phase=self.phase, type=type(error).__name__, message=str(error))
            if self.death_confirmed:
                try:
                    png = self.call("exec-out", "screencap", "-p", binary=True)[0]
                    (self.out / "restoration-failure.png").write_bytes(png)
                except Exception as capture_error:
                    self.record(result="Failure screenshot unavailable", reason=str(capture_error))
        finally:
            try:
                # An unfinished seed must stop before cleanup; its abort is never the measured app death.
                if self.process is not None and self.process.poll() is None:
                    value = self.read_manifest()
                    require(value is not None, "No ownership manifest for native abort; retained")
                    self.phase = "abort-owned-native-stage"
                    self.kill_owned(value["pid"])
                    self.native_finished()
                if self.process is not None:
                    self.native_finished()
                    if self.scenario == "motion-draft" and not self.keyframe_rows_verified:
                        self.record(result="Motion source retained: pre-cleanup keyframe verification did not pass",
                                    cleanupComplete=False, cascadeDeletionPrevented=True)
                    elif self.scenario in ("manual-memory-draft", "manual-memory-commit") and not self.manual_memory_verified:
                        if self.native_completed and not self.manual_save_executed and self.accessibility_owner == "host":
                            self.verify_manual_memory_absence_before_source_cleanup()
                        if self.manual_memory_absence_verified:
                            self.cleanup()
                        else:
                            self.record(result="Manual memory sources retained: exact memory verification/cleanup did not pass",
                                        cleanupComplete=False, cascadeDeletionPrevented=True)
                    elif self.scenario in MOTION_PUBLICATION_SCENARIOS and not self.motion_publication_verified:
                        self.record(result="Motion source/output retained: publication verification did not pass", cleanupComplete=False)
                    elif self.scenario == "gif-publication" and not self.gif_publication_verified:
                        self.record(result="GIF source/output retained: publication verification did not pass", cleanupComplete=False)
                    elif self.scenario == "collage-publication" and not self.collage_publication_verified:
                        self.record(result="Collage source/output retained: publication verification did not pass", cleanupComplete=False)
                    else:
                        self.cleanup()
            except Exception as error:
                cleanup_error = dict(phase=self.phase, type=type(error).__name__, message=str(error))
            result = dict(status="PASS" if self.ui_complete and self.death_confirmed and self.cleanup_complete and cleanup_error is None else "FAIL",
                          fixtureUuid=self.fixture, package=PACKAGE, scenario=self.scenario, oldPid=self.old_pid, newPid=self.new_pid,
                          taskId=getattr(self, "task_id", None), nativeSeedCompleted=self.native_completed,
                          actualDeathConfirmed=self.death_confirmed, restoredUiVerified=self.ui_complete,
                          cleanupComplete=self.cleanup_complete, exportExecuted=self.motion_export_attempted if self.scenario in MOTION_PUBLICATION_SCENARIOS else self.gif_export_attempted if self.scenario == "gif-publication" else self.collage_export_attempted if self.scenario == "collage-publication" else False,
                          noAutoExportVerified=self.no_auto_export_verified,
                          noAutoPlayVerified=self.no_auto_play_verified if self.scenario in ("gif-draft", "motion-draft", "video-editor-draft") else None,
                          keyFrameRowsVerified=self.keyframe_rows_verified if self.scenario == "motion-draft" else None,
                          setKeyFrameExecuted=False if self.scenario == "motion-draft" else None,
                          manualMemoryRowsVerified=self.manual_memory_verified if self.scenario in ("manual-memory-draft", "manual-memory-commit") else None,
                          manualMemoryCleaned=self.manual_memory_verified if self.scenario in ("manual-memory-draft", "manual-memory-commit") else None,
                          manualMemoryAbsenceVerified=self.manual_memory_absence_verified if self.scenario in ("manual-memory-draft", "manual-memory-commit") else None,
                          manualSaveExecuted=self.manual_save_executed if self.scenario in ("manual-memory-draft", "manual-memory-commit") else None,
                          commitInterruptionVerified=(self.manual_memory_verified and self.commit_recovery_opened) if self.scenario == "manual-memory-commit"
                              else False if self.scenario == "manual-memory-draft" else None,
                          draftUuidUiCompared=False if self.scenario in ("manual-memory-draft", "manual-memory-commit") else None,
                          publicationInterruptionVerified=self.motion_publication_verified if self.scenario in MOTION_PUBLICATION_SCENARIOS else self.gif_publication_verified if self.scenario == "gif-publication" else self.collage_publication_verified if self.scenario == "collage-publication" else None,
                          recipientRenderingVerified=False if self.scenario in ("collage-publication", "gif-publication", *MOTION_PUBLICATION_SCENARIOS) else None,
                          failure=failure, cleanupFailure=cleanup_error)
            (self.out / "result.json").write_text(json.dumps(result, indent=2) + "\n")
            print(json.dumps(result))
        return 0 if result["status"] == "PASS" else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS, default="memory-video")
    parser.add_argument("--serial", choices=tuple(DEVICES), required=True)
    parser.add_argument("--observer-sha256", help="Exact preinstalled real-display shell observer hash (assigned API30/API35 lanes)")
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    for digest in (args.apk_sha256, args.test_apk_sha256):
        require(re.fullmatch(r"[0-9a-f]{64}", digest), "Exact installed APK hashes are required")
    return Probe(args).execute()


if __name__ == "__main__":
    raise SystemExit(main())
