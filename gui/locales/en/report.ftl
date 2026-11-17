## The report: the opening paragraph and the indicator lists.

# The opening paragraph. Clauses are joined into one sentence: "The sample tried to start
# 2 programs, contacted 1 server and wrote 3 files."
summary-sentence = The sample { $actions }.
summary-nothing = The sample did nothing the analysis counts as notable.
summary-started = tried to start { $count ->
        [one] 1 program
       *[other] { $count } programs
    }
summary-contacted = contacted { $count ->
        [one] 1 server
       *[other] { $count } servers
    }
summary-looked-up = looked up { $count ->
        [one] 1 host name
       *[other] { $count } host names
    }
summary-wrote = wrote { $count ->
        [one] 1 file
       *[other] { $count } files
    }
summary-created = created { $count ->
        [one] 1 class
       *[other] { $count } classes
    } of hidden code
summary-loaded = loaded { $count ->
        [one] 1 native library
       *[other] { $count } native libraries
    }
summary-gaps = { $count ->
        [one] 1 part
       *[other] { $count } parts
    } could not be emulated faithfully, so the real behaviour may differ there.
summary-blocked = { $count ->
        [one] 1 Java feature it tried to use was
       *[other] { $count } Java features it tried to use were
    } refused.

## Indicator lists. Each has a title and a sentence saying what the list means.

indicator-processes = Programs it tried to start
indicator-processes-about = Command lines. Nothing was really started.
indicator-http = Web addresses it contacted
indicator-http-about = Nothing was really sent; answers came from the settings.
indicator-connections = Network connections
indicator-connections-about = Host and port of each connection it opened.
indicator-dns = Host names it looked up
indicator-dns-about = Often the first sign of its servers.
indicator-files = Files it wrote
indicator-files-about = On the pretend disk. Copies are saved with the report.
indicator-classes = Code it created while running
indicator-classes-about = Hidden stages unpacked at run time. Saved with the report.
indicator-native = Native libraries it loaded
indicator-native-about = Machine code outside Java.
indicator-decoded = Text it decoded
indicator-decoded-about = Text returned by its decoding methods: often addresses, keys and commands.
indicator-crypto = Decrypted data
indicator-crypto-about = The start of what its decryption produced.
indicator-blocked = Calls that were refused
indicator-blocked-about = Java features it tried to use that the analysis does not allow.

## The report screen.

report-heading = Report: { $name }
report-tabs = Report sections
report-tab-summary = Summary
report-tab-indicators = Indicators
report-tab-events = Events
report-in-short = In short
report-how-ended = How it ended
report-details = Details
report-numbers = Numbers
report-events-recorded = Events recorded
report-gaps = Parts not emulated faithfully
report-dropped = Events not kept
report-dropped-none = None
# One category whose events hit the limit, e.g. "1,200 net".
report-dropped-item = { $count } { $category }
report-sha256 = Sample SHA-256
report-folder-heading = Where the results are
report-folder-intro = The full report, the trace and every file the sample wrote or created are in this folder:
report-copy-folder = Copy folder path
report-folder-warning = Files the sample wrote may be malware. Do not open them with other programs.
indicators-none = No indicators were recorded.
indicators-untrusted = Text in these lists comes from the sample. Invisible and control characters are shown as codes such as \x07.
indicators-nav = Indicator lists
# A list's title with how many items it has.
indicators-title-count = { $title } ({ $count })
indicators-shortened = { " " }… ({ $count } characters in full, in the report file)
indicators-copy = Copy the list
indicators-copy-label = Copy the list: { $title }
