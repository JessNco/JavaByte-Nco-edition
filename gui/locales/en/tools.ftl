## Tools: the built-in policy and every option with its default.

tools-heading = Tools
tools-intro = Reference views of how JavaByte works. Nothing here changes a setting.
tools-tabs = Reference views
tools-tab-policy = Policy
tools-tab-defaults = Options and defaults
tools-loading = Loading…
tools-no-match = Nothing matches. Try fewer or shorter words.
tools-search-hint = Shows rows containing every word you type, in any column.
tools-count-all = { $count ->
        [one] 1 row.
       *[other] { $count } rows.
    }
tools-count-matching = { $shown } of { $total } rows match.
tools-policy-intro = When a sample calls into Java's own libraries, the call runs for real only if a rule here allows it. Everything else is imitated or blocked, and reported. A block always wins over an allow.
tools-policy-search = Search the policy
tools-policy-caption = Policy rules, in the order JavaByte reads them
tools-policy-rule = Rule
tools-policy-effect = Effect
tools-policy-section = Group
tools-policy-why = Why
tools-policy-allowed = Allowed
tools-policy-blocked = Blocked
tools-defaults-intro = Every setting an analysis can have, with the value it takes when the settings do not mention it. The same list is printed by 'javabyte defaults' and 'javabyte schema'.
tools-defaults-search = Search the options
tools-defaults-caption = Options, with their defaults
tools-defaults-option = Option
tools-defaults-default = Default
tools-defaults-type = Type
tools-defaults-meaning = Meaning
tools-defaults-none = (none)
