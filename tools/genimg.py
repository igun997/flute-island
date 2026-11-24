#!/usr/bin/env python3
"""Generate an image via the OpenAI-compatible endpoint configured in an env file (URL, KEY).

Usage:
  python3 tools/genimg.py OUT.png "prompt" [--model cx/gpt-6.1-sol] [--size 1024x1024] [--n 1]
  python3 tools/genimg.py OUT.png "prompt" --ref REF.png   # image edit using REF as the base

The env file is FLUTE_ENV (default: /home/nst/FlutterProjects/kereta-api/.env).
Secrets are read from that file and never printed.
"""
import argparse, base64, json, os, pathlib, sys, urllib.request, uuid

DEFAULT_ENV = "/home/nst/FlutterProjects/kereta-api/.env"


def load_env():
    path = pathlib.Path(os.environ.get("FLUTE_ENV", DEFAULT_ENV)).expanduser()
    env = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def multipart(fields, file_field, file_path):
    boundary = uuid.uuid4().hex
    body = bytearray()
    for k, v in fields.items():
        body += f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
    name = pathlib.Path(file_path).name
    body += (f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{name}"\r\n'
             f"Content-Type: image/png\r\n\r\n").encode()
    body += pathlib.Path(file_path).read_bytes()
    body += f"\r\n--{boundary}--\r\n".encode()
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("prompt")
    ap.add_argument("--model", default="cx/gpt-6.1-sol")
    ap.add_argument("--size", default="1024x1024")
    ap.add_argument("--n", type=int, default=1)
    ap.add_argument("--ref", help="reference PNG; uses the /images/edits endpoint")
    a = ap.parse_args()

    env = load_env()
    base = env["URL"].rstrip("/")
    if not base.endswith("/v1"):
        base += "/v1"
    headers = {"Authorization": "Bearer " + env["KEY"], "User-Agent": "curl/8.5.0"}
    fields = {"model": a.model, "prompt": a.prompt, "size": a.size, "n": str(a.n)}
    if a.ref:
        body, ctype = multipart(fields, "image", a.ref)
        url = base + "/images/edits"
    else:
        body = json.dumps(fields).encode()
        ctype = "application/json"
        url = base + "/images/generations"
    req = urllib.request.Request(url, data=body, headers={**headers, "Content-Type": ctype})
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.load(r)["data"]
    out = pathlib.Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    for i, d in enumerate(data):
        p = out if len(data) == 1 else out.with_name(f"{out.stem}_{i}{out.suffix}")
        p.write_bytes(base64.b64decode(d["b64_json"]))
        print("wrote", p)


if __name__ == "__main__":
    sys.exit(main())
