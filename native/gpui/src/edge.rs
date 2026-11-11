//! Fork (cf-access, docs/FORK-CF-ACCESS.md): headers an edge proxy in front of a hub asks for, such as a Cloudflare Access service token
//! (CF-Access-Client-Id, CF-Access-Client-Secret). Read once at start from `edge-headers.json` in this client's settings folder
//! (`BACKPLANE_GPUI_EDGE` names another file), which is made private to this user as the hub token is (plat::make_private):
//!
//! `{"backplane.example.net": {"CF-Access-Client-Id": "…", "CF-Access-Client-Secret": "…"}}` (a key without a port is :443)
//!
//! They go only over TLS, only to that host and port: the hub socket (hub.rs) and the direct requests (pairing, attach, images, the hub bar,
//! the browser's frames), none of which follows a redirect. Values are never logged.
use serde_json::Value;
use std::{collections::HashMap, path::PathBuf, sync::{OnceLock, RwLock}};

type Table = HashMap<String, Vec<(String, String)>>;

/* i18n-ok: header names */ pub const CF_ID: &str = "CF-Access-Client-Id";
/* i18n-ok: header names */ pub const CF_SECRET: &str = "CF-Access-Client-Secret";

fn path() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("BACKPLANE_GPUI_EDGE").filter(|p| !p.is_empty()) { return Some(p.into()) }
    if let Some(p) = std::env::var_os("BACKPLANE_GPUI_CONFIG").filter(|p| !p.is_empty()) { return Some(PathBuf::from(p).join("edge-headers.json")) }
    Some(crate::plat::config_base()?.join("backplane-gpui/edge-headers.json"))
}
fn table() -> &'static RwLock<Table> {
    static T: OnceLock<RwLock<Table>> = OnceLock::new();
    T.get_or_init(|| RwLock::new({
        let Some(p) = path() else { return RwLock::new(Table::new()) };
        let Ok(text) = std::fs::read_to_string(&p) else { return RwLock::new(Table::new()) };
        let _ = crate::plat::make_private(&p);
        let t = parse(&text);
        /* i18n-ok: a log line */ if t.is_empty() { eprintln!("GPUI_LOG {{\"edge\":\"edge-headers.json holds no valid entry\"}}") }
        t
    }))
}
/// The pairing form's rows for the hub at `origin` (an https address): a blank value keeps the one saved under that name, a row with no name
/// is skipped; no row left removes the hub's entry. Written to the file (private to this user) and used at once. Err: what to say.
pub fn save(origin: &str, rows: &[(String, String)]) -> Result<(), &'static str> {
    let (addr, tls) = crate::pairing::host_port(origin.trim_end_matches('/'));
    /* i18n-ok: English, drawn through trs by the form */ if !tls { return Err("Edge proxy headers are only sent to an https address.") }
    let k = key(&addr);
    let mut t = table().write().map_err(|_| "x")?;
    let old: HashMap<String, String> = t.get(&k).cloned().unwrap_or_default().into_iter().map(|(n, v)| (n.to_ascii_lowercase(), v)).collect();
    let mut next: Vec<(String, String)> = vec![];
    for (n, v) in rows.iter().map(|(n, v)| (n.trim().to_string(), v.trim().to_string())).filter(|(n, _)| !n.is_empty()) {
        let v = if v.is_empty() { old.get(&n.to_ascii_lowercase()).cloned().unwrap_or_default() } else { v };
        /* i18n-ok: English, drawn through trs by the form */ if !token(&n) || !value(&v) { return Err("A header name or value is not valid.") }
        if !next.iter().any(|(m, _)| m.eq_ignore_ascii_case(&n)) { next.push((n, v)) }
    }
    let mut new = t.clone(); if next.is_empty() { new.remove(&k); } else { new.insert(k, next); }
    /* i18n-ok: English, drawn through trs by the form */ let p = path().ok_or("No folder for this device's settings.")?;
    let json = serde_json::Value::Object(new.iter().map(|(h, hs)| (h.clone(), serde_json::Value::Object(hs.iter().map(|(n, v)| (n.clone(), v.clone().into())).collect()))).collect());
    let write = || -> std::io::Result<()> {
        if let Some(d) = p.parent() { std::fs::create_dir_all(d)?; }
        let mut o = std::fs::OpenOptions::new(); o.create(true).write(true).truncate(true);
        #[cfg(unix)] { use std::os::unix::fs::OpenOptionsExt; o.mode(0o600); }
        std::io::Write::write_all(&mut o.open(&p)?, serde_json::to_string_pretty(&json)?.as_bytes())?; crate::plat::make_private(&p)
    };
    /* i18n-ok: English, drawn through trs by the form */ write().map_err(|_| "The headers could not be saved here.")?;
    *t = new; Ok(())
}
/// The header names saved for the hub at `origin` (never the values).
pub fn names(origin: &str) -> Vec<String> { let (addr, tls) = crate::pairing::host_port(origin.trim_end_matches('/')); headers(&addr, tls).into_iter().map(|(n, _)| n).collect() }

/// `host:port`, lower case, with 443 when it names none.
fn key(s: &str) -> String {
    let s = s.trim().to_ascii_lowercase();
    let named = s.rsplit_once(':').map_or(false, |(_, p)| !p.is_empty() && p.bytes().all(|c| c.is_ascii_digit()));
    if named { s } else { format!("{s}:443") }
}
fn token(name: &str) -> bool { !name.is_empty() && name.bytes().all(|c| c.is_ascii_alphanumeric() || b"!#$%&'*+.^_`|~-".contains(&c)) }
fn value(v: &str) -> bool { !v.is_empty() && v.bytes().all(|c| (0x20..0x7f).contains(&c)) }

/// The file's entries; a malformed name or value drops its header, never sends it.
pub fn parse(text: &str) -> Table {
    let Ok(Value::Object(hubs)) = serde_json::from_str::<Value>(text) else { return Table::new() };
    hubs.iter().filter_map(|(hub, hs)| {
        let hs: Vec<(String, String)> = hs.as_object()?.iter().filter_map(|(n, v)| Some((n.clone(), v.as_str()?.to_string()))).filter(|(n, v)| token(n) && value(v)).collect();
        (!hs.is_empty()).then(|| (key(hub), hs))
    }).collect()
}

/// The headers for `addr` (host:port), only when the connection is TLS.
pub fn headers_in(t: &Table, addr: &str, tls: bool) -> Vec<(String, String)> { if tls { t.get(&key(addr)).cloned().unwrap_or_default() } else { vec![] } }
pub fn headers(addr: &str, tls: bool) -> Vec<(String, String)> { table().read().map(|t| headers_in(&t, addr, tls)).unwrap_or_default() }
/// The same as header lines (`Name: value\r\n` each) for a hand-written request head.
/* i18n-ok: HTTP header lines */ pub fn lines(addr: &str, tls: bool) -> String { headers(addr, tls).iter().map(|(n, v)| format!("{n}: {v}\r\n")).collect() }
/// For a hub address (ws, wss, http, https).
pub fn for_url(url: &str) -> Vec<(String, String)> { let (addr, tls) = crate::pairing::host_port(url.split(['?', '#']).next().unwrap_or(url).trim_end_matches("/ws")); headers(&addr, tls) }

#[cfg(test)]
mod tests {
    use super::*;
    const F: &str = r#"{"Backplane.Example.net": {"CF-Access-Client-Id": "id.access", "CF-Access-Client-Secret": "s3cret", "Bad Name": "x", "X-Ctl": "a\r\nInjected: 1"},
                        "other.example.net:8443": {"X-Key": "k"}, "empty.example.net": {}, "junk": 3}"#;
    #[test] fn only_tls_and_only_that_host_and_port() {
        let t = parse(F);
        let want = vec![("CF-Access-Client-Id".to_string(), "id.access".to_string()), ("CF-Access-Client-Secret".to_string(), "s3cret".to_string())];
        assert_eq!(headers_in(&t, "backplane.example.net:443", true), want);
        assert_eq!(headers_in(&t, "BACKPLANE.example.net", true), want, "no port is 443, case is ignored");
        assert!(headers_in(&t, "backplane.example.net:443", false).is_empty(), "never over plain TCP");
        assert!(headers_in(&t, "backplane.example.net:8443", true).is_empty(), "another port");
        assert!(headers_in(&t, "evil.example.net:443", true).is_empty() && headers_in(&t, "example.net:443", true).is_empty(), "another host");
        assert_eq!(headers_in(&t, "other.example.net:8443", true), vec![("X-Key".to_string(), "k".to_string())]);
    }
    #[test] fn the_form_saves_keeps_a_blank_value_and_refuses_http() {
        let dir = std::env::temp_dir().join(format!("edge-test-{}", std::process::id())); let f = dir.join("edge-headers.json");
        unsafe { std::env::set_var("BACKPLANE_GPUI_EDGE", &f); }
        let r = |a: &str, b: &str| (a.to_string(), b.to_string());
        assert!(save("http://h.example.net", &[r("X-A", "1")]).is_err(), "never for plain http");
        assert!(save("https://h.example.net", &[r("Bad Name", "1")]).is_err());
        save("https://h.example.net", &[r("CF-Access-Client-Id", "id.access"), r("CF-Access-Client-Secret", "s1")]).unwrap();
        save("https://h.example.net/", &[r("CF-Access-Client-Id", ""), r("CF-Access-Client-Secret", "s2"), r("", "ignored")]).unwrap();
        assert_eq!(headers("h.example.net:443", true), vec![r("CF-Access-Client-Id", "id.access"), r("CF-Access-Client-Secret", "s2")]);
        assert_eq!(names("https://h.example.net"), vec!["CF-Access-Client-Id", "CF-Access-Client-Secret"]);
        assert_eq!(parse(&std::fs::read_to_string(&f).unwrap()).get("h.example.net:443").map(|v| v.len()), Some(2), "on disk too");
        save("https://h.example.net", &[]).unwrap(); assert!(names("https://h.example.net").is_empty(), "no row removes it");
        let _ = std::fs::remove_dir_all(&dir);
    }
    #[test] fn a_bad_name_or_value_is_dropped_never_sent() {
        let t = parse(F);
        let names: Vec<_> = headers_in(&t, "backplane.example.net:443", true).into_iter().map(|(n, _)| n).collect();
        assert!(!names.iter().any(|n| n == "Bad Name" || n == "X-Ctl"), "a space in a name, a CR LF in a value");
        assert!(!t.contains_key("empty.example.net:443") && !t.contains_key("junk:443"));
        assert!(parse("not json").is_empty() && parse("[]").is_empty());
    }
}
