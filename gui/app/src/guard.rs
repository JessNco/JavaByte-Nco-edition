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
//! - **Loopback on Windows.** WebView2 (Chromium) sends `localhost` and `127.0.0.1` past any
//!   proxy unless told otherwise, and the CI egress test showed a navigation to `127.0.0.1`
//!   getting out that way. The window's `additionalBrowserArgs` repeat wry's default arguments,
//!   set the proxy themselves (wry adds `--proxy-server` only when no arguments are given) and
//!   add `--proxy-bypass-list=<-loopback>`, which routes loopback through the black hole too.
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
#[cfg_attr(feature = "egress-control", allow(dead_code))]
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

// The control build for tests/egress deliberately leaves the guard out.
#[cfg_attr(feature = "egress-control", allow(dead_code))]
pub fn plugin<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("egress-guard")
        .js_init_script(DISABLE_WEBRTC.to_string())
        .on_navigation(|_webview, url| {
            let allowed = is_app_origin(url);
            if !allowed {
                // The URL is not printed: it may carry the very data someone tried to send.
                eprintln!("javabyte-gui: blocked navigation away from the app ({} scheme)", url.scheme());
            }
            // Test builds name every decision, so the egress test can show which navigations
            // the guard saw at all.
            #[cfg(feature = "egress-probe")]
            eprintln!("javabyte-gui: navigation {} to {}", if allowed { "allowed" } else { "refused" }, url);
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

    /// Applies a JSON merge patch (RFC 7396) to `target`, as the Tauri CLI does with each
    /// `--config` file given to `cargo tauri build`. A local copy, so the test needs no
    /// dependency of its own; the rules are short: objects merge key by key, `null` removes a
    /// key, and anything else (arrays included) replaces the value whole.
    fn merge_patch(target: &mut serde_json::Value, patch: &serde_json::Value) {
        let Some(patch) = patch.as_object() else {
            *target = patch.clone();
            return;
        };
        if !target.is_object() {
            *target = serde_json::json!({});
        }
        let target = target.as_object_mut().expect("just made an object");
        for (key, value) in patch {
            if value.is_null() {
                target.remove(key);
            } else {
                merge_patch(target.entry(key.clone()).or_insert(serde_json::Value::Null), value);
            }
        }
    }

    #[test]
    fn merge_patches_follow_the_standard() {
        let mut target = serde_json::json!({"a": {"b": 1, "c": [1, 2]}, "d": 4});
        merge_patch(&mut target, &serde_json::json!({"a": {"b": null, "c": [3]}, "e": {"f": 5}}));
        assert_eq!(target, serde_json::json!({"a": {"c": [3]}, "d": 4, "e": {"f": 5}}));
    }

    /// The configuration of every build the installers make: the base file alone (plain
    /// `cargo build`, the egress probe), with the bundle overlay, and with the bundle and
    /// bridge overlays (gui/packaging/build.sh).
    fn shipped_configurations() -> Vec<(&'static str, serde_json::Value)> {
        let read = |text: &str| -> serde_json::Value { serde_json::from_str(text).unwrap() };
        let base = read(include_str!("../tauri.conf.json"));
        let bundle = read(include_str!("../../packaging/tauri.bundle.conf.json"));
        let bridge = read(include_str!("../../packaging/tauri.bridge.conf.json"));
        let mut with_bundle = base.clone();
        merge_patch(&mut with_bundle, &bundle);
        let mut with_bridge = with_bundle.clone();
        merge_patch(&mut with_bridge, &bridge);
        vec![("tauri.conf.json", base), ("with the bundle overlay", with_bundle), ("with the bundle and bridge overlays", with_bridge)]
    }

    /// The network containment lives partly in configuration, where a one-word edit could
    /// undo it without any code changing. This test fails if any part is loosened, in the base
    /// configuration or by a packaging overlay merged into it for the installers. Changing it
    /// is a security decision; rerun tests/egress after any such change.
    #[test]
    fn configuration_keeps_the_window_sealed() {
        for (name, config) in shipped_configurations() {
            assert_sealed(name, &config);
        }
        // The overlays are for packaging only. Anything else in them would be missed by the
        // egress test, which builds without them.
        for (name, overlay) in [
            ("tauri.bundle.conf.json", include_str!("../../packaging/tauri.bundle.conf.json")),
            ("tauri.bridge.conf.json", include_str!("../../packaging/tauri.bridge.conf.json")),
        ] {
            let overlay: serde_json::Value = serde_json::from_str(overlay).unwrap();
            let keys: Vec<&String> = overlay.as_object().expect("an overlay is an object").keys().collect();
            assert_eq!(keys, ["bundle"], "{name} may change only the bundle settings");
        }

        let capabilities: serde_json::Value = serde_json::from_str(include_str!("../capabilities/default.json")).unwrap();
        assert_eq!(capabilities["permissions"], serde_json::json!([]), "the window must get no plugin permissions");
        assert!(!capabilities.to_string().contains("remote"), "no remote origin may use the app's commands");
    }

    /// The checks one configuration must pass: the content security policy exactly as
    /// written, and every window's traffic sent to the closed local port. `name` says which
    /// configuration failed.
    fn assert_sealed(name: &str, config: &serde_json::Value) {
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
        let directives = csp.as_object().unwrap_or_else(|| panic!("{name}: the policy must be set, as an object of directives"));
        assert_eq!(directives.len(), expected.len(), "{name}: unexpected directives: {directives:?}");
        for (directive, value) in expected {
            assert_eq!(csp[directive].as_str(), Some(value), "{name}: content security policy {directive}");
        }
        assert_ne!(config["app"]["security"]["dangerousDisableAssetCspModification"], serde_json::json!(true), "{name}");

        // Every window sends all web traffic, loopback included, to a closed local port.
        let windows = config["app"]["windows"].as_array().unwrap_or_else(|| panic!("{name}: windows must be listed"));
        assert!(!windows.is_empty(), "{name}: no windows");
        for window in windows {
            assert_eq!(window["proxyUrl"].as_str(), Some("http://127.0.0.1:9"), "{name}: window {}", window["label"]);
            let args = window["additionalBrowserArgs"].as_str().unwrap_or_default();
            for required in ["--proxy-server=http://127.0.0.1:9", "--proxy-bypass-list=<-loopback>", "msSmartScreenProtection"] {
                assert!(args.split_whitespace().any(|a| a.contains(required)), "{name}: window {} WebView2 arguments lack {required}", window["label"]);
            }
        }
    }
}
