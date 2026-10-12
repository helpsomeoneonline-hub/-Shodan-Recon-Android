# App UX/UI and Functional Quality Checklist

Guidance for future changes, not an automatic redesign.

## Plan
- Read the README and audit the present interface, existing features, pain points and user journey.
- Research official Material 3 and accessibility guidance, and optional Mobbin examples; distinguish visual inspiration from validated UX.
- Compare two designs, critique extra complexity, then implement the smallest change meeting the task.

## Validate
- Primary action, navigation and Back behavior make sense.
- Contrast, font scaling, labels, touch targets (about 48dp), spacing and hierarchy are accessible.
- Status/navigation insets, on-screen keyboard, portrait, landscape and tablet layouts have no overlap.
- Loading, permissions, empty, error, offline, retry and cancellation states tell the truth.
- User settings/data persist; no regressions or destructive behavior without confirmation.
- No secret credentials leak to code or logs.
- Run builds, tests and lint; check real screen layouts if possible. Report exact evidence and untested scenarios.

## Review record
| Screen/flow | Problem | Severity | Proposed improvement | Test evidence | Outcome |
|---|---|---|---|---|---|
| | | | | | |

## Sources
- https://m3.material.io/
- https://developer.android.com/docs/quality-guidelines/core-app-quality
- https://developer.android.com/guide/topics/ui/accessibility
- https://www.nngroup.com/articles/ten-usability-heuristics/
- https://mobbin.com/
- https://www.figma.com/
- https://context7.com/

Mobbin, Figma and Context7 links are reference links, not a claim of active integration.