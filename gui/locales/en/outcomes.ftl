# How a run ended, in plain words, for people who have not read the manual.
# Each message is named after the outcome the command-line tool reports.

outcome-completed = The sample ran to its end.
outcome-uncaught-exception = The sample stopped with an error it did not handle.
outcome-exit = The sample ended itself.
outcome-sleep-budget = The sample kept waiting, so the analysis moved on. It was probably waiting for an answer from a server.
outcome-silent-loop = Part of the sample kept looping without doing anything, so the analysis ended that part and went on. The report says where it was looping.
outcome-blocked = The sample waited for something that would never happen, so the analysis moved on.
outcome-instruction-limit = The analysis reached its instruction limit before the sample finished.
outcome-timeout = The analysis reached its time limit before the sample finished.
outcome-policy = The analysis stopped the sample because of a safety rule.
outcome-stopped = You stopped the analysis. Everything found until then is kept.
outcome-no-entry-point = There was nowhere to start: the sample names no main class. Choose where to start in the settings.
outcome-config-error = The settings have a problem; nothing was run.
outcome-internal-error = The analysis tool itself failed. This is a fault in the tool, not something the sample did.
outcome-unknown = The analysis ended in a way this version of the app does not know.
