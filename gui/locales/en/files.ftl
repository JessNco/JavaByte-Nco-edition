## Saved copies of files a sample produced.

# Inside each zip, in README-WARNING.txt.
zip-warning = WARNING: these files were produced by a sample analysed with JavaByte. They may be malware. Do not open or run them outside an isolated analysis machine.
zip-run = From the analysis in: { $folder }

## The report's Files tab.

files-heading = Files this run saved
files-intro = Files the sample wrote, code it created while running, and data it sent. They may be malware: the app never opens or runs them.
files-caption = Files this run saved
files-loading = Loading…
files-none = This run saved no files.
files-select = Select
files-select-label = Select { $name }
files-name = Name
files-kind = Kind
files-size = Size
files-origin = Came from
files-sha256 = SHA-256
files-view = View
files-view-label = View { $name }
files-kind-dropped = A file it wrote
files-kind-class = Code it created
files-kind-network = Data it sent
files-kind-other = Other file
files-save-zip = Save selected as a password zip…
files-zip-hint = The zip is encrypted with the password infected, as malware archives usually are. It also holds each file's SHA-256 and a warning. Select files in the table first.
files-saved-zip = { $count ->
        [one] 1 file saved in a password zip.
       *[other] { $count } files saved in a password zip.
    }
files-viewing = Viewing { $name }
files-show-as = Show as
files-as-text = Text
files-as-hex = Hex
files-as-instructions = Instructions (bytecode)
files-showing = Showing { $shown } of { $total } bytes.
files-show-more = Show { $size } more
files-save-as-is = Save as is…
files-saved-as-is = { $name } saved as is.

## The warning before saving a file as is.

as-is-title = Save this file without protection?
as-is-warning = This file was created by the sample. It may be malware. Opening it may infect this computer. A password zip is the safe way to keep it.
as-is-understood = I understand this file may be harmful
as-is-cancel = Cancel
as-is-save = Save as is
as-is-save-hint = Available once the box above is ticked.

## The report's Settings used tab.

used-intro = The settings this run used. Run again with changes opens them in Settings for the same sample.
used-run-again = Run again with changes
used-file = The settings file of this run
used-effective = The settings as the tool read them
used-effective-hint = Every option, with defaults filled in.
