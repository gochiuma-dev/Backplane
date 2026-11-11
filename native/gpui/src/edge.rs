//! Fork (cf-access, docs/FORK-CF-ACCESS.md): headers an edge proxy in front of a hub asks for, such as a Cloudflare Access service token
//! (CF-Access-Client-Id, CF-Access-Client-Secret). Read once at start from `edge-headers.json` in this client's settings folder
//! (`BACKPLANE_GPUI_EDGE` names another file), which is made private to this user as the hub token is (plat::make_private):
//!
//! `{"backplane.example.net": {"CF-Access-Client-Id": "…", "CF-Access-Client-Secret": "…"}}` (a key without a port is :443)
//!
//! They go only over TLS, only to that host and port: the hub socket (hub.rs) and the direct requests (pairing, attach, images, the hub bar,
//! the browser's frames), none of which follows a redirect. Values are never logged.
use serde_json::Value;
use std::{collections::HashMap, path::PathBuf, sync::OnceLock};

type Table = HashMap<String, Vec<(String, String)>>;

fn path() -> Option<PathBuf> {
    if let Some(p) = std::env::var_os("BACKPLANE_GPUI_EDGE").filter(|p| !p.is_empty()) { return Some(p.into()) }
    if let Some(p) = std::env::var_os("BACKPLANE_GPUI_CONFIG").filter(|p| !p.is_empty()) { return Some(PathBuf::from(p).join("edge-headers.json")) }
    Some(crate::plat::config_base()?.join("backplane-gpui/edge-headers.json"))
}
fn table() -> &'static Table {
    static T: OnceLock<Table> = OnceLock::new();
    T.get_or_init(|| {
        let Some(p) = path() else { return Table::new() };
        let Ok(text) = std::fs::read_to_string(&p) else { return Table::new() };
        let _ = crate::plat::make_private(&p);
        let t = parse(&text);
        /* i18n-ok: a log line */ if t.is_empty() { eprintln!("GPUI_LOG {{\"edge\":\"edge-headers.json holds no valid entry\"}}") }
        t
    })
}

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
pub fn headers(addr: &str, tls: bool) -> Vec<(String, String)> { headers_in(table(), addr, tls) }
/// The same as header lines (`Name: value\r\n` each) for a hand-written request head.
pub fn lines(addr: &str, tls: bool) -> String { headers(addr, tls).iter().map(|(n, v)| format!("{n}: {v}\r\n")).collect() }
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
    #[test] fn a_bad_name_or_value_is_dropped_never_sent() {
        let t = parse(F);
        let names: Vec<_> = headers_in(&t, "backplane.example.net:443", true).into_iter().map(|(n, _)| n).collect();
        assert!(!names.iter().any(|n| n == "Bad Name" || n == "X-Ctl"), "a space in a name, a CR LF in a value");
        assert!(!t.contains_key("empty.example.net:443") && !t.contains_key("junk:443"));
        assert!(parse("not json").is_empty() && parse("[]").is_empty());
    }
}
