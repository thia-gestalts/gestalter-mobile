# Mobile source architecture

Sources under `src/main/java/thia/gestalter` are split into layers. A package may reference only
its own unit and the layers listed for it. `scripts/lint-layers.mjs` enforces this (`mise run lint`);
`scripts/check.sh` and `scripts/build-apk.sh` run it first.

| Layer | Package | Holds | May use |
|---|---|---|---|
| core | `core` | Records, notes, Queue, replica, sync, Olymp, crypto. Plain Java + Gson, **no Android SDK**, so it runs in host tests and can be ported to iOS. | — |
| platform | `android.platform` | Android OS adapters: files and keystore vault, HTTP transport, sync jobs, image picker and decoding. | core |
| ui | `android.ui` | Dumb widgets and theme: view builders, chips (`Flow`), dialogs, icons. No app logic. | generated `R` only |
| workspace | `android.workspace` | `GestalterApp` and `Workspace`: opens the profile, serializes local writes, runs sync, notifies observers, maps errors to messages. All callbacks run on the main thread. | core, platform |
| navigation | `android.navigation` | `Route`, `Screen`, `ScreenHost`: the screen concept. | core, ui, workspace |
| features | `android.features.<entity><action>` | Pieces used by more than one screen (`noteimages`, `notecard`). Features never use one another. | all layers above |
| screens | `android.screens.<name>` | One package per screen (`capture`, `notes`, `note`, `queue`, `account`), split into files as needed. Screens never use one another; they move with `ScreenHost.go(Route)`. | all layers above, features |
| shell | `android.shell` | `MainActivity`: frame, tab bar, back stack, screen factory, state saving, activity-result routing. | everything |

Put code that only one screen needs in that screen's package rather than in a feature.

## Screens and navigation

Capture, Notes, Queue and Account are tabs; each tab keeps its screen object while the activity
lives. A note editor (`Route.note(...)`, `Route.newNote()`) is pushed on top of the current tab.
Back pops the pushed editor, then returns to Capture. Before the shell leaves a screen it calls
`Screen.requestLeave`; the editor asks whether to discard unsaved changes. Pushing another editor
(e.g. from a related note) keeps the one below and its draft.

The shell rebuilds the frame on `render()`; a screen keeps its state in fields and fills the page
and action area in `build`. Workspace changes arrive through `Screen.changed()`.

## Notes

`Notes.Note` projects every note/v3 field. `Notes.Fields` is the editable copy the editor works on
and the input to `Replica.save`. A field is written only when it has content or already exists,
so Capture's `Replica.capture` stores just `{kind, id, text}` plus attachments. The reserved
`no-queue` tag is exposed as `noQueue` (Skip Queue). Zettelkasten placement links (`follows`,
`branches from`) are kept but not shown in the editor.
