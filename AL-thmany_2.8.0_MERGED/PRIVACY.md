# Privacy

AL-thmany has no `INTERNET` permission and does not send invitation links, diagnostics, account data, or reports to any server.

Imported links, session state, result codes, and automation state are stored locally in the app's private SQLite database and SharedPreferences. The optional Smart Runtime diagnostic journal is also stored only in the app's private files directory, rotates at a small bounded size, and records engine metadata such as decision type, confidence, stage, link position/id, and watchdog state. It does **not** intentionally record chat message contents, contact names, invite URLs, or ordinary conversation text. Users can clear the diagnostic journal from Settings.

The optional Accessibility service is disabled by default. It requires activation in Android settings and a separate user-started run. Its package filter is scoped to supported WhatsApp personal/Business/cloned packages and the Android/Samsung resolver surfaces needed for explicit Dual Messenger selection.

The service inspects the active Accessibility tree to locate invitation preview, Join, Request to Join, Community Join, confirmation, safe close controls, loading states, conversation evidence, known terminal states, and restriction screens. Destructive labels/resource IDs such as Cancel Request, Leave, Report, Delete, and Remove take precedence over positive action matching and are blocked.

A guarded coordinate gesture fallback may be used only after semantic matching when a verified invitation control is not directly clickable. The service does not type or send chat content, read notifications, access contacts, or process ordinary message bodies.

A local session may contain up to **5,000 unique invitation links** supplied by the user. Each explicit run is capped at **80 links**; remaining links stay queued for another explicit continuation. The same invitation is not automatically relaunched after it has been processed/skipped. The app stops on WhatsApp restriction screens and does not attempt to bypass platform restrictions. It does not automatically join every subgroup inside a WhatsApp community.

Android backup and device-transfer extraction are disabled. Notification permission, when granted, is used only for local progress and control notifications.
