//! Making untrusted text safe and honest to display.
//!
//! Strings from a sample are chosen by the malware author. They can contain terminal escape
//! codes, bidirectional overrides that make `exe.txt` read as `txt.exe`, invisible characters,
//! or megabytes of text. None of that can run code here (the screens insert text as text, never
//! as HTML), but it can mislead a reader or flood a screen reader. Every such string is passed
//! through [`display_text`] before it is shown.

/// Text prepared for display, plus whether anything was cut off.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DisplayText {
    /// The text, safe to insert as text.
    pub text: String,
    /// True if the original was longer than the limit.
    pub truncated: bool,
    /// Length of the original in characters, so the screen can say "showing 200 of 51,200".
    pub original_chars: usize,
}

/// Escapes characters that are invisible or change how surrounding text is drawn, and cuts the
/// result to at most `max_chars` characters of the original.
///
/// Escapes look like `\x07` or `\u{202e}`: visible, unambiguous, and read out character by
/// character by screen readers rather than silently acted on. A backslash in the original is
/// left alone; the escapes are for the reader, not a reversible encoding.
pub fn display_text(raw: &str, max_chars: usize) -> DisplayText {
    let original_chars = raw.chars().count();
    let mut text = String::with_capacity(raw.len().min(max_chars * 2));
    for c in raw.chars().take(max_chars) {
        if needs_escape(c) {
            let code = c as u32;
            if code < 0x100 {
                text.push_str(&format!("\\x{code:02x}"));
            } else {
                text.push_str(&format!("\\u{{{code:04x}}}"));
            }
        } else {
            text.push(c);
        }
    }
    DisplayText { text, truncated: original_chars > max_chars, original_chars }
}

/// Characters shown as a code rather than as themselves.
fn needs_escape(c: char) -> bool {
    matches!(c,
        // C0 and C1 controls, including escape sequences for terminals. Tab is kept escaped
        // too: in a table cell it would silently look like spaces.
        '\u{0000}'..='\u{001f}' | '\u{007f}'..='\u{009f}'
        // Bidirectional controls: embeddings, overrides and isolates.
        | '\u{061c}' | '\u{200e}' | '\u{200f}' | '\u{202a}'..='\u{202e}' | '\u{2066}'..='\u{2069}'
        // Zero-width and other invisible characters used to hide or split words.
        | '\u{00ad}' | '\u{034f}' | '\u{180e}' | '\u{200b}'..='\u{200d}' | '\u{2060}'..='\u{2064}'
        | '\u{feff}' | '\u{fff9}'..='\u{fffb}'
        // Line and paragraph separators would break a one-line cell.
        | '\u{2028}' | '\u{2029}')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ordinary_text_is_unchanged() {
        let shown = display_text("C:\\Users\\admin\\AppData\\Roaming\\évil.jar 🙂 عربى", 200);
        assert_eq!(shown.text, "C:\\Users\\admin\\AppData\\Roaming\\évil.jar 🙂 عربى");
        assert!(!shown.truncated);
    }

    #[test]
    fn controls_and_bidi_overrides_become_visible() {
        let shown = display_text("invoice\u{202e}fdp.exe\x07\x1b[31m\u{200b}", 200);
        assert_eq!(shown.text, "invoice\\u{202e}fdp.exe\\x07\\x1b[31m\\u{200b}");
    }

    #[test]
    fn markup_is_left_as_text() {
        // Escaping HTML is the rendering layer's job and Leptos does it; this function must not
        // double-escape, or the analyst would see "&lt;" instead of what the sample wrote.
        assert_eq!(display_text("<img src=x onerror=alert(1)>", 200).text, "<img src=x onerror=alert(1)>");
    }

    #[test]
    fn long_text_is_cut_by_characters_not_bytes() {
        let raw = "é".repeat(1000);
        let shown = display_text(&raw, 10);
        assert_eq!(shown.text.chars().count(), 10);
        assert!(shown.truncated);
        assert_eq!(shown.original_chars, 1000);
    }
}
