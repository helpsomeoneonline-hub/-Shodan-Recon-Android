"""Isolated live Shodan verification; this file never participates in an APK build."""
from __future__ import annotations

import http.client
import json
import os
import socket
import ssl
import sys
from typing import Callable
from urllib.parse import urlencode

HOST = "api.shodan.io"
DEFAULT_QUERY = "port:80 (DNVRS-Webs OR Hikvision-Webs OR web OR App-webs OR DVRDVS-Webs)"
SEARCH_FIELDS = "ip_str,port,product,org,location.country_name,timestamp"
MAX_RESPONSE_BYTES = 1_200_000
KNOWN_PLANS = {"free", "dev", "developer", "oss", "edu", "smallbiz", "corporate", "enterprise", "member", "subscription"}


class VerificationFailure(Exception):
    """Contains only a fixed diagnostic; never retain a request URL or response body."""


def status_message(status: int) -> str:
    if status == 401:
        return "Shodan authentication failed (HTTP 401). The configured account key is invalid or expired."
    if status == 402:
        return "Shodan requires additional account credits or plan access (HTTP 402)."
    if status == 403:
        return "Shodan denied this operation (HTTP 403). Check account permissions, plan and query credits."
    if status == 429:
        return "Shodan rate limit reached (HTTP 429). Retry verification later."
    if 300 <= status <= 399:
        return f"Shodan returned an unexpected redirect (HTTP {status}); redirects are disabled."
    if 500 <= status <= 599:
        return f"Shodan service is unavailable (HTTP {status}). Retry verification later."
    return f"Shodan request failed (HTTP {status})."


def fetch_json(key: str, endpoint: str, connection_factory: Callable, *, search: bool = False) -> dict:
    params = {"key": key}
    if search:
        params.update({"query": DEFAULT_QUERY, "page": "1", "minify": "true", "fields": SEARCH_FIELDS})
    connection = None
    try:
        # Fixed HTTPS host with ordinary certificate/hostname verification. HTTPSConnection
        # does not follow redirects, and no Authorization/passwords are sent to discovered devices.
        connection = connection_factory(HOST, timeout=20)
        connection.request("GET", endpoint + "?" + urlencode(params), headers={"Accept": "application/json"})
        response = connection.getresponse()
        status = response.status
        if type(status) is not int or status < 100 or status > 599:
            raise VerificationFailure("Shodan returned an invalid HTTP status.")
        if status < 200 or status > 299:
            raise VerificationFailure(status_message(status))
        body = response.read(MAX_RESPONSE_BYTES + 1)
        if len(body) > MAX_RESPONSE_BYTES:
            raise VerificationFailure("Shodan response exceeded the 1.2 MB verification limit.")
        try:
            parsed = json.loads(body)
        except (ValueError, UnicodeError):
            raise VerificationFailure("Shodan returned invalid JSON; response contents were withheld.") from None
        if not isinstance(parsed, dict) or "error" in parsed:
            raise VerificationFailure("Shodan returned an API error or unexpected JSON; response contents were withheld.")
        return parsed
    except VerificationFailure:
        raise
    except (socket.timeout, TimeoutError):
        raise VerificationFailure("Shodan request timed out after the configured 20-second socket timeout.") from None
    except ssl.SSLError:
        raise VerificationFailure("Shodan HTTPS certificate verification failed.") from None
    except Exception:
        # Exception text can include the entire key-bearing request; do not print or retain it.
        raise VerificationFailure("Shodan network request failed. Check service availability and network access.") from None
    finally:
        if connection is not None:
            try:
                connection.close()
            except Exception:
                pass


def numeric_field(data: dict, name: str) -> int:
    value = data.get(name)
    if type(value) is not int or value < 0:
        raise VerificationFailure("Shodan response is missing expected numeric metadata; response contents were withheld.")
    return value


def verify(key: str, connection_factory: Callable = http.client.HTTPSConnection, output: Callable = print) -> int:
    if not key:
        output("SKIPPED: SHODAN_API_KEY is not configured. Live account and search verification could not be performed.")
        return 0
    def emit(message: str) -> None:
        # Defense in depth also covers a secret coincidentally matching a plan label or numeric field.
        output(message.replace(key, "[redacted]"))
    try:
        account = fetch_json(key, "/api-info", connection_factory)
        query_credits = numeric_field(account, "query_credits")
        scan_credits = numeric_field(account, "scan_credits")
        # Only fixed, recognized plan labels may appear in logs. Never echo server-controlled strings.
        plan = account.get("plan")
        safe_plan = plan.lower() if isinstance(plan, str) and plan.lower() in KNOWN_PLANS and key not in plan else "withheld"
        emit(f"Account verified: HTTP 200; plan={safe_plan}; query credits={query_credits}; scan credits={scan_credits}.")
        search = fetch_json(key, "/shodan/host/search", connection_factory, search=True)
        total = numeric_field(search, "total")
        matches = search.get("matches")
        if not isinstance(matches, list):
            raise VerificationFailure("Shodan search response is missing its result list; response contents were withheld.")
        for match in matches:
            if (not isinstance(match, dict) or not isinstance(match.get("ip_str"), str)
                    or not match["ip_str"] or type(match.get("port")) is not int
                    or not 1 <= match["port"] <= 65535):
                raise VerificationFailure("Shodan search result is missing expected IP and port fields; response contents were withheld.")
        emit(f"Default-query search verified: HTTP 200; page=1; total={total}; returned results={len(matches)}.")
        emit("PASSED: Live account and one search verified. No discovered device was contacted; no APK build or artifact received the key.")
        return 0
    except VerificationFailure as error:
        emit("FAILED: " + str(error))
        return 1
    except Exception:
        emit("FAILED: Verification could not complete; diagnostic details were withheld to protect the account key.")
        return 1


def main() -> int:
    return verify(os.environ.get("SHODAN_API_KEY", ""))


if __name__ == "__main__":
    sys.exit(main())
