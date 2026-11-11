# Fork: edge proxy headers on Android and the desktop client (cf-access)

A fork of Backplane (MIT, github.com/i2cjak/Backplane) whose only change is letting the Android app and the desktop client (Windows, Linux, macOS) send extra headers
to a hub behind an access proxy, such as Cloudflare Access with a service token (`CF-Access-Client-Id`, `CF-Access-Client-Secret`).
The native app cannot pass Access's browser login, so it sends the token instead. Backplane's own `Authorization: Bearer` is untouched.

Base: **v0.15.2** (`2418d685eb91701827afead15ab08586c47db644`), the hub's version (data/1 must match). Branch `cf-access`.

## The rule

- Headers are kept **per hub address** (`host:port` of an https link) and sent **only over https to that same host and port**.
- For HTTP they are added by an OkHttp **network** interceptor, so the check runs again for every redirect; a redirect to another
  host, and a picture from another site in a message (DataMarkdown), never carries them. Plain http (LAN, Tailscale) gets nothing.
- OkHttp runs no network interceptor for a **WebSocket upgrade**, so an application interceptor adds them to upgrade requests only,
  and every client that opens a socket follows **no redirect** (`DataClient.wsHttp`, `Hub`): an upgrade bounced elsewhere stops there.
- `VoiceRecorder`'s `HttpURLConnection` uses the same check and stops following redirects when it sends them.
- Value fields use a password keyboard (no suggestions, nothing learned by the keyboard).
- Values are **write-only** in the UI and stored sealed with AES-GCM under an Android Keystore key (`backplane.edge`, prefs `edge`).
  Forgetting a hub removes its headers; Unpair removes all.
- Opening a hub URL in the system browser (Intent.ACTION_VIEW) gets no headers: the browser passes Access's own login.

## Where to enter them

- **Pairing screen** → "EDGE PROXY HEADERS": typed rows go to the hub being paired, so `/auth/enroll` already carries them.
  For the short code, type the address with `https://` (a bare host becomes http, and nothing is sent).
- **Settings → Hubs → Headers** for an already paired hub. A blank value keeps the saved one. Saving reconnects the hub in use.
- The "Cloudflare Access" button fills in the two header names.

## The patch

New files: `EdgeHeaders.kt` (store, rule, interceptor), `EdgeHeadersView.kt` (rows, pairing section, dialog), this file.
Hooks (one line each, `.apply(EdgeHeaders::wire)` on a client builder, or a call):

| File | Change |
|---|---|
| `DataClient.kt` | `wire` on `http`; `wsHttp` (no redirects) for the socket; `EdgeHeaders.init`; `commitPending` at the top of `pair()`; forget on `forgetHub` / `unpair` |
| `Hub.kt` | `wire` on `/auth/enroll`'s client and on `Hub`'s socket client (which now follows no redirect) |
| `HubRescue.kt` | interceptor on `HubHttp` (status, control, health) |
| `DataMarkdown.kt` | interceptor on `MdPics` (only the hub's own https address gets them) |
| `VoiceRecorder.kt` | `EdgeHeaders.apply(c)` |
| `DataViews.kt` | `EdgePairSection` on the pairing screen; a Headers button and `EdgeHubDialog` in Settings → Hubs |
| `app/build.gradle.kts` | test deps `mockwebserver`, `okhttp-tls` for `EdgeHeadersTest` |
| `i18n/en.msg`, `ja.msg` | a `fork (cf-access)` section before `# ---- end ----`; `Msg.kt` regenerated |

## Tests

`EdgeHeadersTest` (JVM, real OkHttp over TLS): the hub gets them on HTTP and on the socket upgrade; another port or host, a redirect
elsewhere, plain http get none; a socket redirect is not followed; a blank value keeps the saved one. Run the unit tests with an
English JVM locale (`JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US"`): three upstream tests fail under a Japanese one.

Checked on a device with a build trusting a test CA: pairing by short code against an https test server sent both headers on
`POST /auth/enroll` and on the `/ws` upgrade; the upgrade's 302 to another port was not followed (that port saw nothing);
`shared_prefs/edge.xml` held only the sealed blob, no value in clear.

## Desktop client (native/gpui)

The same rule in `native/gpui/src/edge.rs`: only over TLS, only to that host and port, on the hub socket's upgrade (`hub.rs`) and on every
hand-written request to the hub (pairing's `/auth/enroll`, attachments, images, the hub bar, the browser's frames). None of them follows a
redirect. They are entered on the **pairing form** ("Edge proxy headers": two rows named for a Cloudflare Access service token,
the values shown as `*` and never copied out; a blank value keeps the saved one) and saved when Pair is pressed, before `/auth/enroll`,
for the https address being paired. To change them for a paired hub, pair it again (Settings → Hubs → Add a hub). They live in
**`edge-headers.json`** in the client's settings folder, which can also be written by hand
(Windows `%APPDATA%\backplane-gpui\`, Linux `~/.config/backplane-gpui/`; `BACKPLANE_GPUI_EDGE` names another file):

```json
{"backplane.example.net": {"CF-Access-Client-Id": "….access", "CF-Access-Client-Secret": "…"}}
```

A key without a port is `:443`. The client makes the file private to the user (on Windows an ACL for the user only, as for the hub token)
when it reads or writes it; it never logs the values. A malformed name or value drops that header. Restart the client after editing it by hand.
Pair with the address written `https://…` (a bare host is http and gets nothing). Dictation's upload from this machine
(`voice_mic.rs`) speaks plain TCP only and does not reach an https hub, with or without this fork.

Hooks: `screens/pair.rs` (the form's section, saved in `pair()`), `input.rs` (`secret()`: a field drawn as `*`, no copy or cut), `main.rs` (`mod edge`), `hub.rs` (the upgrade request), `pairing.rs` (`enroll_request`), `screens/attach.rs` (`head`),
`screens/hubbar.rs` (`call`), `screens/images.rs` (`request`), `viewers/browser.rs` (`get_bytes`): one line each.

`.github/workflows/fork-desktop.yml` runs the client's tests on Linux, then builds `backplane-gpui.exe` for Windows as the release does
(`--features cef`, the version stamped to the upstream one). To use it, quit Backplane and replace `backplane-gpui.exe` in the install
of the **same upstream version** (`%LOCALAPPDATA%\Backplane\builds\<version>\`); the CEF runtime beside it stays. An update of
Backplane puts the upstream exe back.

## Rebasing on a new Backplane

```sh
git fetch origin <tag-or-commit>
git rebase --onto <new> 2418d68 cf-access     # conflicts are almost always Msg.kt
python3 scripts/i18n-android.py               # regenerate Msg.kt instead of resolving it by hand
```

Check that every `OkHttpClient` in `mobile/android/app/src/main/java/dev/backplane/mobile/` still has the interceptor:
`grep -n "OkHttpClient\|openConnection" mobile/android/app/src/main/java/dev/backplane/mobile/*.kt`,
and that every request the desktop client writes by hand to a hub still carries `edge::lines`: `grep -rn 'HTTP/1.1' native/gpui/src`.

## Build

```sh
cd mobile/android   # JDK 17 and an Android SDK (JAVA_HOME, ANDROID_HOME)
./gradlew assembleDebug -Pbp.appId=dev.backplane.mobile.gochiuma -Pbp.scheme=bpgochiuma "-Pbp.appName=Backplane CF"
```

It installs beside the official app (its own id, scheme and launcher name).

## GitHub Actions

`.github/workflows/fork-android.yml` runs on a push to `cf-access`, a pull request, a `cf-v*` tag (`cf-v0.15.2-cf.2`) or by hand:
the catalog checks, the unit tests, then an APK as the run's artifact (a tag also makes a release with it).
The upstream workflows stay on and untouched, so rebasing touches no workflow. None of them fires on `cf-access` or a `cf-v*` tag:
`ci.yml` runs on a push to `main` and on pull requests, `release.yml` on a `v*` tag. **Do not push upstream's `v*` tags to the fork**
(`git push fork cf-access`, never `--tags`): each would start a full release build (it publishes nothing without upstream's signing secrets).

With these secrets the APK is a release build signed by the fork's own key, so each one installs over the last; without them it is
a debug APK under a new key each run:

| Secret | |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | the keystore, `base64 < fork.jks` |
| `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | its passwords and alias |

The version is the upstream tag below the branch plus `-cf.ciN` (or the tag's own name), the version code `1000000 + run number`.
