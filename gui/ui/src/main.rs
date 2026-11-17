//! The GUI's screens: start, sample overview, running, report, compare and preferences.

mod app;
mod bridge;
mod events;
mod isolation;
mod prefs;
mod screens;
mod settings;
mod util;

fn main() {
    console_error_panic_hook::set_once();
    leptos::mount::mount_to_body(app::App);
}
