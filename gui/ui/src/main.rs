//! The GUI's screens. Phase 0: an accessibility trial with one of each hard component.

mod app;
mod bridge;
mod events;
mod form;
mod prefs;
mod run;
mod util;

fn main() {
    console_error_panic_hook::set_once();
    leptos::mount::mount_to_body(app::App);
}
