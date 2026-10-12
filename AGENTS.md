# AI App Design and Quality Instructions
Read the README, existing code, and project-specific constraints before edits. Critically assess feature ideas: prioritize simplicity, real usefulness, reliability, performance, and accessibility over visual decoration or feature bloat.
## Design
Consult official Google Material 3 and Android app-quality documentation; use Mobbin as optional inspiration (not proof of quality), and Figma for editable designs only when authorized/connected. Consistent spacing, visual hierarchy, typography, contrast and clear labels; aim for >=48dp Android tap targets. Test status/navigation-bar insets, keyboard, portrait/landscape, tablet layouts, large text and screen-reader semantics. Ensure primary actions are easy to find; show real loading, empty, permission, offline, error and retry states.
## Functionality
Preserve existing features, user state, settings, app identity, signing/update compatibility and storage. Use migrations for schema changes. Confirm destructive actions, avoid credential exposure, don't ship keys or private data, handle retries/cancellation and process recreation. Favor small, reversible patches.
## Delivery
Before calling anything complete: run available builds, tests and lint, verify critical app flows, and report exactly what passed, failed or was not tested. For UI changes include screenshots or layout evidence when available, note regressions and tradeoffs. Do not claim all features tested without evidence.
## External reference links
- https://m3.material.io/
- https://developer.android.com/docs/quality-guidelines/core-app-quality
- https://developer.android.com/guide/topics/ui/accessibility
- https://www.nngroup.com/articles/ten-usability-heuristics/
- https://mobbin.com/ (optional account)
- https://www.figma.com/ (optional linked account)
- https://context7.com/ (optional developer documentation)
See docs/APP_DESIGN_QUALITY.md.