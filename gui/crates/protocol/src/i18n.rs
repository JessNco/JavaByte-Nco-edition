//! All user-visible text, from the Fluent resource files in `gui/locales/<language>/`.
//!
//! Screens and backend both use [`t!`](crate::t), so the same words are used everywhere and
//! everything can be translated. Text from a sample is never put into a message's pattern, only
//! passed as an argument, so it cannot change the message around it.
//!
//! English is the only language so far. Adding one means adding its folder and an entry in
//! [`RESOURCES`].

use fluent_bundle::{FluentBundle, FluentResource, FluentValue};
use std::cell::RefCell;
use unic_langid::LanguageIdentifier;

pub use fluent_bundle::FluentArgs;

/// The resource files of each language, compiled in so the app needs no files at run time.
pub const RESOURCES: &[(&str, &[(&str, &str)])] = &[(
    "en",
    &[
        ("app.ftl", include_str!("../../../locales/en/app.ftl")),
        ("start.ftl", include_str!("../../../locales/en/start.ftl")),
        ("overview.ftl", include_str!("../../../locales/en/overview.ftl")),
        ("running.ftl", include_str!("../../../locales/en/running.ftl")),
        ("report.ftl", include_str!("../../../locales/en/report.ftl")),
        ("events.ftl", include_str!("../../../locales/en/events.ftl")),
        ("display.ftl", include_str!("../../../locales/en/display.ftl")),
        ("settings.ftl", include_str!("../../../locales/en/settings.ftl")),
        ("limits.ftl", include_str!("../../../locales/en/limits.ftl")),
        ("files.ftl", include_str!("../../../locales/en/files.ftl")),
        ("compare.ftl", include_str!("../../../locales/en/compare.ftl")),
        ("outcomes.ftl", include_str!("../../../locales/en/outcomes.ftl")),
        ("errors.ftl", include_str!("../../../locales/en/errors.ftl")),
    ],
)];

/// The language in use. The page's `lang` attribute is set from it.
pub fn language() -> &'static str {
    "en"
}

thread_local! {
    // A bundle per thread: Fluent's bundles are not shared between threads, and the desktop
    // app answers commands on several.
    static BUNDLE: RefCell<Option<FluentBundle<FluentResource>>> = const { RefCell::new(None) };
}

/// Loads one language's resources into a bundle.
fn build(language: &str) -> FluentBundle<FluentResource> {
    let id: LanguageIdentifier = language.parse().expect("a valid language identifier");
    let mut bundle = FluentBundle::new(vec![id]);
    // No invisible direction marks around arguments: the screens show invisible characters
    // from samples as visible codes, and marks added here would be mistaken for them.
    bundle.set_use_isolating(false);
    bundle.set_formatter(Some(format_number));
    let files = RESOURCES.iter().find(|(l, _)| *l == language).map_or(&[][..], |(_, f)| *f);
    for (name, text) in files {
        let resource = FluentResource::try_new((*text).to_string())
            .unwrap_or_else(|(_, errors)| panic!("{language}/{name} has syntax errors: {errors:?}"));
        bundle
            .add_resource(resource)
            .unwrap_or_else(|errors| panic!("{language}/{name} repeats a message: {errors:?}"));
    }
    bundle
}

/// Whole numbers with thousands separators ("300,000"), as people read them.
fn format_number<M>(value: &FluentValue, _: &M) -> Option<String> {
    match value {
        FluentValue::Number(n) if n.value.fract() == 0.0 && n.value.abs() < 1e15 && n.options.minimum_fraction_digits.is_none() => {
            Some(group_digits(n.value as i64))
        }
        _ => None,
    }
}

/// `1234567` as "1,234,567".
pub fn group_digits(n: i64) -> String {
    let digits = n.unsigned_abs().to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3 + 1);
    if n < 0 {
        out.push('-');
    }
    for (i, c) in digits.chars().enumerate() {
        if i > 0 && (digits.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

/// The text of a message. A missing message is a programming error: tests fail on it, and a
/// release build shows the message's name rather than nothing.
pub fn tr(id: &str, args: Option<&FluentArgs>) -> String {
    BUNDLE.with(|cell| {
        let mut slot = cell.borrow_mut();
        let bundle = slot.get_or_insert_with(|| build(language()));
        let Some(pattern) = bundle.get_message(id).and_then(|m| m.value()) else {
            debug_assert!(false, "no message {id:?} in the resource files");
            return id.to_string();
        };
        let mut errors = Vec::new();
        let text = bundle.format_pattern(pattern, args, &mut errors);
        debug_assert!(errors.is_empty(), "message {id:?}: {errors:?}");
        text.into_owned()
    })
}

/// Whether a message exists; for tests.
pub fn has(id: &str) -> bool {
    BUNDLE.with(|cell| {
        let mut slot = cell.borrow_mut();
        slot.get_or_insert_with(|| build(language())).has_message(id)
    })
}

/// Turns an argument into a Fluent value, owning its text.
pub fn arg<T: IntoArg>(value: T) -> FluentValue<'static> {
    value.into_arg()
}

/// A value that can be a message argument: text or a number.
pub trait IntoArg {
    /// The value as Fluent holds it.
    fn into_arg(self) -> FluentValue<'static>;
}

impl IntoArg for String {
    fn into_arg(self) -> FluentValue<'static> {
        FluentValue::from(self)
    }
}
impl IntoArg for &String {
    fn into_arg(self) -> FluentValue<'static> {
        FluentValue::from(self.clone())
    }
}
impl IntoArg for &str {
    fn into_arg(self) -> FluentValue<'static> {
        FluentValue::from(self.to_string())
    }
}
macro_rules! number_arg {
    ($($t:ty),*) => {$(
        impl IntoArg for $t {
            fn into_arg(self) -> FluentValue<'static> { FluentValue::from(self) }
        }
    )*};
}
number_arg!(u8, u16, u32, u64, usize, i32, i64, f64);

/// `t!("message-id")` or `t!("message-id", count = 3, name = sample_name)`.
#[macro_export]
macro_rules! t {
    ($id:expr) => {
        $crate::i18n::tr($id, None)
    };
    ($id:expr, $($name:ident = $value:expr),+ $(,)?) => {{
        let mut args = $crate::i18n::FluentArgs::new();
        $(args.set(stringify!($name), $crate::i18n::arg($value));)+
        $crate::i18n::tr($id, Some(&args))
    }};
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn digits_are_grouped() {
        assert_eq!(group_digits(0), "0");
        assert_eq!(group_digits(999), "999");
        assert_eq!(group_digits(1000), "1,000");
        assert_eq!(group_digits(300_000), "300,000");
        assert_eq!(group_digits(-1_234_567), "-1,234,567");
    }

    #[test]
    fn every_resource_file_parses_and_no_message_repeats() {
        for (language, _) in RESOURCES {
            build(language);
        }
    }
}
