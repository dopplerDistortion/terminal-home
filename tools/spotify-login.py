#!/usr/bin/env python3
"""One-time Spotify sign-in for the personal trending feed (Authorization Code with PKCE).

Opens Spotify's consent page in the default browser, catches the redirect on http://127.0.0.1:8888/callback
(register that exact URI in the Spotify app), and writes SPOTIFY_REFRESH_TOKEN into keys.properties. Needs
SPOTIFY_CLIENT_ID in keys.properties. Prints no token or secret.

    /usr/bin/python3 tools/spotify-login.py   # macOS: the system Python has the CA certificates
"""
import base64
import hashlib
import http.server
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import webbrowser

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KEYS = os.path.join(ROOT, "keys.properties")
REDIRECT = "http://127.0.0.1:8888/callback"
SCOPES = "user-top-read user-read-recently-played user-follow-read user-library-read"
WAIT_S = 300


def read_keys():
    keys = {}
    if os.path.exists(KEYS):
        for line in open(KEYS, encoding="utf-8"):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.split("=", 1)
                keys[k.strip()] = v.strip()
    return keys


def write_key(name, value):
    lines = open(KEYS, encoding="utf-8").read().splitlines() if os.path.exists(KEYS) else []
    out, done = [], False
    for line in lines:
        if line.split("=", 1)[0].strip() == name:
            out.append(f"{name}={value}")
            done = True
        else:
            out.append(line)
    if not done:
        out.append(f"{name}={value}")
    with open(KEYS, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


def main():
    client_id = read_keys().get("SPOTIFY_CLIENT_ID", "")
    if not client_id:
        sys.exit("SPOTIFY_CLIENT_ID is empty in keys.properties")
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(64)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    url = "https://accounts.spotify.com/authorize?" + urllib.parse.urlencode({
        "client_id": client_id, "response_type": "code", "redirect_uri": REDIRECT, "scope": SCOPES,
        "code_challenge_method": "S256", "code_challenge": challenge, "state": state})

    result = {}

    class Callback(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if not self.path.startswith("/callback"):
                self.send_response(404)
                self.end_headers()
                return
            if q.get("state", [""])[0] != state:
                result["error"] = "state mismatch"
            elif "error" in q:
                result["error"] = q["error"][0]
            else:
                result["code"] = q.get("code", [""])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(("Spotify sign-in done. You can close this tab." if "code" in result
                              else "Spotify sign-in failed: " + result.get("error", "")).encode())

        def log_message(self, *args):
            pass   # the query has the code in it

    server = http.server.HTTPServer(("127.0.0.1", 8888), Callback)
    server.timeout = WAIT_S
    # The URL has the client id and the PKCE challenge only, no secret: safe to show and click by hand.
    print("Opening Spotify sign-in in your browser. Click Agree / Allow. If no tab opens, go to:\n" + url, flush=True)
    webbrowser.open(url)
    while not result:
        server.handle_request()
        if not result:
            sys.exit(f"No sign-in within {WAIT_S} s")
    server.server_close()
    if "error" in result:
        sys.exit("Sign-in failed: " + result["error"])

    body = urllib.parse.urlencode({"grant_type": "authorization_code", "code": result["code"],
                                   "redirect_uri": REDIRECT, "client_id": client_id,
                                   "code_verifier": verifier}).encode()
    req = urllib.request.Request("https://accounts.spotify.com/api/token", data=body,
                                 headers={"Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            token = json.load(r)
    except urllib.error.URLError as e:
        if "CERTIFICATE_VERIFY_FAILED" in str(e):
            sys.exit("This Python has no CA certificates (python.org builds on macOS); run it with /usr/bin/python3")
        raise
    if not token.get("refresh_token"):
        sys.exit("Spotify sent no refresh token")
    write_key("SPOTIFY_REFRESH_TOKEN", token["refresh_token"])
    print("Wrote SPOTIFY_REFRESH_TOKEN to keys.properties (scopes: %s)." % token.get("scope", ""))
    # Optional: the short-lived access token, for checking the endpoints by hand. Never in the repo.
    out = os.environ.get("SPOTIFY_ACCESS_TOKEN_FILE")
    if out:
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(token["access_token"])


if __name__ == "__main__":
    main()
