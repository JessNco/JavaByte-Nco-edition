//! A run-settings form: the patterns the real config editor will use for every option.
//!
//! Errors follow the well-tested "error summary" pattern: on submit, a summary at the top lists
//! every problem as a link to its field and takes focus, and each field shows its own message,
//! linked to the input so screen readers read it with the field.

use crate::util::{announcer, focus_id};
use leptos::prelude::*;

#[derive(Clone, Debug, PartialEq)]
struct Problem {
    field: &'static str,
    message: String,
}

#[derive(Clone, Copy)]
struct Fields {
    sample: RwSignal<String>,
    entry: RwSignal<String>,
    class_name: RwSignal<String>,
    method: RwSignal<String>,
    timeout: RwSignal<String>,
    network: RwSignal<String>,
    shutdown_hooks: RwSignal<bool>,
}

fn check(f: &Fields) -> Vec<Problem> {
    let mut problems = Vec::new();
    let mut add = |field, message: &str| problems.push(Problem { field, message: message.to_string() });
    if f.sample.get_untracked().trim().is_empty() {
        add("sample", "Enter the path of the sample to analyse");
    }
    if f.entry.get_untracked() == "method" {
        if f.class_name.get_untracked().trim().is_empty() {
            add("class-name", "Enter the class that holds the method, for example com.example.Main");
        }
        if f.method.get_untracked().trim().is_empty() {
            add("method-name", "Enter the name of the method to start from, for example onInitialize");
        }
    }
    match f.timeout.get_untracked().trim().parse::<u32>() {
        Ok(seconds) if (1..=86_400).contains(&seconds) => {}
        _ => add("timeout", "Enter a time limit in whole seconds, from 1 to 86,400"),
    }
    problems
}

/// The same settings as a `javabyte run` command, so GUI and command-line users can share work.
fn command_line(f: &Fields) -> String {
    let mut parts = vec!["javabyte run".to_string(), quote(f.sample.get().trim())];
    match f.entry.get().as_str() {
        "method" => parts.push(quote(&format!(
            "--set=entryPoints=[{{type: method, class: {}, method: {}}}]",
            f.class_name.get().trim(),
            f.method.get().trim()
        ))),
        "clinit-sweep" => parts.push(quote("--set=entryPoints=[{type: clinit-sweep}]")),
        _ => {}
    }
    let timeout = f.timeout.get();
    if timeout.trim() != "120" {
        parts.push(quote(&format!("--set=limits.timeoutSeconds={}", timeout.trim())));
    }
    if f.network.get() == "offline" {
        parts.push("--set=network.mode=offline".into());
    }
    if !f.shutdown_hooks.get() {
        parts.push("--set=threads.runShutdownHooks=false".into());
    }
    parts.join(" ")
}

/// Quotes for a POSIX shell when needed. Windows users get the same text; phase 3 adds a
/// PowerShell form.
fn quote(text: &str) -> String {
    if !text.is_empty() && text.chars().all(|c| c.is_ascii_alphanumeric() || "-_./:=".contains(c)) {
        text.to_string()
    } else {
        format!("'{}'", text.replace('\'', r"'\''"))
    }
}

#[component]
pub fn RunSettings() -> impl IntoView {
    let fields = Fields {
        sample: RwSignal::new(String::new()),
        entry: RwSignal::new("main".into()),
        class_name: RwSignal::new(String::new()),
        method: RwSignal::new(String::new()),
        timeout: RwSignal::new("120".into()),
        network: RwSignal::new("respond".into()),
        shutdown_hooks: RwSignal::new(true),
    };
    let problems = RwSignal::new(Vec::<Problem>::new());
    let copied = RwSignal::new(String::new());

    let problem_for = move |field: &'static str| move || problems.with(|all| all.iter().find(|p| p.field == field).map(|p| p.message.clone()));
    let described_by = move |field: &'static str, has_hint: bool| {
        move || {
            let mut ids = Vec::new();
            if has_hint {
                ids.push(format!("{field}-hint"));
            }
            if problems.with(|all| all.iter().any(|p| p.field == field)) {
                ids.push(format!("{field}-error"));
            }
            ids.join(" ")
        }
    };
    let invalid = move |field: &'static str| move || problems.with(|all| all.iter().any(|p| p.field == field)).to_string();

    let on_submit = move |ev: leptos::ev::SubmitEvent| {
        ev.prevent_default();
        let found = check(&fields);
        let ok = found.is_empty();
        problems.set(found);
        if ok {
            announcer().say("These settings are valid. The equivalent command is shown below the form.");
        } else {
            focus_id("error-summary-title".into());
        }
    };

    let copy = move |_| {
        let text = command_line(&fields);
        let done = copy_to_clipboard(&text);
        copied.set(if done {
            "Command copied.".into()
        } else {
            "Copying is not available here. Select the command and press Control and C.".into()
        });
    };

    view! {
        <h2>"Run settings"</h2>
        <p>"A small part of the real settings, used to test forms. " <strong>"Required"</strong> " fields say so in their label."</p>
        <Show when=move || problems.with(|p| !p.is_empty())>
            <div class="error-summary" role="region" aria-labelledby="error-summary-title">
                <h3 id="error-summary-title" tabindex="-1">"There is a problem"</h3>
                <ul>
                    {move || {
                        problems
                            .get()
                            .into_iter()
                            .map(|p| {
                                let field = p.field;
                                view! {
                                    <li>
                                        <a
                                            href=format!("#{field}")
                                            on:click=move |ev| {
                                                ev.prevent_default();
                                                focus_id(field.to_string());
                                            }
                                        >
                                            {p.message}
                                        </a>
                                    </li>
                                }
                            })
                            .collect_view()
                    }}
                </ul>
            </div>
        </Show>
        <form novalidate on:submit=on_submit>
            <div class="field" class:has-error=move || problem_for("sample")().is_some()>
                <label for="sample">"Sample (required)"</label>
                <p class="hint" id="sample-hint">"Path to a .jar file, a .class file or a folder of classes. In the full app this is a file picker as well."</p>
                <ErrorText field="sample" message=Signal::derive(problem_for("sample")) />
                <input
                    id="sample"
                    type="text"
                    autocomplete="off"
                    spellcheck="false"
                    aria-describedby=described_by("sample", true)
                    aria-invalid=invalid("sample")
                    prop:value=move || fields.sample.get()
                    on:input=move |ev| fields.sample.set(event_target_value(&ev))
                />
            </div>
            <fieldset>
                <legend>"Where to start"</legend>
                <p class="hint" id="entry-hint">"The entry point is the place the analysis starts running the sample's code."</p>
                {[
                    ("main", "The main method named in the sample (usual)"),
                    ("method", "A method I name"),
                    ("clinit-sweep", "Every class's start-up code, to collect hidden text"),
                ]
                    .into_iter()
                    .map(|(value, label)| {
                        let id = format!("entry-{value}");
                        view! {
                            <div class="choice">
                                <input
                                    type="radio"
                                    name="entry"
                                    id=id.clone()
                                    aria-describedby="entry-hint"
                                    prop:checked=move || fields.entry.get() == value
                                    on:change=move |_| fields.entry.set(value.to_string())
                                />
                                <label for=id>{label}</label>
                            </div>
                        }
                    })
                    .collect_view()}
            </fieldset>
            <Show when=move || fields.entry.get() == "method">
                <div class="field" class:has-error=move || problem_for("class-name")().is_some()>
                    <label for="class-name">"Class (required)"</label>
                    <ErrorText field="class-name" message=Signal::derive(problem_for("class-name")) />
                    <input
                        id="class-name"
                        type="text"
                        autocomplete="off"
                        spellcheck="false"
                        aria-describedby=described_by("class-name", false)
                        aria-invalid=invalid("class-name")
                        prop:value=move || fields.class_name.get()
                        on:input=move |ev| fields.class_name.set(event_target_value(&ev))
                    />
                </div>
                <div class="field" class:has-error=move || problem_for("method-name")().is_some()>
                    <label for="method-name">"Method (required)"</label>
                    <ErrorText field="method-name" message=Signal::derive(problem_for("method-name")) />
                    <input
                        id="method-name"
                        type="text"
                        autocomplete="off"
                        spellcheck="false"
                        aria-describedby=described_by("method-name", false)
                        aria-invalid=invalid("method-name")
                        prop:value=move || fields.method.get()
                        on:input=move |ev| fields.method.set(event_target_value(&ev))
                    />
                </div>
            </Show>
            <div class="field" class:has-error=move || problem_for("timeout")().is_some()>
                <label for="timeout">"Time limit in seconds"</label>
                <p class="hint" id="timeout-hint">"The analysis stops after this long and keeps what it found. Default 120."</p>
                <ErrorText field="timeout" message=Signal::derive(problem_for("timeout")) />
                // A text input with a numeric keyboard, not type="number": number inputs change
                // value on scroll and arrow keys and are read inconsistently by screen readers.
                <input
                    id="timeout"
                    type="text"
                    inputmode="numeric"
                    class="input-short"
                    autocomplete="off"
                    aria-describedby=described_by("timeout", true)
                    aria-invalid=invalid("timeout")
                    prop:value=move || fields.timeout.get()
                    on:input=move |ev| fields.timeout.set(event_target_value(&ev))
                />
            </div>
            <fieldset>
                <legend>"Network"</legend>
                {[
                    ("respond", "Give made-up answers (the sample thinks it is online)"),
                    ("offline", "Act as if there is no network"),
                ]
                    .into_iter()
                    .map(|(value, label)| {
                        let id = format!("network-{value}");
                        view! {
                            <div class="choice">
                                <input
                                    type="radio"
                                    name="network"
                                    id=id.clone()
                                    prop:checked=move || fields.network.get() == value
                                    on:change=move |_| fields.network.set(value.to_string())
                                />
                                <label for=id>{label}</label>
                            </div>
                        }
                    })
                    .collect_view()}
                <p class="hint">"Nothing is ever really sent. Answers come from your settings."</p>
            </fieldset>
            <div class="choice">
                <input
                    type="checkbox"
                    id="shutdown-hooks"
                    prop:checked=move || fields.shutdown_hooks.get()
                    on:change=move |ev| fields.shutdown_hooks.set(event_target_checked(&ev))
                />
                <label for="shutdown-hooks">"Run the sample's shutdown code at the end"</label>
            </div>
            <button type="submit" class="primary">"Check settings"</button>
        </form>
        <h3 id="command-heading">"Same settings as a command"</h3>
        <p class="hint">"Paste this into a terminal to run the same analysis from the command line."</p>
        <pre class="command" aria-labelledby="command-heading" tabindex="0"><code>{move || command_line(&fields)}</code></pre>
        <button type="button" on:click=copy>"Copy command"</button>
        <p role="status" class="inline-status">{move || copied.get()}</p>
    }
}

#[component]
fn ErrorText(field: &'static str, message: Signal<Option<String>>) -> impl IntoView {
    view! {
        <Show when=move || message.get().is_some()>
            <p class="error-message" id=format!("{field}-error")>
                <span class="visually-hidden">"Error: "</span>
                {move || message.get().unwrap_or_default()}
            </p>
        </Show>
    }
}

fn copy_to_clipboard(text: &str) -> bool {
    // Reached through reflection: the Clipboard API is not in every web-sys build, and the
    // promise's result is not needed to tell the user something useful.
    let Some(window) = web_sys::window() else { return false };
    let navigator: wasm_bindgen::JsValue = window.navigator().into();
    let Ok(clipboard) = js_sys::Reflect::get(&navigator, &"clipboard".into()) else { return false };
    if clipboard.is_undefined() {
        return false;
    }
    let Ok(write) = js_sys::Reflect::get(&clipboard, &"writeText".into()) else { return false };
    let Ok(write) = wasm_bindgen::JsCast::dyn_into::<js_sys::Function>(write) else { return false };
    write.call1(&clipboard, &text.into()).is_ok()
}
