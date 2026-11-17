//! The window's frame: skip link, heading, navigation between screens, and the live region.
//!
//! Screens follow the analysis: Start, Sample overview, Running, Report. Display settings are
//! always available. Moving to a screen puts keyboard focus on its heading and changes the
//! window title, so screen-reader users always know where they are (the usual pattern for
//! single-window apps, since no page load announces it).

use crate::screens::{overview, report, running, start};
use crate::util::{announcer, focus_id};
use crate::{events, prefs};
use javabyte_gui_protocol::t;
use javabyte_gui_protocol::views::{Overview, ReportView, SampleChoice};
use leptos::prelude::*;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Screen {
    Start,
    Overview,
    Running,
    Report,
    Display,
}

impl Screen {
    fn title(self) -> String {
        t!(match self {
            Screen::Start => "screen-start",
            Screen::Overview => "screen-overview",
            Screen::Running => "screen-running",
            Screen::Report => "screen-report",
            Screen::Display => "screen-display",
        })
    }

    fn heading_id(self) -> &'static str {
        match self {
            Screen::Start => "start-heading",
            Screen::Overview => "overview-heading",
            Screen::Running => "running-heading",
            Screen::Report => "report-heading",
            Screen::Display => "display-heading",
        }
    }
}

/// What the screens share: where we are, and the analysis so far.
#[derive(Clone, Copy)]
pub struct Flow {
    pub screen: RwSignal<Screen>,
    pub sample: RwSignal<Option<SampleChoice>>,
    pub overview: RwSignal<Option<Overview>>,
    pub run_started: RwSignal<bool>,
    pub report: RwSignal<Option<ReportView>>,
}

thread_local! {
    static FLOW: std::cell::OnceCell<Flow> = const { std::cell::OnceCell::new() };
}

/// The shared state. Page-wide rather than Leptos context, because asynchronous replies and
/// timers use it and cannot see context (looking it up there panics, as phase 0 found for the
/// announcer). Created by `App` first, so it lives as long as the page.
pub fn flow() -> Flow {
    FLOW.with(|cell| {
        *cell.get_or_init(|| Flow {
            screen: RwSignal::new(Screen::Start),
            sample: RwSignal::new(None),
            overview: RwSignal::new(None),
            run_started: RwSignal::new(false),
            report: RwSignal::new(None),
        })
    })
}

/// Opens a screen: shows it, names the window after it, and moves focus to its heading.
pub fn show(screen: Screen) {
    let flow = flow();
    flow.screen.set(screen);
    document().set_title(&t!("window-title", screen = screen.title()));
    focus_id(screen.heading_id().into());
}

#[component]
pub fn App() -> impl IntoView {
    // First use creates both under this component, which lives as long as the page; created
    // under a shorter-lived component, they would be disposed with it.
    let announcer = announcer();
    prefs::preferences().get_untracked().apply();
    let flow = flow();
    document().set_title(&t!("window-title", screen = Screen::Start.title()));
    // Screen readers choose voice and pronunciation from this.
    if let Some(root) = document().document_element() {
        let _ = root.set_attribute("lang", javabyte_gui_protocol::i18n::language());
    }

    let nav_item = move |screen: Screen, available: Signal<bool>, unavailable_reason: &'static str| {
        let unavailable_reason = if unavailable_reason.is_empty() { String::new() } else { t!(unavailable_reason) };
        let reason_id = format!("nav-reason-{}", screen.heading_id());
        view! {
            <li>
                <button
                    type="button"
                    aria-current=move || (flow.screen.get() == screen).then_some("page")
                    disabled=move || !available.get()
                    aria-describedby=move || (!available.get()).then(|| reason_id.clone())
                    on:click=move |_| show(screen)
                >
                    {screen.title()}
                </button>
                <Show when=move || !available.get()>
                    <span class="visually-hidden" id=format!("nav-reason-{}", screen.heading_id())>{unavailable_reason.clone()}</span>
                </Show>
            </li>
        }
    };

    view! {
        <a class="skip-link" href="#main">{t!("skip-link")}</a>
        <header class="banner">
            <h1 class="app-name">{t!("app-name")}</h1>
            <nav aria-label=t!("nav-label")>
                <ul class="nav-list">
                    {nav_item(Screen::Start, Signal::derive(|| true), "")}
                    {nav_item(Screen::Overview, Signal::derive(move || flow.overview.with(Option::is_some)), "nav-overview-unavailable")}
                    {nav_item(Screen::Running, Signal::derive(move || flow.run_started.get()), "nav-running-unavailable")}
                    {nav_item(Screen::Report, Signal::derive(move || flow.report.with(Option::is_some)), "nav-report-unavailable")}
                    {nav_item(Screen::Display, Signal::derive(|| true), "")}
                </ul>
            </nav>
        </header>
        <main id="main" tabindex="-1">
            // Screens stay in the page when hidden, so a scrolled report or a half-made choice
            // is still there on return. `hidden` removes them from the accessibility tree.
            <section aria-labelledby="start-heading" hidden=move || flow.screen.get() != Screen::Start>
                <start::StartScreen />
            </section>
            <section aria-labelledby="overview-heading" hidden=move || flow.screen.get() != Screen::Overview>
                <overview::OverviewScreen />
            </section>
            <section aria-labelledby="running-heading" hidden=move || flow.screen.get() != Screen::Running>
                <running::RunningScreen />
            </section>
            <section aria-labelledby="report-heading" hidden=move || flow.screen.get() != Screen::Report>
                <report::ReportScreen visible=Signal::derive(move || flow.screen.get() == Screen::Report) />
            </section>
            <section aria-labelledby="display-heading" hidden=move || flow.screen.get() != Screen::Display>
                <prefs::DisplayPanel />
            </section>
        </main>
        <div role="status" aria-live="polite" aria-atomic="true" class="visually-hidden" id="announcer">
            {move || announcer.text()}
        </div>
    }
}

/// Re-exported for screens that embed the event list.
pub use events::EventsPanel;

/// Shown when a backend call fails: a heading that takes focus, the message, and nothing else.
/// `role="alert"` is not used because focus already brings it to the screen reader's attention.
#[component]
pub fn ErrorBox(id: &'static str, message: Signal<Option<String>>) -> impl IntoView {
    view! {
        <Show when=move || message.get().is_some()>
            <div class="error-summary" role="region" aria-labelledby=format!("{id}-title")>
                <h3 id=format!("{id}-title") tabindex="-1">{t!("error-heading")}</h3>
                <p>{move || message.get().unwrap_or_default()}</p>
            </div>
        </Show>
    }
}

pub fn report_error(target: RwSignal<Option<String>>, id: &'static str, message: String) {
    target.set(Some(message));
    focus_id(format!("{id}-title"));
}

/// Opens a report and shows it.
pub fn open_report(view: ReportView) {
    let flow = flow();
    flow.report.set(Some(view));
    show(Screen::Report);
}
