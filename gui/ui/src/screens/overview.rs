//! Sample overview: what the sample is, what that means, and where to start.

use crate::app::{ErrorBox, Screen, flow, report_error, show};
use crate::util::{copied_message, copy_text, fmt_num, fmt_size};
use javabyte_gui_protocol::display_text;
use javabyte_gui_protocol::t;
use javabyte_gui_backend::config::ConfigPath;
use javabyte_gui_protocol::views::EntryChoice;
use leptos::prelude::*;

#[component]
pub fn OverviewScreen() -> impl IntoView {
    let flow = flow();
    // Index into the overview's entries; usize::MAX means "another class" from the list.
    let chosen = RwSignal::new(0usize);
    let other = RwSignal::new(String::new());
    let copied = RwSignal::new(String::new());
    let error = RwSignal::new(None::<String>);
    let starting = RwSignal::new(false);

    // A new overview resets the choice to the recommended start.
    Effect::new(move |_| {
        flow.overview.track();
        chosen.set(0);
        other.set(flow.overview.with(|o| o.as_ref().and_then(|o| o.other_classes.first().cloned()).unwrap_or_default()));
        error.set(None);
    });

    let entry = move || -> Option<EntryChoice> {
        flow.overview.with(|o| {
            let o = o.as_ref()?;
            if chosen.get() == usize::MAX {
                Some(EntryChoice::MainOf { class: other.get() })
            } else {
                o.entries.get(chosen.get()).map(|e| e.entry.clone())
            }
        })
    };

    // The choice is part of the settings: changing it sets `entryPoints` there. The first
    // option is what the suggested settings already say, so nothing changes until the person
    // picks something else.
    let choose = move |index: usize| {
        chosen.set(index);
        if let Some(entry) = entry() {
            crate::settings::set_value(&ConfigPath::parse("entryPoints"), Some(entry.entry_points()));
        }
    };

    let start = move |_| crate::settings::analyse(starting, move |message| report_error(error, "overview-error", message));
    view! {
        <h2 id="overview-heading" tabindex="-1">
            {move || t!("overview-heading", name = flow.sample.with(|s| s.as_ref().map(|s| display_text(&s.name, 120).text).unwrap_or_default()))}
        </h2>
        <ErrorBox id="overview-error" message=Signal::derive(move || error.get()) />
        {move || flow.overview.get().map(|o| {
            let i = &o.inspection;
            let start_point = match (&i.main_class, &i.agent_class) {
                (Some(main), _) => main.replace('/', "."),
                (None, Some(agent)) => t!("overview-start-agent", class = agent.replace('/', ".")),
                (None, None) => t!("overview-start-none"),
            };
            view! {
                <h3>{t!("overview-what-heading")}</h3>
                <dl class="stats">
                    <dt>{t!("overview-file")}</dt><dd class="untrusted">{display_text(&o.sample.path, 300).text}</dd>
                    <dt>{t!("overview-size")}</dt><dd>{fmt_size(i.size)}</dd>
                    <dt>{t!("overview-classes")}</dt><dd>{fmt_num(i.class_count)}</dd>
                    <dt>{t!("overview-start-point")}</dt><dd class="untrusted">{display_text(&start_point, 300).text}</dd>
                    <dt>{t!("overview-native")}</dt><dd>{if i.native_method_count == 0 && i.native_libraries.is_empty() { t!("overview-native-none") } else {
                        t!("overview-native-some", methods = i.native_method_count, libraries = i.native_libraries.len())
                    }}</dd>
                    <dt>{t!("overview-hidden-calls")}</dt><dd>{fmt_num(i.obfuscated_call_sites)}</dd>
                    <dt>{t!("overview-loaders")}</dt><dd>{fmt_num(i.class_loaders.len() as u64)}</dd>
                </dl>
                {(!o.findings.is_empty()).then(|| view! {
                    <h3>{t!("overview-meaning-heading")}</h3>
                    <ul class="findings">
                        {o.findings.iter().map(|f| view! {
                            <li>
                                <strong>{t!(if f.level == "attention" { "finding-important" } else { "finding-note" }, title = &f.title)}</strong>
                                " "{f.detail.clone()}
                            </li>
                        }).collect_view()}
                    </ul>
                })}
            }
        })}
        <fieldset>
            <legend>{t!("overview-start-heading")}</legend>
            <p class="hint" id="entry-hint">{t!("overview-start-hint")}</p>
            {move || flow.overview.get().map(|o| {
                let entries = o.entries.clone();
                let others = o.other_classes.clone();
                view! {
                    {entries.into_iter().enumerate().map(|(index, option)| {
                        let id = format!("entry-{index}");
                        let help_id = format!("entry-help-{index}");
                        view! {
                            <div class="choice choice-with-help">
                                <input
                                    type="radio"
                                    name="entry"
                                    id=id.clone()
                                    aria-describedby=help_id.clone()
                                    prop:checked=move || chosen.get() == index
                                    on:change=move |_| choose(index)
                                />
                                <div>
                                    <label for=id class="untrusted">{display_text(&option.label, 200).text}</label>
                                    <p class="hint" id=help_id>{option.help.clone()}</p>
                                </div>
                            </div>
                        }
                    }).collect_view()}
                    {(!others.is_empty()).then(|| {
                        let count = others.len();
                        view! {
                            <div class="choice choice-with-help">
                                <input
                                    type="radio"
                                    name="entry"
                                    id="entry-other"
                                    aria-describedby="entry-other-help"
                                    prop:checked=move || chosen.get() == usize::MAX
                                    on:change=move |_| choose(usize::MAX)
                                />
                                <div>
                                    <label for="entry-other">{t!("overview-other")}</label>
                                    <p class="hint" id="entry-other-help">{t!("overview-other-help", count = count)}</p>
                                </div>
                            </div>
                            <div class="field indented">
                                <label for="entry-other-class">{t!("overview-other-class")}</label>
                                <select
                                    id="entry-other-class"
                                    disabled=move || chosen.get() != usize::MAX
                                    on:change=move |ev| {
                                        other.set(event_target_value(&ev));
                                        choose(usize::MAX);
                                    }
                                >
                                    {others.into_iter().map(|class| {
                                        let shown = display_text(&class, 200).text;
                                        view! { <option value=class>{shown}</option> }
                                    }).collect_view()}
                                </select>
                            </div>
                        }
                    })}
                }
            })}
        </fieldset>
        <h3 id="overview-command-heading">{t!("overview-command-heading")}</h3>
        <pre class="command untrusted" id="overview-command" aria-labelledby="overview-command-heading" tabindex="0"><code>{crate::settings::command_line}</code></pre>
        <div class="buttons">
            <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&crate::settings::command_line())))>{t!("overview-copy-command")}</button>
            <span role="status" class="inline-status">{move || copied.get()}</span>
        </div>
        <div class="buttons">
            <button type="button" class="primary" disabled=move || starting.get() on:click=start>{t!("overview-analyse")}</button>
            <button type="button" on:click=move |_| show(Screen::Settings)>{t!("overview-change-settings")}</button>
            <button type="button" on:click=move |_| show(Screen::Start)>{t!("overview-different-sample")}</button>
        </div>
    }
}
