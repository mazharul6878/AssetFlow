# AssetFlow — Design Tokens (Aurora Light — Soft Depth Glass)

Version 1.1 · Derived from the supplied specification (v1.0) and the two reference mockups. Machine-readable copies are in `tokens/` inside `AssetFlow_UI_Assets_Organized.zip` (`assetflow_tokens.css`, `af_colors.xml`, `AssetFlowColors.kt`, `tokens_colors.json`).

## 0. What changed versus the supplied specification

The supplied palette is kept as the visual starting point. Contrast was measured on every text/background pair, and the following was found and corrected. Nothing else in the visual direction changed.

| Supplied token | Problem | Resolution in this document |
|---|---|---|
| `primary` `#147DFF` with white label | 3.88:1, below 4.5:1 for 16 sp text | Brand blue stays for icons, selection, progress and focus. Button fills use `primaryAction` `#0F6DF2` → `primaryDeep` `#0758D6` (4.68:1 to 6.22:1). |
| `primary` as link text | 3.88:1 on white | New `primaryText` `#0B63D9` (5.52:1). |
| `textSecondary` `#61718F` | 4.24:1 on `backgroundTint` | `#566684` (4.98:1 on tint, 5.79:1 on white). |
| `mint`, `amber`, `rose` as text | 2.13, 1.76 and 3.21:1 on white | Kept for graphics only. Text uses `successText`, `warningText`, `dangerText` on tinted `*Soft` fills. |
| Approve / Return buttons (green / red in mockup) | white label fails on `#20C997` / `#F05D7A` | `successAction` `#0E7C5C`, `dangerAction` `#C9304F` (5.18 and 5.22:1). |
| `iconMuted` `#8092B4` | 3.14:1 | Allowed for icons only (3:1 non-text rule). Never for text. |
| (missing) | Unselected checkbox/radio edges and the boundary of required controls need 3:1 | New `borderControl` `#7A8EB4` (3.30:1). `borderSoft` stays decorative. |

## 1. Colour tokens

| Token | Hex | Use |
|---|---|---|
| `background` | `#F6F8FF` | Screen background top |
| `backgroundTint` | `#E7EEFF` | Screen background bottom / ambient tint |
| `surface` | `#FFFFFF` | Cards, sheets, form surfaces |
| `surfaceInset` | `#F3F7FF` | Input and inset-tile fill |
| `primary` | `#147DFF` | Brand blue: selection, progress, icons, focus ring |
| `primaryAction` | `#0F6DF2` | Primary button gradient start (white label 4.5:1+) |
| `primaryDeep` | `#0758D6` | Primary button gradient end / pressed |
| `primaryText` | `#0B63D9` | Links and blue text on light surfaces |
| `primarySoft` | `#E7F1FF` | Selected-row / selected-chip tint |
| `sky` | `#65C9FF` | Soft highlight, ambient glow |
| `cyan` | `#24C4EC` | Logo cyan facet, chart accent |
| `violet` | `#7768F8` | Secondary accent, avatars, logo dot |
| `violetText` | `#5B4BE0` | Violet text on violetSoft |
| `violetSoft` | `#ECEAFF` | Violet tint |
| `success` | `#20C997` | Success graphics, progress, check glow (not for text) |
| `successAction` | `#0E7C5C` | Approve button fill (white label) |
| `successText` | `#0B7A5A` | Success text on successSoft/white |
| `successSoft` | `#E3F8F0` | Success tint |
| `warning` | `#FFB547` | Pending graphics (not for text) |
| `warningText` | `#8A5300` | Pending text on warningSoft/white |
| `warningSoft` | `#FFF2D9` | Pending tint |
| `danger` | `#F05D7A` | Failure graphics (not for text) |
| `dangerAction` | `#C9304F` | Return / Reject / destructive fill (white label) |
| `dangerText` | `#C42746` | Error text on dangerSoft/white |
| `dangerSoft` | `#FDE8EC` | Error tint |
| `textPrimary` | `#172A4A` | Titles and primary content |
| `textSecondary` | `#566684` | Supporting text, metadata |
| `textPlaceholder` | `#5F6F8C` | Input placeholder |
| `iconMuted` | `#8092B4` | Secondary icons (non-text, 3:1+) |
| `borderSoft` | `#DDE6F7` | Decorative card/divider outline |
| `borderControl` | `#7A8EB4` | Unselected checkbox/radio edge, required control boundaries |

Glass surface: `rgba(255,255,255,0.82)` with a 1 dp `borderSoft` outline and a 1 dp inner top highlight `rgba(255,255,255,0.9)`. Real background blur is optional; on devices or WebViews where blur is unavailable or slow, use the plain 0.92-alpha white instead. Never rely on blur for legibility.

Gradients:
- Screen: vertical `background` → `backgroundTint`, plus the optional ambient layer (§3).
- Primary button: vertical `primaryAction` → `primaryDeep`.
- Avatar / client monogram tiles: `violet` → `primary` at 135°, white initials (use `violetText`-dark initials on `violetSoft` tiles if lighter tiles are preferred).

## 2. Contrast audit (measured)

| Pair | Ratio | Result |
|---|---|---|
| `textPrimary` on `surface` | 14.32:1 | Pass AA text |
| `textPrimary` on `backgroundTint` | 12.32:1 | Pass AA text |
| `textSecondary` on `surface` | 5.79:1 | Pass AA text |
| `textSecondary` on `background` | 5.45:1 | Pass AA text |
| `textSecondary` on `backgroundTint` | 4.98:1 | Pass AA text |
| `textPlaceholder` on `surfaceInset` | 4.73:1 | Pass AA text |
| `primaryText` on `surface` | 5.52:1 | Pass AA text |
| `primaryText` on `background` | 5.21:1 | Pass AA text |
| `primaryText` on `primarySoft` | 4.85:1 | Pass AA text |
| `surface` on `primaryAction` | 4.68:1 | Pass AA text |
| `surface` on `primaryDeep` | 6.22:1 | Pass AA text |
| `successText` on `successSoft` | 4.80:1 | Pass AA text |
| `warningText` on `warningSoft` | 5.71:1 | Pass AA text |
| `dangerText` on `dangerSoft` | 4.81:1 | Pass AA text |
| `violetText` on `violetSoft` | 5.04:1 | Pass AA text |
| `surface` on `successAction` | 5.18:1 | Pass AA text |
| `surface` on `dangerAction` | 5.22:1 | Pass AA text |
| `iconMuted` on `surface` | 3.14:1 | Pass 3:1 (non-text/large only) |
| `borderControl` on `surface` | 3.30:1 | Pass 3:1 (non-text/large only) |
| `primary` on `surface` | 3.88:1 | Pass 3:1 (non-text/large only) |
| `primary` on `background` | 3.66:1 | Pass 3:1 (non-text/large only) |

Rule: status is never communicated by colour alone. Every badge carries a text label (Submitted, Draft, Returned, Approved, Pending, Failed) and, where room allows, an icon.

## 3. Backgrounds

| Token | File | Use |
|---|---|---|
| `bg_aurora` | `bg_aurora_1440x2560.png` | Default for Home, Review, Control screens |
| `bg_welcome` | `bg_welcome_1440x2560.png` | Welcome and Role Selection only (stronger violet/cyan glow) |
| `bg_calm` | `bg_calm_1440x2560.png` | Long forms and lists: Counting Form, Asset Search, Field Mapping |

Rules: draw the vertical gradient in code first; the image is an optional decorative layer behind content, scaled `centerCrop`, never behind a text-bearing component. Do not tile. The supplied backgrounds contained grey halos around the glow shapes (they read as dirty rings against the reference); the replacements in this package are regenerated without them.

## 4. Typography

Typeface: **Inter** (variable, bundled or via Google Fonts). If the project already ships another modern sans, keep it and map these roles to it. Use tabular numerals for counts, dates and asset numbers.

| Role | Size / line | Weight | Used for |
|---|---|---|---|
| `display` | 28 / 34 sp | 700 | Welcome title, success title |
| `title` | 22 / 28 sp | 600 | Screen titles, greeting name |
| `section` | 17 / 24 sp | 600 | Section headers (Recent Activity, Today's Progress) |
| `kpi` | 24 / 28 sp | 700 | Dashboard counts |
| `body` | 15 / 22 sp | 400 | Default content |
| `bodyStrong` | 15 / 22 sp | 600 | Asset numbers, row titles |
| `bodySmall` | 14 / 20 sp | 400 | Secondary lines, field values in lists |
| `label` | 16 / 20 sp | 600 | Button labels |
| `caption` | 12 / 16 sp | 500 | Metadata, badge text, timestamps. Minimum size in the app. |

Sentence case everywhere. No all-caps labels. Letter spacing 0 except `display` (−0.2). Allow text to wrap or truncate with ellipsis; never clip.

## 5. Shape, depth, spacing

| Token | Value |
|---|---|
| `radiusCard` | 24 dp (hero and primary cards); `radiusCardSmall` 18 dp (list rows, KPI tiles) |
| `radiusControl` | 14 dp (inputs, dropdowns, search, segmented control) |
| `radiusButton` | 16 dp |
| `radiusPill` | 999 dp (chips, badges, progress track) |
| Spacing scale | 4, 8, 12, 16, 20, 24, 32, 40, 48 dp |
| Screen padding | 20 dp horizontal (16 dp on widths below 360 dp) |
| Card padding | 16 dp (20 dp for hero cards) |
| Section gap | 24 dp; item gap in a list 12 dp |

Elevation (blue-tinted, never black):

| Level | Use | Shadow |
|---|---|---|
| 0 | Page | none |
| 1 | List rows, inputs | `0 1 2 rgba(35,76,170,.06)` + `0 3 8 rgba(35,76,170,.06)` |
| 2 | Cards, KPI tiles | `0 2 4 rgba(35,76,170,.06)` + `0 8 20 rgba(35,76,170,.09)` |
| 3 | Bottom bar, sheets, FAB | `0 4 8 rgba(35,76,170,.08)` + `0 16 32 rgba(35,76,170,.12)` |
| CTA | Primary button | `0 6 16 rgba(20,125,255,.32)` |

On Android, `Modifier.shadow` uses grey ambient shadows; set `ambientColor` and `spotColor` to `rgba(35,76,170,.18)` (API 28+) or draw the shadow as a layered rounded rectangle.

## 6. Component specifications

**Primary button** — height 52 dp (min 48), full width by default, `radiusButton`, vertical gradient `primaryAction`→`primaryDeep`, white `label`, CTA shadow, 1 dp inner top highlight `rgba(255,255,255,.28)`. States: pressed = fill `primaryDeep` + scale 0.98; disabled = `#B9CBEA` fill, `#FFFFFF` label at 100%, no shadow, and a visible reason nearby; loading = label replaced by 20 dp white spinner, width unchanged, taps ignored; error = unchanged button, error message above it.

**Secondary button** — `surface`, 1 dp `borderSoft`, `textPrimary` `label`, elevation 1. Pressed = `primarySoft` fill. Used for Save Draft, Retake, Cancel.

**Destructive / review buttons** — Return and Reject use `dangerAction`; Approve uses `successAction`; same geometry as primary; white label.

**Text input** — height 52 dp (multi-line min 96), `surfaceInset` fill, 1 dp `borderSoft`, `radiusControl`. Persistent label above the field in `bodySmall` (14 sp, weight 500, `textPrimary`); required fields append a visible `*` and are announced as "required". Placeholder `textPlaceholder`. Focus: 2 dp `primary` outline + 4 dp `rgba(20,125,255,.16)` halo. Error: 2 dp `dangerText` outline, `dangerText` message with an alert icon below the field, `aria-invalid`/`error` semantics. Password: trailing eye / eye-off toggle, 48 dp target. Keyboard types: email → email; asset number → text, capitalised, no autocorrect; remarks → sentences.

**Dropdown** — same shell as input with trailing `chevron-down`; opens a bottom sheet (radius 28 dp top corners) when more than 7 options, otherwise an anchored menu.

**Checkbox / radio / switch** — 24 dp visual in a 48 dp target. Unchecked edge `borderControl` 1.5 dp; checked fill `primary` with white check. Radio checked = `primary` ring with 10 dp inner dot. Label is part of the target.

**Segmented control / tabs** (My Submissions | Drafts, Pending | All, Pending | Approved | Returned) — `surfaceInset` track, `radiusPill`, selected segment `primary`-gradient pill with white text 14 sp/600 (use `primaryDeep` fill if the gradient is not available); unselected `textSecondary`. Min height 44 dp.

**Chips and status badges** — height 24 dp, horizontal padding 10 dp, `caption` 12 sp/600, `radiusPill`, tinted fill + matching `*Text` colour:

| Status | Fill | Text | Icon |
|---|---|---|---|
| Submitted / Info | `primarySoft` | `primaryText` | `check` |
| Draft | `violetSoft` | `violetText` | `edit` |
| Pending | `warningSoft` | `warningText` | `clock` |
| Returned | `warningSoft` | `warningText` | `retake` |
| Approved / Counted | `successSoft` | `successText` | `check-circle` |
| Rejected / Failed | `dangerSoft` | `dangerText` | `x-circle` |

The mockup shows Draft in solid blue and Returned in solid orange with white text. Those fail contrast at 12 sp, so the tinted treatment above replaces them. Rejected is a defined status in the spec but is not drawn in the mockup, so it is specified here for consistency.

**Cards** — `surface` or glass, `radiusCard`/`radiusCardSmall`, 1 dp `borderSoft`, elevation 2, padding 16 dp. A card that is tappable shows pressed overlay `primarySoft` at 60% and has the whole card as its target.

**KPI tile** — `radiusCardSmall`, tinted fill (blue `primarySoft`, green `successSoft`, amber `warningSoft`, rose `dangerSoft`), 36 dp icon in white rounded tile, `kpi` number, `caption` label in `textSecondary`, optional trailing chevron when it navigates.

**App bar** — 56 dp, transparent over the gradient, title `title` 20–22 sp/600 `textPrimary`, 48 dp back / menu / bell / search icons (24 dp glyphs, `textPrimary`). Elevates to a 1 dp `borderSoft` bottom line when content scrolls under it.

**Bottom navigation** — height 64 dp plus system inset, `surface`/glass, elevation 3, top corner radius 24 dp. Admin: Home, Resources, Review, Control. Auditor: Home, Counting, Activity, Profile. Selected item: `primary` icon and label, 32×20 dp `primarySoft` indicator pill behind the icon. Labels always shown, `caption`. Count badge (e.g., pending reviews): 18 dp `dangerAction` circle, white 12 sp number.

**Icons** — 24 dp default, 20 dp inline in rows, 32–36 dp inside 44–48 dp tinted tiles (`radius` 14 dp), stroke 1.75 at 24 dp. Default colour `primary`; secondary `iconMuted`; on-primary `surface`. One family only (this set). Decorative icons carry no semantics; interactive icons need a content description.

**Progress ring (Today's Progress)** — 120 dp, 12 dp stroke, round caps, track `primarySoft`, arcs `success` (counted), `warning` (pending), `violet` (drafts); centre value `kpi` + `caption` label; the legend beside it repeats the numbers as text, so colour is not the only carrier.

**Step progress bar (Counting Form)** — 6 dp pill track `primarySoft`, fill `primary`, "1 / 5" `caption` at right. Only render this if the approved counting form is actually multi-step (see spec §9).

## 7. Interaction states and feedback

| State | Treatment |
|---|---|
| Loading | Skeleton blocks (`surfaceInset` → `primarySoft` shimmer, 1.2 s) matching the final layout; spinner only inside buttons |
| Empty | 48 dp `inbox`/`search` icon in a 72 dp `primarySoft` circle, one-line statement of what is empty, one action ("Clear filters", "Start counting") |
| Error | Inline card with `dangerSoft` fill, alert icon, what failed and what to do; "Try again" secondary button. No modal for recoverable errors. |
| Success | Inline confirmation or the Submission Success screen; no stacked toasts |
| Permission denied | Inline `primarySoft` info card, lock icon, plain reason ("Your account has no client assigned yet"), action ("Request access") |
| Offline / sync failed | `cloud-off` icon, "Saved on this device. Will sync when you're online." with Retry |
| Disabled | 100% opacity label on `#B9CBEA` fill; show why next to the control |

Motion: 150–250 ms ease-out for press, selection and sheet transitions; no looping or decorative motion; honour the system "remove animations" setting (disable shimmer, confetti, scale).

## 8. Responsive and accessibility rules

- Layouts use constraints and weights, never fixed coordinates. Test at 320 dp, 360 dp, 411 dp wide and at 200% font scale; content scrolls vertically rather than clipping.
- On widths ≥ 600 dp keep a 560 dp maximum content column centred on the gradient; lists may become two columns at ≥ 840 dp.
- All interactive targets ≥ 48 × 48 dp with ≥ 8 dp between neighbours.
- Selected state is conveyed by outline, check mark and an accessibility "selected" state, not colour alone.
- Every icon-only control has a content description; decorative images are hidden from accessibility services.
- Errors are programmatically associated with their fields and announced.
- Follow the system light/dark setting only if a dark theme is implemented. This kit defines Light only; do not auto-generate a dark theme by inverting these values.
