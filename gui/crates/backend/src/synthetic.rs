//! Made-up events that look like a real report, for exercising the screens.
//!
//! Rows are computed from their position, so any number of them costs no memory. Every few
//! hundred rows is a hostile string of the kind real samples produce: markup, bidirectional
//! overrides, terminal escapes, very long text, other scripts. The screens must show all of
//! them as plain, honest text.

use crate::events::EventSource;
use javabyte_gui_protocol::EventRow;

/// Made-up events computed from their position: any number of rows, no memory.
pub struct SyntheticEvents {
    count: u64,
}

impl SyntheticEvents {
    /// A source of `count` events.
    pub fn new(count: u64) -> Self {
        Self { count }
    }
}

/// Category, action and a template for the details, weighted roughly like a busy real run:
/// mostly strings and files, fewer network and process events.
const KINDS: &[(&str, &str, &str)] = &[
    ("string", "returned", "method=a/b/Decrypt.s(Ljava/lang/String;)Ljava/lang/String; value=config-{n}"),
    ("string", "returned", "method=a/b/Decrypt.s(Ljava/lang/String;)Ljava/lang/String; value={hostile}"),
    ("file", "check", "path=C:\\Users\\admin\\AppData\\Local\\Profile{n}\\Local Storage\\leveldb exists=false"),
    ("file", "write", "path=C:\\Users\\admin\\AppData\\Roaming\\cache{n}.bin size={n} sha256=9f2c{n}"),
    ("reflect", "invoke", "method=java/lang/String.valueOf([C)Ljava/lang/String;"),
    ("crypto", "cipher-output", "algorithm=AES size=48 preview=host{n}.duckdns.org|4787|true"),
    ("net", "dns", "host=c2-{n}.duckdns.org result=203.0.113.10"),
    ("net", "connect", "endpoint=c2-{n}.duckdns.org:4787"),
    ("net", "http", "method=POST url=https://panel{n}.example.test/gate.php matchedRule=none"),
    ("process", "exec", "command=cmd.exe /c schtasks /create /tn Updater{n} /tr C:\\Users\\admin\\x.jar"),
    ("class", "define", "name=stage/Payload{n} via=ClassLoader.defineClass size=9827"),
    ("console", "stdout", "text={hostile}"),
    ("thread", "start", "name=Thread-{n} class=java.lang.Thread"),
    ("policy", "blocked", "kind=method member=java/awt/Robot.createScreenCapture(Ljava/awt/Rectangle;)Ljava/awt/image/BufferedImage;"),
    ("gap", "missing-class", "class=com/fasterxml/jackson/databind/ObjectMapper"),
];

/// Strings real samples have produced, or close relatives of them.
const HOSTILE: &[&str] = &[
    "<img src=x onerror=alert(1)><script>alert('xss')</script>",
    "invoice\u{202e}fdp.exe",
    "\x1b[31mRED\x1b[0m bell\x07 null\0 tab\tend",
    "zero\u{200b}width\u{2060}joiner\u{feff}",
    "Button: Delete all reports", // not-ui-text: pretend sample data
    "Ошибка доступа — 访问被拒绝 — رفض الوصول", // not-ui-text: pretend sample data
    "🙂🔥💀 emoji-heavy line",
    "line one\nline two\r\nline three\u{2028}separator", // not-ui-text: pretend sample data
];

impl EventSource for SyntheticEvents {
    fn len(&self) -> u64 {
        self.count
    }

    fn category(&self, index: u64) -> &str {
        kind(index).0
    }

    fn row(&self, index: u64) -> EventRow {
        let (category, action, template) = kind(index);
        let n = index.to_string();
        let hostile = if index.is_multiple_of(997) {
            // A rare, very long value: 20,000 characters, as decoded payloads often are.
            "A".repeat(20_000)
        } else {
            HOSTILE[(index / 15) as usize % HOSTILE.len()].to_string()
        };
        EventRow {
            seq: index,
            category: category.to_string(),
            action: action.to_string(),
            details: template.replace("{n}", &n).replace("{hostile}", &hostile),
            caller: Some(format!("a/b/Main.run:{}", 10 + index % 400)),
        }
    }
}

/// The made-up kind of event at a position.
fn kind(index: u64) -> (&'static str, &'static str, &'static str) {
    // A cheap scramble so neighbouring rows differ, while staying deterministic.
    let mixed = index.wrapping_mul(0x9E37_79B9_7F4A_7C15) >> 32;
    KINDS[(mixed % KINDS.len() as u64) as usize]
}
