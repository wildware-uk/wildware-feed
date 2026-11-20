#!/usr/bin/env python3
"""Tests for asc.py that need no network and no real key: a throwaway key signs the token, and a
fake Apple answers the notarisation questions.

  python3 -m unittest discover -s .github/scripts -p 'test_*.py'
"""

import base64
import contextlib
import hashlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import asc  # noqa: E402

ID = "2efe2717-52ef-43a5-96dc-0797e4ca1041"
DMG = "Wildware-Feed-1.2.3-mac-arm64.dmg"


def unb64url(text):
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def raw_to_der(raw):
    """The JWT's r||s back into the DER form openssl checks."""
    def integer(value):
        value = value.lstrip(b"\0") or b"\0"
        if value[0] & 0x80:
            value = b"\0" + value
        return b"\x02" + bytes([len(value)]) + value
    body = integer(raw[:32]) + integer(raw[32:])
    return b"\x30" + bytes([len(body)]) + body


class TokenTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.key = os.path.join(self.dir.name, "key.p8")
        self.pub = os.path.join(self.dir.name, "key.pub")
        # The same shape as Apple's AuthKey_*.p8: a P-256 key in PKCS#8.
        subprocess.run(["openssl", "genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:P-256", "-out", self.key], check=True, capture_output=True)
        subprocess.run(["openssl", "pkey", "-in", self.key, "-pubout", "-out", self.pub], check=True, capture_output=True)
        env = {"ASC_KEY_ID": "ABCDE12345", "ASC_ISSUER_ID": "69a6de70-0000-47e3-e053-5b8c7c11a4d1", "ASC_KEY_PATH": self.key}
        self.env = mock.patch.dict(os.environ, env)
        self.env.start()

    def tearDown(self):
        self.env.stop()
        self.dir.cleanup()

    def test_token_is_an_es256_jwt_apple_accepts(self):
        for _ in range(20):  # DER signatures vary in length; make sure every shape converts.
            header, payload, signature = asc.token().split(".")
            self.assertEqual({"alg": "ES256", "kid": "ABCDE12345", "typ": "JWT"}, json.loads(unb64url(header)))
            claims = json.loads(unb64url(payload))
            self.assertEqual("69a6de70-0000-47e3-e053-5b8c7c11a4d1", claims["iss"])
            self.assertEqual("appstoreconnect-v1", claims["aud"])
            self.assertLessEqual(claims["exp"] - claims["iat"], 20 * 60, "Apple refuses tokens over 20 minutes")
            raw = unb64url(signature)
            self.assertEqual(64, len(raw))
            sig = os.path.join(self.dir.name, "sig.der")
            with open(sig, "wb") as f:
                f.write(raw_to_der(raw))
            check = subprocess.run(["openssl", "dgst", "-sha256", "-verify", self.pub, "-signature", sig],
                                   input=f"{header}.{payload}".encode(), capture_output=True)
            self.assertEqual(0, check.returncode, check.stdout + check.stderr)


class FakeApple:
    """Answers each status question with the next of [answers] (repeating the last), and the log questions."""

    def __init__(self, *answers, name=DMG, log=None, logs_fail=False):
        self.answers = list(answers)
        self.name = name
        self.log = log
        self.logs_fail = logs_fail
        self.asked = []
        self.authed = []

    def __call__(self, url, auth=True):
        self.asked.append(url)
        self.authed.append(auth)
        if url == f"{asc.NOTARY}/{ID}":
            answer = self.answers.pop(0) if len(self.answers) > 1 else self.answers[0]
            if isinstance(answer, int):
                return answer, None
            return 200, {"data": {"id": ID, "type": "submissions", "attributes": {"status": answer, "name": self.name, "createdDate": "2026-11-20T10:00:00.000Z"}}}
        if url == f"{asc.NOTARY}/{ID}/logs":
            if self.logs_fail:
                return 503, None
            return 200, {"data": {"id": ID, "type": "submissionsLog", "attributes": {"developerLogUrl": "https://logs.example/secret-link"}}}
        if url == "https://logs.example/secret-link":
            return 200, self.log
        return 404, None


class Clock:
    def __init__(self):
        self.now = 0.0
        self.pauses = []

    def sleep(self, seconds):
        self.pauses.append(seconds)
        self.now += seconds

    def __call__(self):
        return self.now


class NotaryTest(unittest.TestCase):
    def setUp(self):
        self.out = tempfile.NamedTemporaryFile("r", delete=False)
        self.out.close()
        self.dir = tempfile.TemporaryDirectory()
        self.dmg = os.path.join(self.dir.name, DMG)
        with open(self.dmg, "wb") as f:
            f.write(b"a signed disk image")
        self.sha = hashlib.sha256(b"a signed disk image").hexdigest()
        self.env = mock.patch.dict(os.environ, {"GITHUB_OUTPUT": self.out.name})
        self.env.start()

    def tearDown(self):
        self.env.stop()
        os.unlink(self.out.name)
        self.dir.cleanup()

    def good_log(self):
        return {"status": "Accepted", "archiveFilename": DMG, "sha256": self.sha, "issues": None}

    def run_quietly(self, action):
        printed = io.StringIO()
        with contextlib.redirect_stdout(printed):
            try:
                action()
                code = 0
            except SystemExit as e:
                code = e.code
        with open(self.out.name) as f:
            return code, printed.getvalue(), f.read()

    def wait(self, apple, budget=0, name=DMG):
        clock = Clock()
        code, printed, output = self.run_quietly(lambda: asc.notary_wait(ID, name, budget, get=apple, sleep=clock.sleep, clock=clock))
        return code, printed, clock, output

    def verify(self, apple):
        code, printed, _ = self.run_quietly(lambda: asc.notary_verify(ID, self.dmg, get=apple))
        return code, printed

    # Asking

    def test_asking_once_while_apple_checks_is_pending_and_waits_for_nothing(self):
        apple = FakeApple("In Progress")
        code, printed, clock, output = self.wait(apple)
        self.assertEqual(0, code)
        self.assertEqual("status=pending\n", output)
        self.assertEqual([], clock.pauses)
        self.assertEqual(1, len(apple.asked), "one question, no report fetched")
        self.assertIn("sent 2026-11-20T10:00:00.000Z", printed)

    def test_accepted_prints_apples_report(self):
        code, printed, _, output = self.wait(FakeApple("Accepted", log=self.good_log()))
        self.assertEqual(0, code)
        self.assertEqual("status=accepted\n", output)
        self.assertIn(self.sha, printed)
        self.assertNotIn("secret-link", printed, "the report's link works without the key: never printed")

    def test_rejected_prints_apples_report_and_fails(self):
        log = {"status": "Invalid", "issues": [{"path": "Wildware Feed.app/Contents/MacOS/x", "message": "The binary is not signed."}]}
        apple = FakeApple("Invalid", log=log)
        code, printed, _, output = self.wait(apple)
        self.assertEqual(1, code)
        self.assertEqual("status=rejected\n", output)
        self.assertIn("The binary is not signed.", printed)
        self.assertNotIn("secret-link", printed)
        self.assertEqual([True, True, False], apple.authed, "the report's link itself is fetched without the key")

    def test_a_submission_for_another_file_is_refused(self):
        code, printed, _, output = self.wait(FakeApple("Accepted", name="Something-else.dmg"))
        self.assertEqual(1, code)
        self.assertEqual("", output)
        self.assertIn("Wrong submission id?", printed)

    def test_without_a_name_any_file_is_reported(self):
        code, printed, _, _ = self.wait(FakeApple("Accepted", name="Whatever.dmg", log={"status": "Accepted"}), name=None)
        self.assertEqual(0, code)
        self.assertIn("Apple notarised Whatever.dmg", printed)

    def test_no_proper_answer_fails_so_it_is_asked_again_later(self):
        for status in (0, 404, 429, 503):
            code, _, _, output = self.wait(FakeApple(status))
            self.assertEqual(1, code, status)
            self.assertEqual("", output, "neither pending nor decided")

    def test_a_refused_key_fails_at_once(self):
        apple = FakeApple(401)
        code, printed, _, _ = self.wait(apple, budget=60)
        self.assertEqual(1, code)
        self.assertEqual(1, len(apple.asked))
        self.assertIn("refused the key", printed)

    def test_a_longer_budget_keeps_asking_then_slows_down(self):
        apple = FakeApple(*["In Progress"] * 45, 503, "Accepted", log=self.good_log())
        code, _, clock, output = self.wait(apple, budget=345)
        self.assertEqual(0, code)
        self.assertEqual("status=accepted\n", output)
        self.assertEqual(30, clock.pauses[0])
        self.assertEqual(180, clock.pauses[-1])

    def test_a_longer_budget_ends_pending(self):
        code, _, clock, output = self.wait(FakeApple("In Progress"), budget=60)
        self.assertEqual(0, code)
        self.assertEqual("status=pending\n", output)
        self.assertLessEqual(clock.now, 60 * 60)

    # Matching the file

    def test_the_file_apple_checked_is_verified(self):
        code, printed = self.verify(FakeApple("Accepted", log=self.good_log()))
        self.assertEqual(0, code)
        self.assertIn("exact SHA-256", printed)

    def test_a_different_file_is_refused(self):
        code, printed = self.verify(FakeApple("Accepted", log=dict(self.good_log(), sha256="0" * 64)))
        self.assertEqual(1, code)
        self.assertIn("a different file", printed)

    def test_no_report_means_no_publishing(self):
        code, printed = self.verify(FakeApple("Accepted", logs_fail=True))
        self.assertEqual(1, code)
        self.assertIn("has no SHA-256", printed)

    def test_only_an_accepted_submission_is_verified(self):
        code, _ = self.verify(FakeApple("In Progress", log=self.good_log()))
        self.assertEqual(1, code)
        code, _ = self.verify(FakeApple("Accepted", name="Other.dmg", log=self.good_log()))
        self.assertEqual(1, code)


if __name__ == "__main__":
    unittest.main()
