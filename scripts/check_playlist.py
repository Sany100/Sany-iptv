#!/usr/bin/env python3
"""Check stream reachability for every entry in playlist.m3u.

This checker is intentionally non-destructive: it never edits or removes playlist
entries. A successful HTTP response is only a reachability signal, not proof of
continuous playback.
"""
from __future__ import annotations

import concurrent.futures
import datetime as dt
import json
import re
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

PLAYLIST = Path("playlist.m3u")
JSON_OUT = Path("channel-check-report.json")
MD_OUT = Path("channel-check-report.md")
WORKERS = 24
TIMEOUT_SECONDS = 12
RETRIES = 1
USER_AGENT = "SANY-IPTV-Playlist-Health-Checker/1.0"


def read_entries(path: Path):
    lines = path.read_text(encoding="utf-8-sig", errors="replace").splitlines()
    if not any(line.strip().upper().startswith("#EXTM3U") for line in lines[:10]):
        raise SystemExit("Invalid playlist: #EXTM3U header missing")
    entries = []
    current_name = None
    for line in lines:
        line = line.strip()
        if line.startswith("#EXTINF"):
            current_name = line.rsplit(",", 1)[-1].strip() if "," in line else "Unknown"
        elif line and not line.startswith("#") and line.lower().startswith(("http://", "https://")):
            if current_name is not None:
                entries.append({"name": current_name or "Unknown", "url": line})
                current_name = None
    if not entries:
        raise SystemExit("Invalid playlist: no EXTINF/URL entries found")
    return entries


def classify_http_error(code: int):
    if code in (401, 403, 429):
        return "Restricted"
    if code == 404 or code == 410:
        return "Offline"
    return "Error"


def check_one(item):
    name, url = item["name"], item["url"]
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return {"name": name, "url": url, "status": "Error", "http_code": None, "detail": "Invalid HTTP(S) URL"}

    last_error = "Unknown network error"
    for attempt in range(RETRIES + 1):
        request = urllib.request.Request(
            url,
            headers={
                "User-Agent": USER_AGENT,
                "Accept": "*/*",
                "Range": "bytes=0-4095",
                "Cache-Control": "no-cache",
            },
            method="GET",
        )
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                code = response.getcode()
                sample = response.read(4096)
                content_type = response.headers.get("Content-Type", "").lower()
                final_url = response.geturl()
            if code not in (200, 206):
                return {"name": name, "url": url, "status": classify_http_error(code), "http_code": code, "detail": "Unexpected HTTP response"}
            if not sample:
                return {"name": name, "url": url, "status": "Error", "http_code": code, "detail": "Empty response"}
            text_sample = sample.lstrip(b"\xef\xbb\xbf \t\r\n").lower()
            path = urlparse(final_url).path.lower()
            if "text/html" in content_type or text_sample.startswith((b"<!doctype html", b"<html")):
                return {"name": name, "url": url, "status": "Error", "http_code": code, "detail": "HTTP page returned instead of stream"}
            if path.endswith((".m3u8", ".m3u")) and b"#extm3u" not in text_sample:
                return {"name": name, "url": url, "status": "Error", "http_code": code, "detail": "Playlist response has no #EXTM3U header"}
            if path.endswith(".mpd") and b"<mpd" not in text_sample:
                return {"name": name, "url": url, "status": "Error", "http_code": code, "detail": "DASH response has no MPD header"}
            return {"name": name, "url": url, "status": "Working", "http_code": code, "detail": "HTTP stream endpoint responded", "final_url": final_url}
        except urllib.error.HTTPError as exc:
            last_error = f"HTTP {exc.code}"
            status = classify_http_error(exc.code)
            if status != "Error" or attempt == RETRIES:
                return {"name": name, "url": url, "status": status, "http_code": exc.code, "detail": last_error}
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            last_error = f"{type(exc).__name__}: {str(exc)[:180]}"
            if attempt == RETRIES:
                return {"name": name, "url": url, "status": "Error", "http_code": None, "detail": last_error}
        time.sleep(1.0 * (attempt + 1))
    return {"name": name, "url": url, "status": "Error", "http_code": None, "detail": last_error}


def main():
    entries = read_entries(PLAYLIST)
    started = dt.datetime.now(dt.timezone.utc)
    with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
        results = list(pool.map(check_one, entries))
    counts = {key: sum(row["status"] == key for row in results) for key in ("Working", "Offline", "Restricted", "Error")}
    report = {
        "checked_at_utc": started.isoformat(timespec="seconds"),
        "total": len(results),
        "unique_urls": len({row["url"] for row in results}),
        "counts": counts,
        "note": "Reachability check only; not a guarantee of continuous playback. Playlist entries were not modified.",
        "results": results,
    }
    JSON_OUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# SANY IPTV Channel Check",
        "",
        f"- Checked (UTC): {report['checked_at_utc']}",
        f"- Total entries: {report['total']}",
        f"- Unique URLs: {report['unique_urls']}",
        f"- Working: {counts['Working']}",
        f"- Offline: {counts['Offline']}",
        f"- Restricted: {counts['Restricted']}",
        f"- Error: {counts['Error']}",
        "",
        "> HTTP reachability is not a guarantee of continuous playback. No playlist entries were removed or changed.",
        "",
        "| Channel | Status | HTTP | Details |",
        "|---|---|---:|---|",
    ]
    for row in results:
        safe_name = row["name"].replace("|", "\\|")
        safe_detail = row["detail"].replace("|", "\\|")
        lines.append(f"| {safe_name} | {row['status']} | {row['http_code'] or '-'} | {safe_detail} |")
    MD_OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Checked {len(results)} channels: " + ", ".join(f"{k}={v}" for k, v in counts.items()))
    print("The checker did not modify playlist.m3u.")


if __name__ == "__main__":
    main()
