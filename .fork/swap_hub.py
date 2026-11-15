"""Upstream's native archive with the fork's backplane-hub (and COMMIT) in place of upstream's; every other member copied as it is.
  python3 swap_hub.py UPSTREAM.tar.gz HUB COMMIT_SHA OUT.tar.gz"""
import io, sys, tarfile
src, hub, sha, out = sys.argv[1:5]
data = open(hub, "rb").read(); commit = (sha + "\n").encode()
seen = set()
with tarfile.open(src, "r:gz") as i, tarfile.open(out, "w:gz", format=tarfile.PAX_FORMAT) as o:
    for m in i:
        if not (m.isfile() or m.isdir()): sys.exit(f"{m.name}: not a plain file or folder")
        base = m.name.rstrip("/").rsplit("/", 1)[-1]
        if m.isfile() and base in ("backplane-hub", "COMMIT") and m.name.count("/") <= 1:
            body = data if base == "backplane-hub" else commit
            n = tarfile.TarInfo(m.name); n.mode, n.mtime, n.uid, n.gid, n.uname, n.gname = m.mode, m.mtime, 0, 0, "", ""; n.size = len(body)
            o.addfile(n, io.BytesIO(body)); seen.add(base); continue
        o.addfile(m, i.extractfile(m) if m.isfile() else None)
if seen != {"backplane-hub", "COMMIT"}: sys.exit(f"upstream's archive lacks {({'backplane-hub', 'COMMIT'} - seen)}")
print(f"{out}: hub {len(data)} bytes, COMMIT {sha}")
