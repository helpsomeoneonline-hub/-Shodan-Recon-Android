"""Verification tests use fixtures only; no environment key is read and no network is contacted."""
import io
import json
import socket
import ssl
import unittest
from contextlib import redirect_stdout
from urllib.parse import parse_qs, urlsplit

import shodan_api_check as checker

FAKE_KEY = "fixture-account-key-not-a-real-key"


class FakeResponse:
    def __init__(self, status=200, data=None, raw=None):
        self.status = status
        self.body = raw if raw is not None else json.dumps(data).encode("utf-8")
        self.read_limit = None

    def read(self, limit):
        self.read_limit = limit
        return self.body[:limit]


class FakeFactory:
    def __init__(self, responses, failure=None):
        self.responses = list(responses)
        self.failure = failure
        self.requests = []
        self.targets = []
        self.closed = 0

    def __call__(self, host, timeout):
        fixture = self
        fixture.targets.append((host, timeout))
        class Connection:
            def request(self, method, path, headers):
                fixture.requests.append((method, path, headers))
                if fixture.failure is not None:
                    raise fixture.failure

            def getresponse(self):
                return fixture.responses.pop(0)

            def close(self):
                fixture.closed += 1
        return Connection()


def account(**updates):
    data = {"plan": "dev", "query_credits": 10, "scan_credits": 0}
    data.update(updates)
    return FakeResponse(data=data)


def search(**updates):
    data = {"total": 100, "matches": [{"ip_str": "192.0.2.1", "port": 80, "secret": FAKE_KEY}]}
    data.update(updates)
    return FakeResponse(data=data)


class ShodanVerificationTests(unittest.TestCase):
    def run_check(self, fixture, key=FAKE_KEY):
        captured = io.StringIO()
        with redirect_stdout(captured):
            result = checker.verify(key, fixture)
        text = captured.getvalue()
        self.assertNotIn(FAKE_KEY, text)
        self.assertNotIn("api.shodan.io", text)
        self.assertNotIn("http://", text)
        self.assertNotIn("https://", text)
        self.assertNotIn("192.0.2.1", text)
        self.assertNotIn("key=", text)
        return result, text

    def test_success_contacts_only_fixed_https_host_for_account_and_one_page(self):
        fixture = FakeFactory([account(), search()])
        result, text = self.run_check(fixture)
        self.assertEqual(0, result)
        self.assertIn("PASSED:", text)
        self.assertIn("plan=dev", text)
        self.assertIn("query credits=10", text)
        self.assertIn("returned results=1", text)
        self.assertEqual([(checker.HOST, 20), (checker.HOST, 20)], fixture.targets)
        self.assertEqual(2, fixture.closed)
        self.assertEqual(2, len(fixture.requests))
        paths = []
        for method, path, headers in fixture.requests:
            parts = urlsplit(path)
            paths.append(parts.path)
            self.assertEqual("GET", method)
            self.assertEqual(FAKE_KEY, parse_qs(parts.query)["key"][0])
            self.assertEqual({"Accept": "application/json"}, headers)
        self.assertEqual(["/api-info", "/shodan/host/search"], paths)
        params = parse_qs(urlsplit(fixture.requests[1][1]).query)
        self.assertEqual(["1"], params["page"])
        self.assertEqual([checker.DEFAULT_QUERY], params["query"])
        self.assertEqual([checker.SEARCH_FIELDS], params["fields"])
        self.assertEqual(["true"], params["minify"])

    def test_missing_secret_reports_skipped_without_network(self):
        fixture = FakeFactory([])
        result, text = self.run_check(fixture, key="")
        self.assertEqual(0, result)
        self.assertIn("SKIPPED:", text)
        self.assertIn("could not be performed", text)
        self.assertEqual([], fixture.requests)

    def test_auth_error_never_echoes_response_body_or_requests_search(self):
        fixture = FakeFactory([FakeResponse(status=401, data={"error": FAKE_KEY + " https://api.shodan.io/"})])
        result, text = self.run_check(fixture)
        self.assertEqual(1, result)
        self.assertIn("HTTP 401", text)
        self.assertEqual(1, len(fixture.requests))

    def test_rate_limit_and_access_denied_are_reported_accurately(self):
        for status, expected in [(402, "credits"), (403, "permissions"), (429, "rate limit"), (503, "unavailable")]:
            with self.subTest(status=status):
                fixture = FakeFactory([account(), FakeResponse(status=status, data={"error": FAKE_KEY})])
                result, text = self.run_check(fixture)
                self.assertEqual(1, result)
                self.assertIn("HTTP " + str(status), text)
                self.assertIn(expected, text)
                self.assertEqual(2, len(fixture.requests))

    def test_redirect_is_rejected_without_follow_up(self):
        fixture = FakeFactory([FakeResponse(status=302, data={"redirect": "https://elsewhere.invalid/?key=" + FAKE_KEY})])
        result, text = self.run_check(fixture)
        self.assertEqual(1, result)
        self.assertIn("redirects are disabled", text)
        self.assertEqual(1, len(fixture.requests))

    def test_exception_containing_secret_and_url_is_sanitized(self):
        fixture = FakeFactory([], failure=RuntimeError("https://api.shodan.io/?key=" + FAKE_KEY))
        result, text = self.run_check(fixture)
        self.assertEqual(1, result)
        self.assertIn("network request failed", text)
        self.assertEqual(1, fixture.closed)

    def test_timeout_and_tls_failures_have_fixed_diagnostics(self):
        for failure, expected in [(socket.timeout(FAKE_KEY), "timed out"), (ssl.SSLError(FAKE_KEY), "certificate")]:
            with self.subTest(kind=type(failure).__name__):
                fixture = FakeFactory([], failure=failure)
                result, text = self.run_check(fixture)
                self.assertEqual(1, result)
                self.assertIn(expected, text)

    def test_server_controlled_plan_label_is_withheld(self):
        result, text = self.run_check(FakeFactory([account(plan="https://api.shodan.io/?key=" + FAKE_KEY), search()]))
        self.assertEqual(0, result)
        self.assertIn("plan=withheld", text)

    def test_malformed_json_and_api_errors_are_not_logged(self):
        for response in [FakeResponse(raw=(FAKE_KEY + " https://api.shodan.io/").encode()),
                         FakeResponse(data={"error": FAKE_KEY}), FakeResponse(data=[FAKE_KEY])]:
            with self.subTest(body_type=type(response.body).__name__):
                result, text = self.run_check(FakeFactory([response]))
                self.assertEqual(1, result)
                self.assertIn("contents were withheld", text)

    def test_numeric_metadata_and_match_list_are_validated(self):
        for responses in [[account(query_credits=FAKE_KEY)], [account(scan_credits=True)],
                          [account(), search(total=-1)], [account(), search(matches=FAKE_KEY)]]:
            with self.subTest(response_count=len(responses)):
                result, text = self.run_check(FakeFactory(responses))
                self.assertEqual(1, result)
                self.assertIn("contents were withheld", text)

    def test_response_size_is_bounded(self):
        response = FakeResponse(raw=b"x" * (checker.MAX_RESPONSE_BYTES + 2))
        result, text = self.run_check(FakeFactory([response]))
        self.assertEqual(1, result)
        self.assertIn("1.2 MB", text)
        self.assertEqual(checker.MAX_RESPONSE_BYTES + 1, response.read_limit)

    def test_projected_results_require_ip_and_port_without_logging_values(self):
        for match in [{"port": 80}, {"ip_str": "192.0.2.1"},
                      {"ip_str": "192.0.2.1", "port": "80"},
                      {"ip_str": "192.0.2.1", "port": 0},
                      {"ip_str": "", "port": 80}, FAKE_KEY]:
            with self.subTest(match_type=type(match).__name__):
                result, text = self.run_check(FakeFactory([account(), search(matches=[match])]))
                self.assertEqual(1, result)
                self.assertIn("missing expected IP and port fields", text)
                self.assertIn("contents were withheld", text)

    def test_coincident_numeric_secret_is_redacted_from_stdout(self):
        fixture = FakeFactory([account(query_credits=123456789), search(total=123456789)])
        result, text = self.run_check(fixture, key="123456789")
        self.assertEqual(0, result)
        self.assertNotIn("123456789", text)


if __name__ == "__main__":
    unittest.main()
