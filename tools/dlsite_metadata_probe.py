"""Small, opt-in feasibility tool for public DLsite credit metadata, not an app updater.

Example:
  python tools/dlsite_metadata_probe.py --rj RJ99000001 \
      --output artifacts/local-metadata.json

Only product credit names/links are retained. No account, media, descriptions or
search/list enumeration is used. Existing successful records survive refresh errors.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

ORIGIN = "https://www.dlsite.com"
USER_AGENT = "YsPlayerMetadataResearch/0.1"
MAX_HTML_BYTES = 2_000_000


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def normalize_name(name: str) -> str:
    # A lookup candidate key, never proof that two credits are the same person.
    return " ".join(unicodedata.normalize("NFKC", name).split())


def official_url(href: str) -> str | None:
    result = urllib.parse.urljoin(ORIGIN, href)
    parts = urllib.parse.urlsplit(result)
    return result if parts.scheme == "https" and parts.netloc == "www.dlsite.com" else None


class CreditRows(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.rows: list[dict] = []
        self.row: dict | None = None
        self.link: dict | None = None
        self.header = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tr":
            self.row = {"header": [], "body": [], "links": []}
        elif self.row is not None:
            if tag == "th":
                self.header = True
            elif tag == "a":
                self.link = {"href": dict(attrs).get("href") or "", "text": []}

    def handle_data(self, data: str) -> None:
        if self.row is not None:
            self.row["header" if self.header else "body"].append(data)
        if self.link is not None:
            self.link["text"].append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag == "a" and self.link is not None:
            if self.row is not None:
                self.row["links"].append({"href": self.link["href"], "name": " ".join("".join(self.link["text"]).split())})
            self.link = None
        elif tag == "th":
            self.header = False
        elif tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None
            self.link = None
            self.header = False


def parse_credits(html: str, rj: str, source_url: str) -> dict:
    parser = CreditRows()
    parser.feed(html)
    circles: list[dict] = []
    voices: list[dict] = []
    voice_field_present = False
    for row in parser.rows:
        label = normalize_name("".join(row["header"]))
        if label in {"サークル名", "Circle"}:
            for link in row["links"]:
                url = official_url(link["href"])
                maker = re.search(r"/maker_id/(RG\d+)(?:[./]|$)", url or "")
                if maker and link["name"]:
                    item = {"id": maker.group(1), "name": link["name"], "profile_url": url}
                    if item not in circles:
                        circles.append(item)
        elif label in {"声優", "Voice Actor", "Voice actor"}:
            voice_field_present = True
            for link in row["links"]:
                url = official_url(link["href"])
                if url and link["name"]:
                    item = {"credited_name": link["name"], "lookup_key": normalize_name(link["name"]),
                            "source_link": url, "identity_status": "credited_name_only"}
                    if item not in voices:
                        voices.append(item)
    if not circles:
        raise ValueError("No circle ID/name found; possible changed markup, removed work or verification page")
    if voice_field_present and not voices:
        raise ValueError("Voice credit row exists but could not be parsed; do not overwrite a previous record")
    return {"rj": rj, "source_url": source_url, "circles": circles, "voice_credits": voices,
            "voice_field_present": voice_field_present, "fetched_at_utc": utc_now()}


class RobotsPolicy:
    def __init__(self, text: str, user_agent: str) -> None:
        groups: list[dict] = []
        group: dict = {"agents": [], "rules": [], "delay": 0.0}
        directives = False
        for line in text.splitlines():
            key, separator, value = line.split("#", 1)[0].partition(":")
            if not separator:
                continue
            key, value = key.strip().lower(), value.strip()
            if key == "user-agent":
                if directives:
                    groups.append(group)
                    group = {"agents": [], "rules": [], "delay": 0.0}
                    directives = False
                group["agents"].append(value.lower())
            elif group["agents"] and key in {"allow", "disallow", "crawl-delay"}:
                directives = True
                if key == "crawl-delay":
                    try:
                        group["delay"] = max(0.0, float(value))
                    except ValueError:
                        pass
                elif value:
                    group["rules"].append((key == "allow", value))
        if group["agents"]:
            groups.append(group)
        scores = [(max((len(a) if a != "*" else 0 for a in g["agents"] if a == "*" or a in user_agent.lower()), default=-1), g) for g in groups]
        best = max((score for score, _ in scores), default=-1)
        selected = [g for score, g in scores if score == best and score >= 0]
        self.rules = [rule for g in selected for rule in g["rules"]]
        self.delay = max([10.0] + [g["delay"] for g in selected])

    def allows(self, url: str) -> bool:
        parts = urllib.parse.urlsplit(url)
        target = parts.path + ("?" + parts.query if parts.query else "")
        matches: list[tuple[int, bool]] = []
        for allowed, pattern in self.rules:
            anchored = pattern.endswith("$")
            body = pattern[:-1] if anchored else pattern
            expression = "^" + re.escape(body).replace(r"\*", ".*") + ("$" if anchored else "")
            if re.search(expression, target):
                matches.append((len(body.replace("*", "")), allowed))
        return max(matches, default=(0, True))[1]


def download(url: str) -> str:
    if not official_url(url):
        raise ValueError("Only official HTTPS URLs are accepted")
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept-Language": "ja,en;q=0.5"})
    with urllib.request.urlopen(request, timeout=25) as response:
        if not official_url(response.url):
            raise ValueError("Unexpected redirect destination")
        expected = re.search(r"/product_id/(RJ\d+)", url)
        actual = re.search(r"/product_id/(RJ\d+)", response.url)
        if expected and (actual is None or expected.group(1) != actual.group(1)):
            raise ValueError("Product redirected to a different page")
        data = response.read(MAX_HTML_BYTES + 1)
        if len(data) > MAX_HTML_BYTES:
            raise ValueError("Response too large")
        return data.decode("utf-8", "strict")


def indexes(records: list[dict]) -> dict:
    circles: dict[str, dict] = {}
    voices: dict[str, dict] = {}
    for record in records:
        for circle in record["circles"]:
            entry = circles.setdefault(circle["id"], {"id": circle["id"], "observed_names": [], "works": []})
            if circle["name"] not in entry["observed_names"]:
                entry["observed_names"].append(circle["name"])
            if record["rj"] not in entry["works"]:
                entry["works"].append(record["rj"])
        for voice in record["voice_credits"]:
            entry = voices.setdefault(voice["lookup_key"], {"lookup_key": voice["lookup_key"], "credited_names": [],
                "works": [], "identity_status": "name_lookup_candidate_not_person_id"})
            if voice["credited_name"] not in entry["credited_names"]:
                entry["credited_names"].append(voice["credited_name"])
            if record["rj"] not in entry["works"]:
                entry["works"].append(record["rj"])
    return {"circles_by_id": list(circles.values()), "voices_by_name_candidate": list(voices.values())}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rj", nargs="+", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--section", choices=["maniax", "home"], default="maniax")
    parser.add_argument("--refresh", action="store_true", help="Refresh cached records; serial, at least 10 seconds per request")
    args = parser.parse_args()
    ids = list(dict.fromkeys(code.upper() for code in args.rj))
    if not all(re.fullmatch(r"RJ\d+", code) for code in ids):
        parser.error("Each ID must be RJ followed by digits")
    previous = json.loads(args.output.read_text(encoding="utf-8")) if args.output.exists() else {}
    records = {record["rj"]: record for record in previous.get("works", [])}
    pending = [code for code in ids if args.refresh or code not in records]
    errors = []
    delay = previous.get("request_policy", {}).get("minimum_delay_seconds", 10.0)
    if pending:
        robots = download(ORIGIN + "/robots.txt")
        policy = RobotsPolicy(robots, USER_AGENT)
        delay = policy.delay
        last_request = time.monotonic()
        for code in pending:
            url = f"{ORIGIN}/{args.section}/work/=/product_id/{code}.html"
            if not policy.allows(url):
                errors.append({"rj": code, "error": "robots_disallowed"})
                break
            time.sleep(max(0, delay - (time.monotonic() - last_request)))
            last_request = time.monotonic()
            try:
                updated = parse_credits(download(url), code, url)
                if records.get(code, {}).get("voice_field_present") and not updated["voice_field_present"]:
                    raise ValueError("Previously observed voice row missing; keep previous record")
                records[code] = updated
                print(json.dumps({"rj": code, "circle_names": [c["name"] for c in records[code]["circles"]],
                    "voice_names": [v["credited_name"] for v in records[code]["voice_credits"]]}, ensure_ascii=False), flush=True)
            except Exception as error:
                errors.append({"rj": code, "error": type(error).__name__, "message": str(error)})
                # Stop the run on access restrictions and throttling, not a retry storm.
                if isinstance(error, urllib.error.HTTPError) and error.code in {401, 403, 429}:
                    break
    result_records = [records[code] for code in sorted(records)]
    result = {"schema_version": 1, "purpose": "feasibility_sample_not_complete_catalog", "updated_at_utc": utc_now(),
        "source": "dlsite_public_product_html", "request_policy": {"robots_url": ORIGIN + "/robots.txt",
        "minimum_delay_seconds": delay, "concurrency": 1}, "works": result_records, "indexes": indexes(result_records), "errors": errors}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(args.output.suffix + ".tmp")
    temporary.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(args.output)
    print(json.dumps({"saved_records": len(result_records), "errors": len(errors), "output": str(args.output)}, ensure_ascii=False), flush=True)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
