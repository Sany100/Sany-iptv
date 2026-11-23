#!/usr/bin/env python3
"""Manual, read-only AniLight API smoke test. Never prints stream tokens or full URLs."""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

API = os.environ.get("ANILIGHT_API_BASE", "https://api.anilight.live/api").rstrip("/")
QUERY = os.environ.get("ANILIGHT_SMOKE_QUERY", "one piece")
TIMEOUT = 20
UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
      "AppleWebKit/537.36 Chrome/124.0 Safari/537.36")
results = []
failures = 0


def report(name, status, detail):
    global failures
    if status == "FAIL":
        failures += 1
    line = f"{status}: {name} — {detail}"
    print(line)
    results.append(line)


def request(path, method="GET", payload=None, headers=None, follow_redirects=True):
    hdrs = {
        "User-Agent": UA,
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": "https://anilight.live/",
    }
    if headers:
        hdrs.update(headers)
    data = None if payload is None else json.dumps(payload).encode()
    if data is not None:
        hdrs["Content-Type"] = "application/json"
    opener = urllib.request.build_opener() if follow_redirects else urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(API + path, data=data, headers=hdrs, method=method)
    try:
        with opener.open(req, timeout=TIMEOUT) as response:
            body = response.read(3_000_000).decode("utf-8", "replace")
            return response.status, response.headers, body
    except urllib.error.HTTPError as exc:
        body = exc.read(500_000).decode("utf-8", "replace")
        return exc.code, exc.headers, body
    except Exception as exc:
        raise RuntimeError(type(exc).__name__ + ": " + str(exc)[:180]) from exc


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def json_call(name, path, expected):
    try:
        status, _, body = request(path)
        if status != 200:
            report(name, "FAIL", f"HTTP {status}")
            return None
        value = json.loads(body)
        if not expected(value):
            report(name, "FAIL", "HTTP 200, unexpected JSON shape")
            return None
        report(name, "PASS", "HTTP 200; JSON shape valid")
        return value
    except Exception as exc:
        report(name, "FAIL", str(exc))
        return None


def first_media(value):
    if isinstance(value, dict):
        return value.get("media") or []
    return []


print("AniLight live API smoke test")
print("API host:", urllib.parse.urlparse(API).netloc)
print("Search query:", QUERY)
home = json_call("homepage", "/homepage",
                 lambda x: isinstance(x, dict) and ("popularMovies" in x or "trending" in x))
movie_rows = first_media(home.get("popularMovies")) if home else []
movies = [x for x in movie_rows if isinstance(x, dict) and x.get("slug") and x.get("format") == "MOVIE"]
if home:
    report("Anime movie catalogue", "PASS" if movies else "WARN",
           f"{len(movies)} movie entries in popularMovies")
    if not movies:
        print("No movie item available in this homepage response; movie detail/source checks skipped.")

encoded = urllib.parse.urlencode({"q": QUERY, "hideAdult": "true"})
search = json_call("search", "/search?" + encoded, lambda x: isinstance(x, list))
search_items = [x for x in (search or []) if isinstance(x, dict) and x.get("slug")]
if search is not None:
    report("Search results", "PASS" if search_items else "WARN",
           f"{len(search_items)} usable results")

# Exercise detail/watch/source resolution on the movie row first, then a series result.
candidates = []
if movies:
    candidates.append(("movie", movies[0]))
series = next((x for x in search_items if x.get("format") != "MOVIE"), None)
if series:
    candidates.append(("series", series))
if not candidates and search_items:
    candidates.append(("search-result", search_items[0]))

for kind, item in candidates:
    slug = urllib.parse.quote(str(item["slug"]), safe="")
    detail = json_call(kind + " detail", "/anime/" + slug,
                       lambda x: isinstance(x, dict) and bool(x.get("id")) and bool(x.get("title")))
    if not detail:
        continue
    watch = json_call(kind + " watch", "/watch/" + slug,
                      lambda x: isinstance(x, dict) and ("episodes" in x or "id" in x))
    episodes = (watch or {}).get("episodes") or []
    episode = next((e for e in episodes if isinstance(e, dict)), {})
    number = episode.get("number") or 1
    language = "dub" if episode.get("dub") is True else "sub"

    try:
        status, _, body = request("/sources/verify", method="POST", payload={"token": "direct"})
        session = json.loads(body) if status == 200 else {}
        token = session.get("sessionToken") if isinstance(session, dict) else None
        if not token:
            report(kind + " source session", "WARN", f"verify returned HTTP {status} without sessionToken")
            continue
        report(kind + " source session", "PASS", "session token acquired")
        params = urllib.parse.urlencode({"id": detail["id"], "episode": number, "language": language})
        status, _, body = request("/sources?" + params, headers={"x-source-session": token})
        if status != 200:
            report(kind + " source catalogue", "FAIL", f"HTTP {status}")
            continue
        source_doc = json.loads(body)
        rows = source_doc.get("sources") or []
        tokens = []
        for row in rows:
            raw = row.get("url") if isinstance(row, dict) else None
            if isinstance(raw, str) and raw:
                tokens.append(raw)
            elif isinstance(raw, list):
                tokens.extend(v for v in raw if isinstance(v, str) and v)
        report(kind + " source catalogue", "PASS" if isinstance(rows, list) else "FAIL",
               f"{len(rows)} source rows; {len(tokens)} resolver tokens")
        if not tokens:
            report(kind + " direct stream resolution", "WARN", "API returned no source tokens for this item")
            continue
        resolved = False
        for source_token in tokens[:5]:
            try:
                code, headers, _ = request("/lb/e/" + urllib.parse.quote(source_token, safe=""),
                                           follow_redirects=False)
                location = headers.get("Location", "")
                if 300 <= code < 400 and (not location or location.startswith("http")):
                    resolved = True
                    host = urllib.parse.urlparse(location).netloc if location else "redirect endpoint"
                    report(kind + " direct stream resolution", "PASS",
                           f"load-balancer HTTP {code}; destination host {host or 'not exposed'}")
                    break
            except Exception:
                pass
        if not resolved:
            report(kind + " direct stream resolution", "FAIL",
                   "source tokens returned, but none produced a valid HTTP redirect")
    except Exception as exc:
        report(kind + " source pipeline", "FAIL", str(exc))

if not candidates:
    report("detail/watch/source pipeline", "WARN", "no usable movie or search result to exercise")
print("\nSummary:", sum(x.startswith("PASS:") for x in results), "passed;",
      sum(x.startswith("WARN:") for x in results), "warnings;", failures, "failed")
sys.exit(1 if failures else 0)
