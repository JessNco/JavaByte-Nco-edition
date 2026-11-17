//! Sample overview: what the sample is, what that means, and where to start.

use crate::app::{ErrorBox, Screen, flow, report_error, show};
use crate::bridge;
use crate::util::{announcer, copied_message, copy_text, fmt_num, fmt_size};
use javabyte_gui_protocol::display_text;
use javabyte_gui_protocol::views::EntryChoice;
use leptos::prelude::*;
use leptos::task::spawn_local;

#[component]
pub fn OverviewScreen() -> impl IntoView {
    let flow = flow();
    // Index into the overview's entries; usize::MAX means "another class" from the list.
    let chosen = RwSignal::new(0usize);
    let other = RwSignal::new(String::new());
    let command = RwSignal::new(String::new());
    let copied = RwSignal::new("");
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

    // Keep the equivalent command in step with the choice.
    Effect::new(move |_| {
        let (Some(sample), Some(entry)) = (flow.sample.get(), entry()) else { return };
        spawn_local(async move {
            if let Ok(text) = bridge::command_line(&sample, &entry).await {
                command.set(text);
            }
        });
    });

    let start = move |_| {
        let (Some(sample), Some(entry)) = (flow.sample.get_untracked(), entry()) else { return };
        starting.set(true);
        spawn_local(async move {
            match bridge::start_run(&sample, &entry).await {
                Ok(()) => {
                    flow.run_started.set(true);
                    flow.report.set(None);
                    show(Screen::Running);
                    announcer().say(format!("Analysis of {} started.", sample.name));
                }
                Err(message) => report_error(error, "overview-error", message),
            }
            starting.set(false);
        });
    };

    view! {
        <h2 id="overview-heading" tabindex="-1">
            {move || format!("Before analysing: {}", flow.sample.with(|s| s.as_ref().map(|s| display_text(&s.name, 120).text).unwrap_or_default()))}
        </h2>
        <ErrorBox id="overview-error" message=Signal::derive(move || error.get()) />
        {move || flow.overview.get().map(|o| {
            let i = &o.inspection;
            let start_point = match (&i.main_class, &i.agent_class) {
                (Some(main), _) => main.replace('/', "."),
                (None, Some(agent)) => format!("none; it is a Java agent ({})", agent.replace('/', ".")),
                (None, None) => "none named".to_string(),
            };
            view! {
                <h3>"What it is"</h3>
                <dl class="stats">
                    <dt>"File"</dt><dd class="untrusted">{display_text(&o.sample.path, 300).text}</dd>
                    <dt>"Size"</dt><dd>{fmt_size(i.size)}</dd>
                    <dt>"Classes"</dt><dd>{fmt_num(i.class_count)}</dd>
                    <dt>"Starting point"</dt><dd class="untrusted">{display_text(&start_point, 300).text}</dd>
                    <dt>"Native code"</dt><dd>{if i.native_method_count == 0 && i.native_libraries.is_empty() { "None".to_string() } else {
                        format!("{} native methods, {} libraries", fmt_num(i.native_method_count), i.native_libraries.len())
                    }}</dd>
                    <dt>"Hidden calls"</dt><dd>{fmt_num(i.obfuscated_call_sites)}</dd>
                    <dt>"Own class loaders"</dt><dd>{fmt_num(i.class_loaders.len() as u64)}</dd>
                </dl>
                {(!o.findings.is_empty()).then(|| view! {
                    <h3>"What this means"</h3>
                    <ul class="findings">
                        {o.findings.iter().map(|f| view! {
                            <li>
                                <strong>{if f.level == "attention" { "Important: " } else { "Note: " }}{f.title.clone()}"."</strong>
                                " "{f.detail.clone()}
                            </li>
                        }).collect_view()}
                    </ul>
                })}
            }
        })}
        <fieldset>
            <legend>"Where to start"</legend>
            <p class="hint" id="entry-hint">"The analysis runs the sample's code from this point."</p>
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
                                    on:change=move |_| chosen.set(index)
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
                                    on:change=move |_| chosen.set(usize::MAX)
                                />
                                <div>
                                    <label for="entry-other">"Another class with a main method"</label>
                                    <p class="hint" id="entry-other-help">{format!("{count} more classes have one. Choose below.")}</p>
                                </div>
                            </div>
                            <div class="field indented">
                                <label for="entry-other-class">"Class to start from"</label>
                                <select
                                    id="entry-other-class"
                                    disabled=move || chosen.get() != usize::MAX
                                    on:change=move |ev| other.set(event_target_value(&ev))
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
        <h3 id="overview-command-heading">"Same analysis on the command line"</h3>
        <pre class="command untrusted" aria-labelledby="overview-command-heading" tabindex="0"><code>{move || command.get()}</code></pre>
        <div class="buttons">
            <button type="button" on:click=move |_| copied.set(copied_message(copy_text(&command.get_untracked())))>"Copy command"</button>
            <span role="status" class="inline-status">{move || copied.get()}</span>
        </div>
        <p class="hint">"More settings, such as made-up server answers and time limits, come in a later version. Until then they can be set on the command line."</p>
        <div class="buttons">
            <button type="button" class="primary" disabled=move || starting.get() on:click=start>"Analyse"</button>
            <button type="button" on:click=move |_| show(Screen::Start)>"Choose a different sample"</button>
        </div>
    }
}
