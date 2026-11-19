import hmac
import json
import os
from datetime import datetime, timezone
from typing import Optional
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import HTMLResponse

try:
    from google.analytics.data_v1beta import BetaAnalyticsDataClient
    from google.analytics.data_v1beta.types import (
        DateRange,
        Filter,
        FilterExpression,
        Metric,
    )
    from google.oauth2 import service_account
except ImportError:
    BetaAnalyticsDataClient = None
    DateRange = Filter = FilterExpression = Metric = None
    service_account = None

app = FastAPI(title="SANY IPTV Admin Dashboard", docs_url=None, redoc_url=None)
BASE_DIR = Path(__file__).resolve().parent
INDEX_FILE = BASE_DIR / "static" / "index.html"


def require_admin(authorization: Optional[str] = Header(default=None)) -> None:
    expected = os.getenv("ADMIN_TOKEN", "")
    if not expected:
        raise HTTPException(status_code=503, detail="Admin dashboard token is not configured on the server.")
    supplied = ""
    if authorization and authorization.lower().startswith("bearer "):
        supplied = authorization[7:].strip()
    if not supplied or not hmac.compare_digest(supplied, expected):
        raise HTTPException(status_code=401, detail="Invalid admin token.")


def fetch_github_downloads() -> dict:
    repository = os.getenv("GITHUB_REPOSITORY", "Sany100/Sany-iptv").strip()
    mobile_total = 0
    tv_total = 0
    releases_seen = 0
    latest_release = None

    for page in range(1, 11):
        url = f"https://api.github.com/repos/{repository}/releases?per_page=100&page={page}"
        request = Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": "SANY-IPTV-Admin-Dashboard",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urlopen(request, timeout=20) as response:
            releases = json.loads(response.read().decode("utf-8"))

        if not isinstance(releases, list):
            raise RuntimeError("GitHub returned an unexpected releases response.")

        if page == 1 and releases:
            first = releases[0]
            latest_release = {
                "tag": first.get("tag_name", ""),
                "name": first.get("name", ""),
                "published_at": first.get("published_at", ""),
                "url": first.get("html_url", ""),
            }

        releases_seen += len(releases)
        for release in releases:
            for asset in release.get("assets", []):
                name = str(asset.get("name", "")).lower()
                count = int(asset.get("download_count", 0) or 0)
                if name == "app-mobile-release.apk":
                    mobile_total += count
                elif name == "app-tv-release.apk":
                    tv_total += count

        if len(releases) < 100:
            break

    return {
        "available": True,
        "repository": repository,
        "mobile_downloads": mobile_total,
        "tv_downloads": tv_total,
        "total_downloads": mobile_total + tv_total,
        "releases_checked": releases_seen,
        "latest_release": latest_release,
        "note": "GitHub asset download count; not unique people and not confirmed installs.",
    }


def fetch_ga4_analytics(days: int) -> dict:
    property_id = os.getenv("GA4_PROPERTY_ID", "").strip()
    service_account_json = os.getenv("GOOGLE_SERVICE_ACCOUNT_JSON", "").strip()

    if not property_id or not service_account_json:
        return {
            "available": False,
            "error": "Firebase/GA4 reporting is not connected yet. Add GA4_PROPERTY_ID and GOOGLE_SERVICE_ACCOUNT_JSON to the Render environment.",
        }

    if BetaAnalyticsDataClient is None or service_account is None:
        return {
            "available": False,
            "error": "Google Analytics Data API package is missing on the server.",
        }

    try:
        info = json.loads(service_account_json)
        credentials = service_account.Credentials.from_service_account_info(
            info,
            scopes=["https://www.googleapis.com/auth/analytics.readonly"],
        )
        client = BetaAnalyticsDataClient(credentials=credentials)
        date_ranges = [DateRange(start_date=f"{days}daysAgo", end_date="today")]

        overview = client.run_report(
            request={
                "property": f"properties/{property_id}",
                "date_ranges": date_ranges,
                "metrics": [
                    Metric(name="activeUsers"),
                    Metric(name="totalUsers"),
                    Metric(name="newUsers"),
                ],
            }
        )

        values = ["0", "0", "0"]
        if overview.rows:
            values = [item.value for item in overview.rows[0].metric_values]

        first_open_report = client.run_report(
            request={
                "property": f"properties/{property_id}",
                "date_ranges": date_ranges,
                "metrics": [Metric(name="eventCount")],
                "dimension_filter": FilterExpression(
                    filter=Filter(
                        field_name="eventName",
                        string_filter=Filter.StringFilter(
                            value="first_open",
                            match_type=Filter.StringFilter.MatchType.EXACT,
                        ),
                    )
                ),
            }
        )
        first_opens = "0"
        if first_open_report.rows:
            first_opens = first_open_report.rows[0].metric_values[0].value

        return {
            "available": True,
            "active_users": int(values[0]),
            "total_users": int(values[1]),
            "new_users": int(values[2]),
            "first_opens": int(first_opens),
            "period_days": days,
            "scope": "Firebase Analytics / linked GA4 property",
            "note": "first_open counts first app launches reported by Analytics; it is not a verified device-install count. Current Android Firebase setup is for the Mobile app only.",
        }
    except Exception as exc:
        return {
            "available": False,
            "error": f"GA4 report could not be loaded: {type(exc).__name__}: {str(exc)[:300]}",
        }


@app.get("/", response_class=HTMLResponse)
def dashboard_home():
    if not INDEX_FILE.exists():
        raise HTTPException(status_code=500, detail="Dashboard page is missing.")
    return HTMLResponse(INDEX_FILE.read_text(encoding="utf-8"))


@app.get("/health")
def health():
    return {"status": "ok", "service": "sany-iptv-admin-dashboard"}


@app.get("/api/dashboard")
def dashboard_data(
    days: int = Query(default=28, ge=7, le=90),
    _admin: None = Depends(require_admin),
):
    try:
        downloads = fetch_github_downloads()
    except (HTTPError, URLError, TimeoutError, RuntimeError, ValueError) as exc:
        downloads = {
            "available": False,
            "error": f"GitHub release data could not be loaded: {type(exc).__name__}: {str(exc)[:250]}",
        }

    analytics = fetch_ga4_analytics(days)
    return {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "period_days": days,
        "downloads": downloads,
        "analytics": analytics,
    }
