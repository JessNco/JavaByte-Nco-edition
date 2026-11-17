//! The report's Limits and gaps, Files, and Settings used tabs (phase 4).

use crate::app::flow;
use crate::bridge;
use crate::util::{Disclosure, announcer, focus_id, fmt_num, fmt_size};
use javabyte_gui_protocol::i18n::tr;
use javabyte_gui_protocol::limits::NextStep;
use javabyte_gui_protocol::views::{Artifact, PAGE_BYTES};
use javabyte_gui_protocol::{display_text, t};
use leptos::prelude::*;
use leptos::task::spawn_local;

/// Text from a sample or a file, line by line, with invisible and control characters shown as
/// codes; never markup.
fn safe_lines(text: &str) -> String {
    text.lines().map(|line| display_text(line, usize::MAX).text).collect::<Vec<_>>().join("\n")
}

/// "What to try", with a button that opens Settings for a new run when a setting helps.
fn next_step_view(next: Option<NextStep>) -> impl IntoView {
    next.map(|next| {
        let setting = next.setting.clone();
        view! {
            <p class="next-step">
                {t!("limits-next", text = next.text.clone())}
                {setting.map(|setting| {
                    let label = t!("limits-show-setting-label", setting = setting.clone());
                    view! {
                        " "
                        <button type="button" aria-label=label on:click=move |_| crate::settings::run_again_and_show(Some(setting.clone()))>
                            {t!("limits-show-setting")}
                        </button>
                    }
                })}
            </p>
        }
    })
}

#[component]
pub fn LimitsPanel() -> impl IntoView {
    let flow = flow();
    move || {
        let Some(report) = flow.report.get() else { return ().into_any() };
        let limits = report.limits;
        view! {
            <h3>{t!("limits-heading-endings")}</h3>
            <ul class="endings">
                {limits.endings.into_iter().map(|ending| view! {
                    <li>
                        <strong class="untrusted">{display_text(&ending.entry, 200).text}</strong>": "{ending.meaning.clone()}
                        {ending.detail.clone().map(|detail| view! {
                            <Disclosure label=t!("report-details")>
                                <pre class="detail-text untrusted" tabindex="0">{safe_lines(&detail)}</pre>
                            </Disclosure>
                        })}
                        {next_step_view(ending.next.clone())}
                    </li>
                }).collect_view()}
            </ul>

            <h3>{t!("limits-heading-gaps")}</h3>
            {if limits.gaps.is_empty() {
                view! { <p>{t!("limits-none-gaps")}</p> }.into_any()
            } else {
                view! {
                    <p>{t!("limits-gaps-intro")}</p>
                    {limits.gaps.into_iter().map(|gap| view! {
                        <section class="gap" aria-label=gap.title.clone()>
                            <h4>{gap.title.clone()}" ("{t!("limits-count", count = gap.count)}")"</h4>
                            <p>{gap.meaning.clone()}</p>
                            {next_step_view(gap.next.clone())}
                            <Disclosure label=t!("limits-examples")>
                                <ul class="untrusted">
                                    {gap.examples.iter().map(|(seq, details)| view! {
                                        <li>{t!("limits-first", seq = *seq)}": "{display_text(details, 300).text}</li>
                                    }).collect_view()}
                                </ul>
                            </Disclosure>
                        </section>
                    }).collect_view()}
                }
                .into_any()
            }}

            <h3 id="refused-heading">{t!("limits-heading-refused")}</h3>
            {if limits.refusals.is_empty() {
                view! { <p>{t!("limits-none-refused")}</p> }.into_any()
            } else {
                view! {
                    <p>{t!("limits-refused-intro")}</p>
                    <div class="table-wrap" tabindex="0" role="region" aria-labelledby="refused-heading">
                        <table class="simple-table compact">
                            <thead><tr>
                                <th scope="col">{t!("limits-member")}</th>
                                <th scope="col">{t!("limits-action")}</th>
                                <th scope="col" class="number">{t!("limits-times")}</th>
                                <th scope="col" class="number">{t!("limits-first-event")}</th>
                            </tr></thead>
                            <tbody>
                                {limits.refusals.into_iter().map(|r| view! {
                                    <tr>
                                        <th scope="row" class="untrusted">{display_text(&r.member, 200).text}</th>
                                        <td>{tr(&format!("refusal-{}", r.action), None)}</td>
                                        <td class="number">{fmt_num(r.count)}</td>
                                        <td class="number">{fmt_num(r.first_seq)}</td>
                                    </tr>
                                }).collect_view()}
                            </tbody>
                        </table>
                    </div>
                    {next_step_view(Some(NextStep { text: t!("next-allow"), setting: Some("policy.allow".into()) }))}
                }
                .into_any()
            }}

            <h3 id="dropped-heading">{t!("limits-heading-dropped")}</h3>
            {if limits.dropped.is_empty() {
                view! { <p>{t!("limits-none-dropped")}</p> }.into_any()
            } else {
                view! {
                    <p>{t!("limits-dropped-intro")}</p>
                    <div class="table-wrap" tabindex="0" role="region" aria-labelledby="dropped-heading">
                    <table class="simple-table compact">
                        <thead><tr>
                            <th scope="col">{t!("limits-category")}</th>
                            <th scope="col" class="number">{t!("limits-not-kept")}</th>
                        </tr></thead>
                        <tbody>
                            {limits.dropped.into_iter().map(|d| view! {
                                <tr><th scope="row">{d.category}</th><td class="number">{fmt_num(d.count)}</td></tr>
                            }).collect_view()}
                        </tbody>
                    </table>
                    </div>
                    {next_step_view(limits.dropped_next.clone())}
                }
                .into_any()
            }}
        }
        .into_any()
    }
}

#[derive(Clone, Copy, PartialEq)]
enum View {
    Text,
    Hex,
    Instructions,
}

/// Hex with offsets and printable characters, 16 bytes a line, like common hex viewers.
fn hex_lines(offset: u64, bytes: &[u8]) -> String {
    bytes
        .chunks(16)
        .enumerate()
        .map(|(i, chunk)| {
            let hex: Vec<String> = chunk.iter().map(|b| format!("{b:02x}")).collect();
            let ascii: String = chunk.iter().map(|&b| if (0x20..0x7f).contains(&b) { b as char } else { '.' }).collect();
            format!("{:08x}  {:<47}  {ascii}", offset + (i * 16) as u64, hex.join(" "))
        })
        .collect::<Vec<_>>()
        .join("\n")
}

#[component]
pub fn FilesPanel(visible: Signal<bool>) -> impl IntoView {
    let flow = flow();
    let files = RwSignal::new(None::<Result<Vec<Artifact>, String>>);
    let chosen = RwSignal::new(Vec::<String>::new());
    let viewing = RwSignal::new(None::<Artifact>);
    let view_as = RwSignal::new(View::Text);
    let loaded = RwSignal::new(Vec::<u8>::new());
    let total = RwSignal::new(0u64);
    let instructions = RwSignal::new(None::<Result<String, String>>);
    let status = RwSignal::new(String::new());
    let dialog = NodeRef::<leptos::html::Dialog>::new();
    let understood = RwSignal::new(false);

    // Load the list when the tab is first shown for a report.
    Effect::new(move |loaded_for: Option<Option<String>>| {
        let report = flow.report.with(|r| r.as_ref().map(|r| r.output_dir.clone()));
        if !visible.get() || report.is_none() {
            return loaded_for.flatten();
        }
        if loaded_for.flatten() != report {
            files.set(None);
            chosen.set(Vec::new());
            viewing.set(None);
            spawn_local(async move { files.set(Some(bridge::artifacts().await)) });
        }
        report
    });

    let load_more = move || {
        let Some(artifact) = viewing.get_untracked() else { return };
        let offset = loaded.with_untracked(Vec::len) as u64;
        spawn_local(async move {
            match bridge::artifact_page(&artifact.path, offset).await {
                Ok(page) => {
                    total.set(page.total);
                    loaded.update(|l| l.extend(page.bytes));
                }
                Err(message) => announcer().say(message),
            }
        });
    };
    let open_viewer = move |artifact: Artifact| {
        loaded.set(Vec::new());
        total.set(artifact.size);
        instructions.set(None);
        view_as.set(View::Text);
        viewing.set(Some(artifact.clone()));
        load_more();
        if artifact.kind == "class" {
            spawn_local(async move { instructions.set(Some(bridge::artifact_disasm(&artifact.path).await)) });
        }
        focus_id("viewer-heading".into());
    };

    let save_zip = move |_| {
        let paths = chosen.get_untracked();
        spawn_local(async move {
            match bridge::save_zip(&paths).await {
                Ok(true) => status.set(t!("files-saved-zip", count = paths.len())),
                Ok(false) => {}
                Err(message) => status.set(message),
            }
        });
    };
    let open_dialog = move |_| {
        understood.set(false);
        if let Some(dialog) = dialog.get() {
            let _ = dialog.show_modal();
            // Focus on Cancel: the safe choice is the default one.
            focus_id("as-is-cancel".into());
        }
    };
    let close_dialog = move || {
        if let Some(dialog) = dialog.get() {
            dialog.close();
        }
        focus_id("save-as-is-open".into());
    };
    let save_as_is = move |_| {
        let Some(artifact) = viewing.get_untracked() else { return };
        close_dialog();
        spawn_local(async move {
            match bridge::save_as_is(&artifact.path).await {
                Ok(true) => status.set(t!("files-saved-as-is", name = display_text(&artifact.name, 120).text)),
                Ok(false) => {}
                Err(message) => status.set(message),
            }
        });
    };

    let table = move || match files.get() {
        None => view! { <p>{t!("files-loading")}</p> }.into_any(),
        Some(Err(message)) => view! { <p>{message}</p> }.into_any(),
        Some(Ok(list)) if list.is_empty() => view! { <p>{t!("files-none")}</p> }.into_any(),
        Some(Ok(list)) => view! {
            <div class="table-wrap" tabindex="0" role="region" aria-labelledby="files-heading">
                <table class="simple-table compact">
                    <caption class="visually-hidden">{t!("files-caption")}</caption>
                    <thead><tr>
                        <th scope="col">{t!("files-select")}</th>
                        <th scope="col">{t!("files-name")}</th>
                        <th scope="col">{t!("files-kind")}</th>
                        <th scope="col" class="number">{t!("files-size")}</th>
                        <th scope="col">{t!("files-origin")}</th>
                        <th scope="col">{t!("files-sha256")}</th>
                        <th scope="col"><span class="visually-hidden">{t!("items-actions")}</span></th>
                    </tr></thead>
                    <tbody>
                        {list.into_iter().map(|artifact| {
                            let name = display_text(&artifact.name, 120).text;
                            let path = artifact.path.clone();
                            let path_for_check = artifact.path.clone();
                            let to_view = artifact.clone();
                            view! {
                                <tr>
                                    <td>
                                        <input type="checkbox" aria-label=t!("files-select-label", name = name.clone())
                                            prop:checked=move || chosen.with(|c| c.contains(&path_for_check))
                                            on:change=move |ev| {
                                                let on = event_target_checked(&ev);
                                                let path = path.clone();
                                                chosen.update(|c| { c.retain(|p| *p != path); if on { c.push(path); } });
                                            } />
                                    </td>
                                    <th scope="row" class="untrusted">{name.clone()}</th>
                                    <td>{tr(&format!("files-kind-{}", artifact.kind), None)}</td>
                                    <td class="number">{fmt_size(artifact.size)}</td>
                                    <td class="untrusted">{display_text(&artifact.origin.clone().unwrap_or_default(), 200).text}</td>
                                    // The start of the hash is enough to tell files apart; the
                                    // viewer and the warning show it in full.
                                    <td class="hash">{format!("{}…", artifact.sha256.chars().take(12).collect::<String>())}</td> // not-ui-text: a shortened hash
                                    <td>
                                        <button type="button" aria-label=t!("files-view-label", name = name.clone()) on:click=move |_| open_viewer(to_view.clone())>
                                            {t!("files-view")}
                                        </button>
                                    </td>
                                </tr>
                            }
                        }).collect_view()}
                    </tbody>
                </table>
            </div>
        }
        .into_any(),
    };

    let content = move || {
        viewing.get()?;
        let text = match view_as.get() {
            View::Text => safe_lines(&String::from_utf8_lossy(&loaded.get())),
            View::Hex => hex_lines(0, &loaded.get()),
            View::Instructions => match instructions.get() {
                None => t!("files-loading"),
                Some(Ok(text)) => safe_lines(&text),
                Some(Err(message)) => message,
            },
        };
        let shown = loaded.with(Vec::len) as u64;
        let partial = view_as.get() != View::Instructions && shown < total.get();
        Some(view! {
            <pre class="file-view untrusted" tabindex="0" aria-labelledby="viewer-heading">{text}</pre>
            {(view_as.get() != View::Instructions).then(|| view! {
                <p role="status">{t!("files-showing", shown = shown, total = total.get())}</p>
            })}
            {partial.then(|| view! {
                <button type="button" on:click=move |_| load_more()>{t!("files-show-more", size = fmt_size(PAGE_BYTES))}</button>
            })}
        })
    };

    view! {
        <h3 id="files-heading">{t!("files-heading")}</h3>
        <p>{t!("files-intro")}</p>
        {table}
        <div class="buttons">
            <button type="button" class="primary" disabled=move || chosen.with(Vec::is_empty) aria-describedby="files-zip-hint" on:click=save_zip>
                {t!("files-save-zip")}
            </button>
        </div>
        <p class="hint" id="files-zip-hint">{t!("files-zip-hint")}</p>
        <p class="inline-status" role="status">{move || status.get()}</p>

        {move || viewing.get().map(|artifact| {
            let is_class = artifact.kind == "class";
            let name = display_text(&artifact.name, 120).text;
            view! {
                <section class="viewer" aria-labelledby="viewer-heading">
                    <h4 id="viewer-heading" tabindex="-1" class="untrusted">{t!("files-viewing", name = name.clone())}</h4>
                    <dl class="stats">
                        <dt>{t!("files-size")}</dt><dd>{fmt_size(artifact.size)}</dd>
                        <dt>{t!("files-sha256")}</dt><dd class="hash">{artifact.sha256.clone()}</dd>
                        <dt>{t!("files-origin")}</dt><dd class="untrusted">{display_text(&artifact.origin.clone().unwrap_or_default(), 300).text}</dd>
                    </dl>
                    <fieldset class="inline">
                        <legend>{t!("files-show-as")}</legend>
                        <div class="choice">
                            <input type="radio" name="view-as" id="view-as-text" prop:checked=move || view_as.get() == View::Text on:change=move |_| view_as.set(View::Text) />
                            <label for="view-as-text">{t!("files-as-text")}</label>
                        </div>
                        <div class="choice">
                            <input type="radio" name="view-as" id="view-as-hex" prop:checked=move || view_as.get() == View::Hex on:change=move |_| view_as.set(View::Hex) />
                            <label for="view-as-hex">{t!("files-as-hex")}</label>
                        </div>
                        {is_class.then(|| view! {
                            <div class="choice">
                                <input type="radio" name="view-as" id="view-as-instructions" prop:checked=move || view_as.get() == View::Instructions on:change=move |_| view_as.set(View::Instructions) />
                                <label for="view-as-instructions">{t!("files-as-instructions")}</label>
                            </div>
                        })}
                    </fieldset>
                    {content}
                    <div class="buttons">
                        <button type="button" id="save-as-is-open" on:click=open_dialog>{t!("files-save-as-is")}</button>
                    </div>
                </section>
            }
        })}

        <dialog node_ref=dialog class="alert" role="alertdialog" aria-modal="true" aria-labelledby="as-is-title" aria-describedby="as-is-warning"
            on:close=move |_| focus_id("save-as-is-open".into())>
            <h3 id="as-is-title">{t!("as-is-title")}</h3>
            <p id="as-is-warning">{t!("as-is-warning")}</p>
            {move || viewing.get().map(|a| view! {
                <dl class="stats">
                    <dt>{t!("files-name")}</dt><dd class="untrusted">{display_text(&a.name, 200).text}</dd>
                    <dt>{t!("files-kind")}</dt><dd>{tr(&format!("files-kind-{}", a.kind), None)}</dd>
                    <dt>{t!("files-sha256")}</dt><dd class="hash">{a.sha256.clone()}</dd>
                </dl>
            })}
            <div class="choice">
                <input type="checkbox" id="as-is-understood" prop:checked=move || understood.get() on:change=move |ev| understood.set(event_target_checked(&ev)) />
                <label for="as-is-understood">{t!("as-is-understood")}</label>
            </div>
            <div class="buttons">
                <button type="button" id="as-is-cancel" class="primary" on:click=move |_| close_dialog()>{t!("as-is-cancel")}</button>
                <button type="button" id="as-is-save" disabled=move || !understood.get() aria-describedby="as-is-save-hint" on:click=save_as_is>{t!("as-is-save")}</button>
            </div>
            <p class="hint" id="as-is-save-hint">{t!("as-is-save-hint")}</p>
        </dialog>
    }
}

#[component]
pub fn SettingsUsedPanel() -> impl IntoView {
    let flow = flow();
    move || {
        let Some(report) = flow.report.get() else { return ().into_any() };
        let effective = javabyte_gui_backend::config::render_document(&report.config);
        view! {
            <p>{t!("used-intro")}</p>
            <div class="buttons">
                <button type="button" class="primary" on:click=move |_| crate::settings::run_again_and_show(None)>{t!("used-run-again")}</button>
            </div>
            {report.config_text.clone().map(|text| view! {
                <h3 id="used-file-heading">{t!("used-file")}</h3>
                <pre class="command untrusted" tabindex="0" aria-labelledby="used-file-heading">{safe_lines(&text)}</pre>
            })}
            <h3 id="used-effective-heading">{t!("used-effective")}</h3>
            <p class="hint">{t!("used-effective-hint")}</p>
            <pre class="command untrusted" tabindex="0" aria-labelledby="used-effective-heading">{safe_lines(&effective)}</pre>
        }
        .into_any()
    }
}
