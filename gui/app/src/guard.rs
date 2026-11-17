//! Keeps the window's web content from reaching anything outside the app.
//!
//! The content security policy in `tauri.conf.json` decides what the page may *load*. It does
//! not cover everything that can send data out:
//!
//! - **Navigation.** `window.location`, a submitted form, a clicked link or a meta refresh
//!   moves the whole page, and the policy has no say. [`plugin`] refuses every navigation that
//!   leaves the app's own origin.
//! - **WebRTC.** Peer connections send UDP to any address given to them, outside the policy.
//!   The initialisation script below removes the API before any page script runs.
//! - **Speculative connections.** `<link rel="preconnect">`, and the connection the engine
//!   opens when a link is clicked, look up the host name and connect before any check. The
//!   window's `proxyUrl` in `tauri.conf.json` sends all web traffic to a closed local port,
//!   so these go nowhere.
//!
//! Text from a sample is never treated as code, so none of this should ever be needed. It is
//! here so that a mistake elsewhere still cannot turn into data leaving the machine.
//! `tests/egress` attacks all of it from inside the real window.

use tauri::Runtime;
use tauri::plugin::{Builder, TauriPlugin};

/// True for the app's own packaged files: `tauri://localhost` on Linux and macOS,
/// `http(s)://tauri.localhost` on Windows. Neither is a network address.
pub fn is_app_origin(url: &tauri::Url) -> bool {
    matches!(
        (url.scheme(), url.host_str()),
        ("tauri", Some("localhost")) | ("http" | "https", Some("tauri.localhost"))
    )
}

/// Runs before any page script in the window. Each API is replaced by a value that cannot be
/// changed back; without frames (the policy forbids them) and workers, there is no fresh copy
/// to borrow from.
const DISABLE_WEBRTC: &str = r#"
(() => {
  for (const name of ['RTCPeerConnection', 'webkitRTCPeerConnection', 'RTCDataChannel',
                      'RTCIceCandidate', 'RTCSessionDescription', 'RTCRtpSender',
                      'RTCRtpReceiver', 'RTCRtpTransceiver', 'RTCDtlsTransport', 'RTCIceTransport']) {
    try {
      Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false });
    } catch (_) { /* already absent or locked */ }
  }
})();
"#;

pub fn plugin<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("egress-guard")
        .js_init_script(DISABLE_WEBRTC.to_string())
        .on_navigation(|_webview, url| {
            let allowed = is_app_origin(url);
            if !allowed {
                // The URL is not printed: it may carry the very data someone tried to send.
                eprintln!("javabyte-gui: blocked navigation away from the app ({} scheme)", url.scheme());
            }
            allowed
        })
        .build()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_apps_own_origin_is_allowed() {
        for allowed in ["tauri://localhost/", "tauri://localhost/index.html", "http://tauri.localhost/", "https://tauri.localhost/x"] {
            assert!(is_app_origin(&allowed.parse().unwrap()), "{allowed}");
        }
        for refused in [
            "http://localhost/",
            "http://127.0.0.1/",
            "https://tauri.localhost.evil.example/",
            "https://evil.example/?tauri.localhost",
            "http://user@tauri.localhost.evil.example/",
            "file:///etc/passwd",
            "data:text/html,hi",
            "javascript:alert(1)",
            "about:blank",
            "mailto:a@b.example",
            "tauri://evil.example/",
        ] {
            assert!(!is_app_origin(&refused.parse().unwrap()), "{refused}");
        }
    }

    /// The network containment lives partly in configuration, where a one-word edit could
    /// undo it without any code changing. This test fails if any part is loosened. Changing
    /// it is a security decision; rerun tests/egress after any such change.
    #[test]
    fn configuration_keeps_the_window_sealed() {
        let config: serde_json::Value = serde_json::from_str(include_str!("../tauri.conf.json")).unwrap();
        let csp = &config["app"]["security"]["csp"];
        let expected = [
            ("default-src", "'self'"),
            ("script-src", "'self' 'wasm-unsafe-eval'"),
            ("style-src", "'self'"),
            ("img-src", "'self'"),
            ("font-src", "'self'"),
            ("connect-src", "'self' ipc: http://ipc.localhost"),
            ("media-src", "'none'"),
            ("object-src", "'none'"),
            ("frame-src", "'none'"),
            ("child-src", "'none'"),
            ("worker-src", "'none'"),
            ("manifest-src", "'none'"),
            ("base-uri", "'none'"),
            ("form-action", "'none'"),
            ("frame-ancestors", "'none'"),
        ];
        let directives = csp.as_object().expect("the policy must be set, as an object of directives");
        assert_eq!(directives.len(), expected.len(), "unexpected directives: {directives:?}");
        for (name, value) in expected {
            assert_eq!(csp[name].as_str(), Some(value), "content security policy {name}");
        }
        assert_ne!(config["app"]["security"]["dangerousDisableAssetCspModification"], serde_json::json!(true));

        // Every window sends all web traffic to a closed local port.
        for window in config["app"]["windows"].as_array().unwrap() {
            assert_eq!(window["proxyUrl"].as_str(), Some("http://127.0.0.1:9"), "window {}", window["label"]);
        }

        let capabilities: serde_json::Value = serde_json::from_str(include_str!("../capabilities/default.json")).unwrap();
        assert_eq!(capabilities["permissions"], serde_json::json!([]), "the window must get no plugin permissions");
        assert!(!capabilities.to_string().contains("remote"), "no remote origin may use the app's commands");
    }
}
