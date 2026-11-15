"""Check upstream's manifest against its signature under upstream's release key, then the archive against the manifest.
  python3 verify_upstream.py DIR KEY.pub PLATFORM   (DIR holds manifest.json, .sig, .keyid and the archive)"""
import hashlib, json, os, subprocess, sys
d, pub, plat = sys.argv[1:4]
subprocess.run([os.environ.get("OPENSSL", "openssl"), "pkeyutl", "-verify", "-pubin", "-inkey", pub, "-rawin", "-in", f"{d}/manifest.json", "-sigfile", f"{d}/manifest.json.sig"], check=True)
m = json.load(open(f"{d}/manifest.json")); a = m["assets"][plat]; f = f"{d}/{a['name']}"
if os.path.getsize(f) != a["size"] or hashlib.sha256(open(f, "rb").read()).hexdigest() != a["sha256"]: sys.exit(f"{f}: not what upstream's manifest says")
print(f"upstream {m['tag']} {m['commit'][:9]}: signature and {plat} archive check out")
