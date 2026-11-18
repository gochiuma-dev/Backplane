# The fork's hub feed (gochiuma-dev/Backplane)

The Mac hub (home-infra `mac/services/backplane-hub`) runs the fork's hub, not upstream's: the fork carries fixes upstream does not have yet
(KiCad's newstroke glyphs for Japanese, FreeCAD meshing with each part's global placement). Upstream's updater would replace it with
upstream's next release, so the hub reads **this fork's releases** instead and still updates itself the usual way (signature, probation, rollback).

- **Branch `hub-fork`**: upstream's release tag (in `.fork/BASE`) plus the fork's commits. Change the fork by committing here.
- **Key**: the hub trusts one key, compiled in: `native/host/src/update/release.pub` / `release.keyid` are the fork's (ed25519, ID
  `e9a1d37ddcb587f5`), so this hub takes only the fork's releases. Private key: Actions secrets `FORK_RELEASE_KEY` and `FORK_RELEASE_KEY_ID`,
  and an offline copy on the Mac at `~/.local/share/backplane-fork-signing/` (never committed). Upstream's key stays in `.fork/upstream-release.pub`
  to check upstream's archive before the fork reuses it.
- **Workflow `fork-release.yml`** (every 6 hours, or by hand): upstream's newest full release T; `hub-fork` rebased onto T when T is new (a conflict
  fails the run and nothing is published); when the commit has no release yet: the hub built from it (`cargo test kicad:: update::` first), put
  into upstream's darwin-arm64 archive of T in place of upstream's `backplane-hub` and `COMMIT` (`.fork/swap_hub.py`), a manifest for
  darwin-arm64 only, signed, published as a prerelease `vX.Y.(Z+1)-nightly.YYYYMMDD.N` with `upstream=` and `commit=` in its notes.
- **Why that tag**: the updater takes only `vX.Y.Z`, `-beta.N` and `-nightly.D.N`, and only something newer than the running hub. That tag is newer
  than upstream's T and than every earlier fork release, and older than upstream's next release. So the hub's channel is **nightly** (from 0.16 nightly ranks above beta, and the manifest says `nightly`, matching its tag).
- **The hub's side**: `BACKPLANE_UPDATE_FEED=https://api.github.com/repos/gochiuma-dev/Backplane/releases` in its launchd job, `update.channel` nightly,
  no `BACKPLANE_NO_UPDATE`. Only the Mac (darwin-arm64) is built.
- Other builds in the archive (client, step2glb, KiCad runtime pin, kistack) are upstream's T as they are.
