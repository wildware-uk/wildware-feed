#!/usr/bin/env python3
"""Small App Store Connect API helper for .github/workflows/ios-release.yml.

Standard library only, plus the `openssl` command for the ES256 signature, so it runs on the
Linux and Mac runners without installing anything.

Reads the key from ASC_KEY_ID, ASC_ISSUER_ID and ASC_KEY_PATH (the .p8 file).

  asc.py check BUNDLE_ID [--require-app]        the key works; the app exists in App Store Connect
  asc.py notes BUNDLE_ID VERSION BUILD FILE     sets the TestFlight "What to Test" text
  asc.py cert-ids                               lists every certificate id (to prove none changed)
  asc.py create-cert CSR OUT                    makes one development certificate (run once, by hand)
  asc.py ensure-ids "ID=Name=CAP,CAP" ...       registers missing bundle ids and turns capabilities on
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.appstoreconnect.apple.com"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def der_to_raw(der):
    """openssl gives an ECDSA signature as DER; a JWT wants r and s as two 32-byte numbers."""
    if der[0] != 0x30:
        raise ValueError("not a DER signature")
    i = 3 if der[1] & 0x80 else 2
    parts = []
    for _ in range(2):
        if der[i] != 0x02:
            raise ValueError("not a DER integer")
        length = der[i + 1]
        value = der[i + 2:i + 2 + length].lstrip(b"\0")
        parts.append(value.rjust(32, b"\0"))
        i += 2 + length
    return b"".join(parts)


def token():
    key_id = os.environ["ASC_KEY_ID"]
    issuer = os.environ["ASC_ISSUER_ID"]
    key_path = os.environ["ASC_KEY_PATH"]
    now = int(time.time())
    header = b64url(json.dumps({"alg": "ES256", "kid": key_id, "typ": "JWT"}).encode())
    # Apple refuses tokens that live longer than 20 minutes.
    payload = b64url(json.dumps({"iss": issuer, "iat": now, "exp": now + 900, "aud": "appstoreconnect-v1"}).encode())
    signing_input = f"{header}.{payload}".encode()
    result = subprocess.run(["openssl", "dgst", "-sha256", "-sign", key_path], input=signing_input, capture_output=True)
    if result.returncode != 0:
        fail("Couldn't sign with the App Store Connect key. Is ASC_KEY_P8_BASE64 the base64 of the whole .p8 file?")
    return f"{header}.{payload}.{b64url(der_to_raw(result.stdout))}"


def call(method, path, body=None, query=None):
    url = path if path.startswith("http") else API + path
    if query:
        url += "?" + urllib.parse.urlencode(query)
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("Authorization", f"Bearer {token()}")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        details = error.read().decode(errors="replace")
        try:
            details = "; ".join(e.get("detail") or e.get("title", "") for e in json.loads(details)["errors"])
        except (ValueError, KeyError, TypeError):
            pass
        if error.code == 401:
            fail("App Store Connect refused the key (401). Check that ASC_KEY_ID, ASC_ISSUER_ID and "
                 "ASC_KEY_P8_BASE64 all come from the same key, and that the key hasn't been revoked.")
        if error.code == 403:
            fail(f"App Store Connect says the key may not do this (403): {details}. The key needs the Admin role.")
        fail(f"App Store Connect {method} {path} failed ({error.code}): {details}")


def find_app(bundle_id):
    apps = call("GET", "/v1/apps", query={"filter[bundleId]": bundle_id, "fields[apps]": "bundleId,name"})["data"]
    # filter[bundleId] also matches longer ids that start the same, so check it exactly.
    return next((app for app in apps if app["attributes"]["bundleId"] == bundle_id), None)


def check(args):
    app = find_app(args.bundle_id)
    if app:
        print(f"The key works, and App Store Connect has {args.bundle_id} as \"{app['attributes']['name']}\".")
    elif args.require_app:
        fail(f"The key works, but App Store Connect has no app for {args.bundle_id}. Create it once in "
             "App Store Connect (Apps, +, New App, bundle id dev.wildware.agents), then run this again.")
    else:
        print(f"::warning::The key works, but App Store Connect has no app for {args.bundle_id} yet. "
              "Building is fine; uploading needs it.")


def notes(args):
    with open(args.notes_file, encoding="utf-8") as f:
        text = f.read().strip()
    if not text:
        print("No notes given; leaving What to Test alone.")
        return
    app = find_app(args.bundle_id) or fail(f"No app for {args.bundle_id} in App Store Connect.")
    deadline = time.time() + args.wait_minutes * 60
    build = None
    # A build shows up in the API a few minutes after the upload, while Apple still processes it.
    while True:
        found = call("GET", "/v1/builds", query={
            "filter[app]": app["id"],
            "filter[version]": args.build,
            "filter[preReleaseVersion.version]": args.version,
            "limit": "1",
        })["data"]
        if found:
            build = found[0]
            break
        if time.time() > deadline:
            fail(f"Build {args.version} ({args.build}) didn't show up in App Store Connect within "
                 f"{args.wait_minutes} minutes. It may still be processing; set What to Test by hand.")
        print(f"Waiting for build {args.version} ({args.build}) to show up…")
        time.sleep(30)

    localizations = call("GET", f"/v1/builds/{build['id']}/betaBuildLocalizations")["data"]
    existing = next((l for l in localizations if l["attributes"].get("locale") == "en-US"), None)
    existing = existing or (localizations[0] if localizations else None)
    if existing:
        call("PATCH", f"/v1/betaBuildLocalizations/{existing['id']}", body={"data": {
            "type": "betaBuildLocalizations", "id": existing["id"], "attributes": {"whatsNew": text}}})
    else:
        call("POST", "/v1/betaBuildLocalizations", body={"data": {
            "type": "betaBuildLocalizations",
            "attributes": {"locale": "en-US", "whatsNew": text},
            "relationships": {"build": {"data": {"type": "builds", "id": build["id"]}}}}})
    print(f"Set What to Test on build {args.version} ({args.build}).")


def cert_ids(args):
    """Prints every certificate id in the account, one per line, sorted, so a run can prove it
    neither made nor revoked one."""
    ids, url, query = [], "/v1/certificates", {"limit": "200", "fields[certificates]": "name"}
    while url:
        page = call("GET", url, query=query)
        ids += [cert["id"] for cert in page["data"]]
        url = page.get("links", {}).get("next")
        query = None
    print("\n".join(sorted(ids)))


def create_cert(args):
    """Asks Apple for one Apple Development certificate for this CSR and writes it to OUT (DER).
    Run once, by hand, to make the long-lived signing certificate; the release never calls it."""
    csr = open(args.csr).read()
    body = "".join(l for l in csr.splitlines() if "CERTIFICATE REQUEST" not in l)
    made = call("POST", "/v1/certificates", body={"data": {"type": "certificates", "attributes": {
        "certificateType": "DEVELOPMENT", "csrContent": body}}})["data"]
    open(args.out, "wb").write(base64.b64decode(made["attributes"]["certificateContent"]))
    print(f"Made {made['attributes'].get('name')} (id {made['id']}), "
          f"expires {made['attributes'].get('expirationDate')}.")


def ensure_ids(args):
    """Registers each bundle id if it's missing and turns on the capabilities it lists. Safe to run
    again: it only adds what isn't there, and never renames or removes anything."""
    for spec in args.specs:
        try:
            identifier, name, caps = spec.split("=")
        except ValueError:
            fail(f"Bad spec '{spec}': expected ID=Name=CAPABILITY,CAPABILITY.")
        wanted = [c for c in caps.split(",") if c]
        found = call("GET", "/v1/bundleIds", query={
            "filter[identifier]": identifier, "fields[bundleIds]": "identifier,name,platform", "limit": "200"})["data"]
        # filter[identifier] also matches ids that only start the same, so check it exactly.
        bundle = next((b for b in found if b["attributes"]["identifier"] == identifier), None)
        if bundle:
            print(f"{identifier}: registered as \"{bundle['attributes']['name']}\".")
        else:
            bundle = call("POST", "/v1/bundleIds", body={"data": {
                "type": "bundleIds",
                "attributes": {"identifier": identifier, "name": name, "platform": "IOS"}}})["data"]
            print(f"{identifier}: registered it as \"{name}\".")
        have = {c["attributes"]["capabilityType"]
                for c in call("GET", f"/v1/bundleIds/{bundle['id']}/bundleIdCapabilities")["data"]}
        for capability in wanted:
            if capability in have:
                continue
            call("POST", "/v1/bundleIdCapabilities", body={"data": {
                "type": "bundleIdCapabilities",
                "attributes": {"capabilityType": capability},
                "relationships": {"bundleId": {"data": {"type": "bundleIds", "id": bundle["id"]}}}}})
            print(f"{identifier}: turned on {capability}.")
        print(f"{identifier}: has {', '.join(sorted(have | set(wanted))) or 'no capabilities'}.")


def main():
    for name in ("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_PATH"):
        if not os.environ.get(name):
            fail(f"{name} is not set.")
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    p = commands.add_parser("check")
    p.add_argument("bundle_id")
    p.add_argument("--require-app", action="store_true")
    p.set_defaults(run=check)
    p = commands.add_parser("notes")
    p.add_argument("bundle_id")
    p.add_argument("version")
    p.add_argument("build")
    p.add_argument("notes_file")
    p.add_argument("--wait-minutes", type=int, default=20)
    p.set_defaults(run=notes)
    p = commands.add_parser("cert-ids")
    p.set_defaults(run=cert_ids)
    p = commands.add_parser("create-cert")
    p.add_argument("csr")
    p.add_argument("out")
    p.set_defaults(run=create_cert)
    p = commands.add_parser("ensure-ids")
    p.add_argument("specs", nargs="+")
    p.set_defaults(run=ensure_ids)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
