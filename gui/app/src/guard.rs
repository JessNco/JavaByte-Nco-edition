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
}
