# AssetFlow — Google AI Studio UI Implementation Specification

**Theme:** Aurora Light — Soft Depth Glass · **Version:** 1.1 · **Scope:** visual layer only

Companion files (all in this delivery): `AssetFlow_Design_Tokens.md`, `AssetFlow_UI_Asset_Inventory.md`, `AssetFlow_UI_Assets_Organized.zip` (assets, tokens, README).

## How to use this document

1. Upload to Google AI Studio: the two reference images (`01_references/`), this file, `AssetFlow_Design_Tokens.md`, and the contents of `AssetFlow_UI_Assets_Organized.zip`.
2. Paste the **Master Prompt** (Part A) as your message.
3. Parts B–F are the detailed instructions the prompt tells AI Studio to follow. Keep them attached; do not paste them into the message.

If anything in this document disagrees with your authoritative functional specification (screen list, fields, roles, workflow, schema), **the functional specification wins** and the difference is recorded in the issue register, not silently resolved.

---

# Part A — Master Prompt (paste this)

~~~~
You are modifying the existing AssetFlow application. Apply the "Aurora Light — Soft Depth Glass" UI theme to every existing screen. This is a visual-layer change only. Do not rebuild the app and do not change business logic or backend behaviour.

Attached: two reference mockup images, AssetFlow_Google_AI_Studio_UI_Implementation_Spec.md (this brief), AssetFlow_Design_Tokens.md, and the AssetFlow_UI_Assets_Organized assets. Treat the images as the visual target and the two Markdown files as the exact specification. The mockups are flattened pictures: re-create them with real, responsive, native controls and real application data. Never use a mockup image as a screen background and never hard-code the sample names, counts, dates, asset numbers or statuses shown in them.

STEP 1 — Audit first, change nothing yet.
Inspect the project and report: (a) the platform and UI framework (native Android with Compose or XML views, or a web/PWA/hybrid app, and which libraries); (b) existing theme/style files, colour and typography definitions; (c) navigation graph and every screen/route; (d) shared components already present; (e) resource directories; (f) where authentication, Firestore access, Google Drive, evidence upload, Master Input workbook logic and role checks live. List every screen you found and map each to Part D of the spec. List screens in the spec that do not exist in the project; do not invent them as working features.

STEP 2 — Install the design system inside the existing architecture.
Create shared tokens from AssetFlow_Design_Tokens.md (colours, type scale, radii, spacing, elevation) using the project's existing mechanism. Copy the supplied assets to the correct resource locations (Part E). Use the vector icon set (SVG for web, VectorDrawable XML for Android) for all interactive icons; use raster PNG/WebP only for backgrounds, the hero illustration and launcher icons.

STEP 3 — Build reusable components once, then use them everywhere.
Screen background (gradient plus optional ambient image), Card, GlassCard, PrimaryButton, SecondaryButton, DestructiveButton/ApproveButton, TextField (label, helper, error, password toggle), Dropdown, Checkbox/Radio, SegmentedTabs, Chip, StatusBadge, KpiTile, ListRow, IconTile, AppBar, BottomNav, ProgressRing, StepBar (only if the counting form is multi-step), EmptyState, ErrorBanner, InfoBanner, Skeleton, ConfirmDialog/BottomSheet. Each must implement the states in Part C. Refactor screens to use them; remove one-off styling you replace.

STEP 4 — Restyle screens in this order, committing a coherent working state after each group:
(1) Welcome, Role Selection, Login, Registration, Forgot Password; (2) Client Selection, Fiscal Year Selection; (3) Admin Home, Auditor Home; (4) Asset Search, Asset Details, Counting Form, Photo Evidence, Submission Success, Activity; (5) Admin Review, Review Details; (6) Resources, Field Mapping, Control Panel, User Management, Access Requests; (7) Settings, Profile, Edit Profile, Change Password and any other existing screen, using the same tokens.

ROLE SELECTION (new dedicated screen after Welcome): exactly two selectable cards, Auditor and Admin. The reference mockup also shows an Owner card. Do NOT implement it: Owner is never self-selectable. Selecting a card is an entry-flow preference only. It grants nothing. The existing trusted backend decides the real role and permissions; the UI must never reveal or enable an Admin or Owner feature because of this choice. An authenticated Auditor with no client assignment may sign in but sees no client data; show the existing access-request flow as a calm inline state, not a popup.

HARD CONSTRAINTS
- Preserve all features, navigation targets, data, and integrations: Firebase Authentication, Firestore paths and security rules, trusted-backend authorization, Google Drive integration, Master Input workbook update logic, evidence storage, submission/approval states, audit history.
- Do not change the Firestore schema, security rules, OAuth configuration, Cloud Functions/server logic, or production configuration. Do not delete features or data. Do not deploy to production or run destructive operations. If a non-visual change is unavoidable, stop and explain it first.
- Keep the Counting Form fields, validation and the Field Mapping exactly as the approved functional spec defines them. Only restyle.
- Do not add or enable a control unless a real workflow behind it exists. Do not show decorative mock controls as functional.
- Use the colours, sizes and component states in the tokens file. Status must never be shown by colour alone. Interactive targets are at least 48 dp. Text is at least 12 sp and survives 200% font scale.
- Do not claim anything is done unless you built it and checked it runs. Mark placeholders as placeholders.

WHEN YOU FINISH, report: files changed; screens covered and screens not found; components added; assets used and where they were placed; anything you could not do and why; and a list of bugs or open questions, written as entries for the existing final bug-fixing register. Do not create a separate testing roadmap.

Begin with STEP 1 and show me the audit before editing any file.
~~~~

---

# Part B — Reconciliation of the references with the rules

The two mockups are the visual target, not an exact functional specification. These differences were found and decided as follows.

| # | Mockup shows | Decision |
|---|---|---|
| 1 | Role Selection has three cards: Auditor, Admin, **Owner** | Implement **two** cards only (Auditor, Admin), per the approved rule. No Owner icon is supplied on purpose. |
| 2 | Auditor card pre-selected, check mark top-right | Keep: pre-selecting the least-privileged option is a safe default. Continue is enabled. |
| 3 | Login drawn two ways: Email / Google tabs (flow image) and a stacked "Sign In — or — Continue with Google" (collage) | Use the **stacked** form. Show the Google button only if Google sign-in is configured. Show "Remember me" only if the approved login policy includes it. |
| 4 | Counting Form drawn as one scrolling form (collage) and as a stepped form with a "1 / 5" bar and "Asset Information" heading (flow image) | Follow the approved counting-form structure. If it is a single form, omit the step bar. Use the stepped variant only if the approved flow has steps. |
| 5 | Description shown as a dropdown on the Counting Form | Treat Description as read-only data from the asset register unless the approved schema says it is an input. |
| 6 | Home screens show a bottom bar with four items | Admin: Home, Resources, Review, Control. Auditor: Home, Counting, Activity, Profile. Keep, filtered to what each role is authorised to use. |
| 7 | Collage numbering has errors (Counting Form is "19", Admin Home is "5" and "7" in different images) | Ignore the numbering. Use the screen list in Part D. |
| 8 | Garbled glyphs and typos in the mockup ("Client ꞓꞅ Selection", "ABC a.2025", "30 Jun a2004", "aaeets") | Do not copy. Use the correct labels from this spec and real data. |
| 9 | Draft and Returned badges are solid colour with white text; Approve and Return are bright green and red | Use the contrast-safe treatments in the tokens file. |
| 10 | Theme row in Settings reads "System" | The kit defines Light only. Show the row only if a second theme actually exists. |
| 11 | Review list shows Pending, Approved, Returned tabs; the Rejected state appears on a badge only | Provide the tabs the approved workflow defines. Style Rejected like the other statuses. |
| 12 | Welcome shows "Version 1.0.0" and trust chips (Secure, Accurate, Organised) | Version text comes from the real build version. Keep the three chips as static copy. |

---

# Part C — Component states checklist (applies to every screen)

| Component | Default | Pressed | Focused | Disabled | Loading | Error / empty |
|---|---|---|---|---|---|---|
| PrimaryButton | gradient + shadow | `primaryDeep`, scale 0.98 | 2 dp `primary` ring, 3 dp offset | `#B9CBEA` fill + reason text | spinner, same width | error shown above button |
| SecondaryButton | white, `borderSoft` | `primarySoft` | ring | muted label, no shadow | spinner | — |
| TextField | `surfaceInset`, `borderSoft` | — | 2 dp `primary` + halo | 60% opacity, no cursor | — | `dangerText` outline + message + icon |
| Card (tappable) | elevation 2 | `primarySoft` overlay | ring | 60% opacity | skeleton | — |
| List | rows | — | row ring | — | 5–6 skeleton rows | EmptyState with action |
| StatusBadge | tinted + label | — | — | — | — | — |

Global state rules: loading uses skeletons that match the final layout; empty states explain what is empty and offer one action; errors are inline banners saying what failed and what to do, with a Try again action; permission-denied is an inline info banner with a lock icon and the approved request-access action; offline shows "Saved on this device. Will sync when you're online." with Retry. Avoid repeated popups and stacked toasts.

---

# Part D — Screen-by-screen implementation

Common to all screens unless stated: horizontal padding 20 dp; top app bar 56 dp transparent over the gradient; background `bg_aurora` (see per-screen exceptions); content scrolls under a status-bar inset; primary action sits in a sticky bottom area with 16 dp padding on forms; layouts are constraint-based and tested at 320, 360 and 411 dp widths and 200% font scale; tablet (≥ 600 dp) centres a 560 dp column.

Icon names below refer to `svg/icons/<name>.svg` and `ic_af_<name>` (Android).

### D1. Welcome / Splash
- **Background:** `bg_welcome`.
- **Hierarchy (centred, top to bottom):** logo mark 72 dp (`assetflow_mark`), wordmark "AssetFlow" `display` 32 sp / 700 in `primaryDeep`, tagline "Fixed Asset Verification Made Simple" `body` `textSecondary` on two lines, hero illustration (`welcome_hero_approx`, ~280 dp tall, never cropped), three trust chips in one row: `shield` Secure, `check-circle` Accurate, `clipboard-check` Organised (13 sp medium, icon 16 dp, `primaryText`), primary button "Get Started", then "Version x.y.z" in `caption` `textSecondary` taken from the build.
- **Actions:** Get Started → Role Selection. If the user already has a valid session, skip Welcome and go to the destination the existing logic chooses.
- **States:** none needed. Respect reduced motion; one 300 ms fade-in of the hero is the only animation allowed.
- **Responsive:** on short screens (< 640 dp tall) shrink the hero to 200 dp; never push the button off screen. Below 360 dp the trust chips wrap to two rows.

### D2. Role Selection (new, dedicated)
- **Background:** `bg_welcome`.
- **Hierarchy:** back arrow (48 dp target) → title "Select your role" `title` → subtext "Choose your role to continue" `bodySmall` `textSecondary` → two stacked role cards 20 dp apart → bottom "Continue" primary button.
- **Auditor card:** 88–96 dp tall, `radiusCard`, 48 dp violet-tinted icon tile with `clipboard-check`, title "Auditor" `section`, description "Perform asset counting and submit reports" `bodySmall` `textSecondary`, trailing `chevron-right` (hidden when selected, replaced by the check).
- **Admin card:** same geometry, amber-tinted icon tile with `shield` (the mockup's person-in-circle is replaced by `shield` to signal governance; `briefcase` is an acceptable alternative), title "Admin", description "Manage clients, review submissions and settings".
- **Selected:** 2 dp `primary` outline, 4 dp `rgba(20,125,255,.16)` halo, 24 dp filled `primary` check circle at top-right, `selected` accessibility state. **Unselected:** `surface`/glass, 1 dp `borderSoft`, elevation 1.
- **Actions:** Continue → Login (or Registration if the existing flow starts there). The choice is carried as a non-authoritative hint only.
- **Rules:** exactly two options; no Owner; selecting Admin never enables any Admin capability; backend decides the real role after authentication. If the backend role differs from the chosen card, follow the approved policy; if none is defined, route by the backend role and show a calm inline notice ("This account is an Auditor account.") and add the question to the issue register.
- **Responsive:** cards stack and grow with font scale; description wraps to three lines max.

### D3. Login
- **Background:** `bg_aurora`.
- **Hierarchy:** logo mark 56 dp, "Welcome back" `title`, "Sign in to your account" `bodySmall`; Email field (`mail` icon, email keyboard); Password field (`lock` icon, `eye`/`eye-off` toggle); row: checkbox "Remember me" (only if approved) and "Forgot password?" link in `primaryText`; PrimaryButton "Sign in"; divider with "or"; secondary button with Google logo "Continue with Google" (only if configured; use Google's official logo asset, not supplied here); footer "Don't have an account? Sign up" with "Sign up" in `primaryText`.
- **Actions:** Sign in → existing auth flow → Client Selection (or the existing destination); Sign up → Registration; Forgot → Forgot Password.
- **States:** field-level errors; invalid-credentials banner above the button (do not say which field was wrong); button loading; network error banner; Google cancelled shows nothing.
- **Responsive:** keyboard open: content scrolls and the focused field stays visible; button stays reachable.

### D4. Registration
- **Hierarchy:** back, mark, "Create account", "Join AssetFlow"; fields exactly as the approved plan: Full name (`user`), Email (`mail`), Password (`lock`), Confirm password; checkbox "I agree to the Terms & Privacy Policy" with both words as links (only if required); PrimaryButton "Create account"; footer "Already have an account? Sign in".
- **Rules:** do not add a role field. New accounts follow the approved policy; do not imply Admin access. Password rules shown as helper text under the field and validated live; mismatch error on Confirm.
- **States:** per-field errors, submitting, email-already-used banner with a Sign in link, success (follow existing flow; if email verification or approval is required, show an inline info card with the next step).

### D5. Client Selection
- **Hierarchy:** back, bell and search icons optional (bell only if notifications exist), title "Select client" `title`, subtext "Choose a client to continue", search field "Search clients", list of client cards (64 dp): 44 dp monogram tile (initials, `violet`→`primary` gradient, white 16 sp/700; use the client logo if one exists), client name `bodyStrong`, trailing `chevron-right`. Selected card (if the flow keeps a selection state): `primary` outline, check circle. Bottom "Next" button when selection is required.
- **Data:** only clients the authenticated user may access, from the existing source. No sample names.
- **States:** loading skeletons; empty search "No clients match 'x'" + Clear search; **unassigned Auditor:** replace the list with an InfoBanner (lock icon) "No client is assigned to your account yet." and the approved action "Request access"; pending request shows "Request sent on <date>. You'll be notified when it's approved."
- **Responsive:** list becomes two columns ≥ 840 dp.

### D6. Fiscal Year Selection
- **Hierarchy:** back, title "Select fiscal year", subtext "Choose the fiscal year to work with"; fiscal-year cards (72 dp): 44 dp `primarySoft` tile with `calendar`, "FY 2025 – 2026" `bodyStrong`, date range `caption` `textSecondary` (formatted from the data and the user's locale), trailing radio (selected: filled `primary` check circle; unselected: `borderControl` ring). Bottom "Next".
- **Rules:** keep the selected client visible as a small chip above the list ("ABC Corporation" from data) if the existing flow carries it. Persist client + fiscal-year context exactly as the app does today.
- **States:** loading, empty ("No fiscal years are set up for this client." + the approved admin action if the user can perform it), error.

### D7. Admin Home
- **Hierarchy:** app bar: `menu`, "AssetFlow" wordmark, `bell`, 36 dp avatar with initials (opens Profile); greeting "Good morning," + user name `title` (greeting changes with local time); context pill "<Client> | FY 2025 – 2026" with `chevron-down` (opens the client/fiscal-year switcher already in the app); 2×2 KPI grid: **Total Assets** (`package`, blue tint), **Counted** (`check-circle`, green), **Pending Review** (`clock`, amber), **Sync Failed** (`cloud-off`, rose), each with number `kpi` and label `caption`; "Recent Activity" `section` with rows: 40 dp round icon tile, title `bodyStrong` ("New submission"), subtitle `bodySmall` ("by <name>"), time `caption` ("2 hours ago"); bottom nav (Home selected).
- **Actions:** KPI tiles navigate to the relevant filtered list when such a destination exists, otherwise they are not tappable (no chevron). Recent Activity row opens the item. Bell opens notifications if implemented.
- **States:** KPI skeletons; zero activity → EmptyState "No activity yet"; Sync Failed > 0 uses `dangerText` count plus the label so it is not colour-only.
- **Responsive:** grid stays 2 columns down to 320 dp; numbers use tabular figures and `maxLines=1` with auto-shrink to 20 sp.

### D8. Auditor Home
- **Hierarchy:** app bar as D7 (avatar initials); greeting "Good morning," + name; context pill; 2×2 KPI grid: **My Assigned** (`clipboard`, blue), **Counted** (`check-circle`, green), **Pending** (`clock`, amber), **Drafts** (`edit`, rose-tint or violet-tint; use `violetSoft` for Drafts to match the badge system); "Today's Progress" card: ProgressRing (value = counted ÷ assigned using the app's real definition) + legend rows Counted / Pending / Drafts with counts; "Recent Activity" list; bottom nav (Home, Counting, Activity, Profile).
- **Actions:** Counting nav opens Asset Search/counting list; KPI tiles open the matching filtered list when one exists.
- **States:** no assignments → EmptyState with the approved explanation; the ring shows 0% with "No assets assigned"; offline shows the sync banner.

### D9. Asset Search
- **Background:** `bg_calm`.
- **Hierarchy:** back, title "Assets", optional more menu; search field "Search by asset no., name…" with trailing 48 dp square filter button (`filter`, `primary` fill, white icon); result rows (80 dp, `radiusCardSmall`): 56 dp thumbnail (real photo if present, otherwise an IconTile by asset class: IT Equipment `laptop`, Furniture `armchair`, Monitor `monitor`, Printer `printer`, default `package`), asset number `bodyStrong`, description `bodySmall`, "Class | Location" `caption` `textSecondary`, `chevron-right`.
- **Rules:** searchable fields are only those the approved plan permits. Barcode scan (`scan-barcode`) is added only if scanning exists.
- **States:** typing debounce with inline spinner; no results "No assets match 'x'" + Clear filters; loading skeleton rows; error banner; filter sheet (bottom sheet) listing only approved filters with Apply / Reset.

### D10. Asset Details
- **Hierarchy:** back, title "Asset details"; image area 160 dp tall `radiusCard` (photo or class illustration on `surfaceInset`); asset number `title`; description `body` `textSecondary`; two-column grid of info tiles (`surfaceInset`, `radiusControl`, label `caption` `textSecondary`, value `bodyStrong`): Asset class, Location, Department, Purchase date, Status, Current value. PrimaryButton "Start counting" pinned at bottom.
- **Rules:** show only fields in the approved register mapping and only those the role may see (e.g., hide Current value from roles that must not see it). Currency and dates formatted from data and locale, never hard-coded.
- **States:** loading skeleton; asset not found banner; already-counted asset → button becomes "Review count" or stays disabled with the approved explanation.

### D11. Counting Form
- **Background:** `bg_calm`.
- **Hierarchy:** back, title "Counting form"; (StepBar and "1 / 5" only if the approved form is stepped); section heading "Asset information" `section`; fields exactly as the approved schema. Mockup fields: Asset No. (read-only, `surfaceInset` at 70% with `lock` icon), Description (read-only unless the schema says otherwise), Condition* (dropdown), Location* (dropdown), Physical status* (radio group: Found / Not found), Remarks (multi-line), plus the Photo evidence entry row (opens D12) if evidence is part of the form. Sticky bottom bar: SecondaryButton "Save draft" + PrimaryButton "Submit", 12 dp apart, each ≥ 48 dp.
- **Rules:** required fields carry a visible `*` and "required" semantics; keep every field, validation rule and mapping from the approved plan; Submit enabled by the existing business rules only, and when disabled show which field is missing.
- **States:** field errors inline; autosave indicator ("Draft saved 10:42") in `caption`; submitting spinner on Submit; offline "Saved on this device"; leaving with unsaved changes → ConfirmDialog (Save draft / Discard / Keep editing).
- **Responsive:** buttons stack vertically below 340 dp or at ≥ 150% font scale.

### D12. Photo Evidence
- **Hierarchy:** back, title "Photo evidence"; large preview (4:3, `radiusCard`) of the selected photo; thumbnail strip: 64 dp thumbs with a 24 dp `x` remove button, plus a dashed-outline 64 dp "+" add tile (`plus`); two buttons: SecondaryButton "Retake" (`retake`) and PrimaryButton "Add photo" (`camera`).
- **Rules:** capture, retake, remove and the maximum photo count follow the approved plan. Show upload progress per thumbnail and a clear failed state with Retry. Evidence storage path and metadata logic are untouched.
- **States:** no photos → EmptyState "No photos yet" + Add photo; camera permission denied → InfoBanner with the explanation and "Open settings"; storage/upload error → ErrorBanner; pending upload offline → "Will upload when you're online".

### D13. Submission Success
- **Background:** `bg_aurora` with a soft `success` radial glow behind the badge.
- **Hierarchy (centred):** 120 dp circular badge: `success`→`#12B886` gradient disk, white `check` 56 dp, 1 dp white inner ring, soft glow; optional sparse confetti (≤ 10 particles, one-time 800 ms, disabled when reduced motion is on); title "Submission successful!" `display`; subtext "Asset count has been submitted for review." `body` `textSecondary`; summary card (rows with `caption` label left, `bodyStrong` value right): Asset No., Submitted at, Reference; PrimaryButton "Done".
- **Data:** reference and timestamp come from the real submission result. If no reference exists, omit that row.
- **Actions:** Done → Auditor Home (or the app's existing destination). Back is disabled so the form cannot be resubmitted.
- **Accessibility:** announce "Submission successful" on arrival.

### D14. Activity / My Submissions
- **Hierarchy:** back, title "Activity", filter or sort icon if supported; SegmentedTabs "My submissions" | "Drafts"; rows (84 dp): 56 dp thumbnail, asset no. `bodyStrong`, description `bodySmall`, date/time `caption`, StatusBadge top-right (Submitted, Draft, Returned, Approved) with icon and text; bottom nav (Activity selected).
- **Actions:** tap opens the submission (Returned or Draft → edit if allowed; others → read-only detail). A Returned row shows the reviewer's reason on the detail screen if the data exists.
- **States:** skeleton, empty ("No drafts" / "No submissions yet"), error.

### D15. Admin Review List
- **Hierarchy:** `menu` or back, title "Review", search "Search submissions…" with filter button; SegmentedTabs: the statuses defined by the plan (mockup: Pending, Approved, Returned; add Rejected if the plan defines it; scroll horizontally if more than three); rows (76 dp): 44 dp avatar (initials on `violet`→`primary`), auditor name `bodyStrong`, "120 assets" `bodySmall`, timestamp `caption`, StatusBadge; bottom nav (Review selected, with the pending-count badge when > 0).
- **States:** skeleton, empty per tab ("Nothing is waiting for review."), error.

### D16. Review Details
- **Hierarchy:** back, title "Review details"; asset header (56 dp thumb, asset no. `title`, description); key–value card (label `bodySmall` `textSecondary` left column, value `bodyStrong` right): Auditor, Submitted, Condition, Physical status, Location, Remarks; evidence strip (tappable thumbnails opening a full-screen viewer with pinch zoom); audit metadata (submitted by, timestamps, previous returns/approvals) if the plan includes audit history; sticky bottom actions: **Return** (DestructiveButton, `dangerAction`) and **Approve** (`successAction`), 12 dp apart.
- **Rules:** actions appear only for users authorised to review and only while the status allows it (hide or replace with a read-only status line otherwise). Return and Approve use the existing logic; if a reason is required for Return, collect it in a bottom sheet with a required multi-line field. Confirm Approve/Return in a ConfirmDialog only if the existing flow does.
- **States:** loading, not found, action in progress (button spinner, other button disabled), action failed (ErrorBanner), already actioned by someone else (InfoBanner and refresh).

### D17. Resources
- **Hierarchy:** back, title "Resources"; four ListRows (72 dp, `radiusCardSmall`) with 44 dp tinted icon tiles: **Asset Register** (`file-spreadsheet`, blue) "View / upload asset register"; **Master Input Sheet** (`file-spreadsheet`, green) "Manage master workbook"; **Field Mapping** (`database`, violet) "Configure field mapping"; **Documents** (`folder`, indigo) "View shared documents". Trailing `chevron-right`.
- **Rules:** only the resources in the approved plan, only for authorised roles; Drive-backed rows show connected/disconnected state in `caption` ("Connected to Google Drive" / "Not connected").
- **States:** Drive not connected → InfoBanner with the approved action; loading; error.

### D18. Field Mapping
- **Hierarchy:** back, title "Field mapping"; subtext explaining "Source register header → Auditor input → Master Input column"; rows (72 dp): 36 dp tile icon, **Auditor-facing label** `bodyStrong` (e.g., "Asset number"), source header `caption` `textSecondary`, destination "→ Column B" `caption` `primaryText`, `chevron-right`; edit opens a bottom sheet with source header, label, destination column.
- **Rules:** this screen shows and edits the real mapping; restyle only. Warn before changing a mapping that affects existing data, exactly as the plan defines. The mockup's Column B/C/D/E are sample values.
- **States:** loading; validation error (duplicate destination column, missing required mapping) inline on the row; saved confirmation inline.

### D19. Control Panel
- **Hierarchy:** back, title "Control panel"; ListRows with 44 dp tinted tiles: **User & Role Management** (`users`) "Manage users and roles"; **Client Assignment** (`building`) "Assign clients to auditors"; **Access Requests** (`key`) "Review access requests" with a 20 dp `dangerAction` count badge when there are pending requests; **Google Drive & Settings** (`folder` or `cloud`) "Manage Drive integration".
- **Rules:** visible only to authorised roles; entries limited to existing controls. Role-restricted entries (e.g., Owner-only governance) are hidden, not disabled.

### D20. User Management
- **Hierarchy:** back, title "User management"; search if present; list rows (72 dp): 44 dp avatar (photo or initials), name `bodyStrong`, email `bodySmall` `textSecondary`, role badge: Auditor (`primarySoft`/`primaryText`), Admin (`successSoft`/`successText`), Owner (`violetSoft`/`violetText`, display only), assignment state `caption` ("Assigned to 2 clients" / "No client assigned"); FAB `plus` only if the plan lets this role add or invite users.
- **Rules:** action menus (`more-vertical`) appear only when the signed-in role may perform them. Role changes use existing backend calls and confirmation.
- **States:** skeleton, empty, error, action in progress.

### D21. Access Requests
- **Hierarchy:** back, title "Access requests"; SegmentedTabs "Pending" | "All"; request cards: requester name `bodyStrong`, email `bodySmall`, client name, "Requested 28 Oct 2026" `caption` (from data), and two 48 dp buttons: Approve (`successAction` outline/filled small) and Reject (`dangerAction`). In "All", show a StatusBadge instead of buttons for resolved requests.
- **Rules:** Approve/Reject exist only for authorised users; call existing backend logic; disable both buttons while a request is processing; show the result inline on the card.
- **States:** skeleton, empty ("No pending requests."), error.

### D22. Settings
- **Hierarchy:** back, title "Settings"; ListRows with 40 dp tiles: **Theme** (`moon`; only if a second theme exists), **Language** (`globe`, value "English"; only if multiple languages exist), **Notifications** (`bell`, "Manage notifications"), **About** (`info`, "Version x.y.z" from the build).
- **Rules:** only settings that work. Do not show a row that does nothing.

### D23. Profile
- **Hierarchy:** back, title "Profile"; 72 dp avatar (photo or initials on `violet`→`primary`), name `title`, email `bodySmall`, role badge from the **backend-verified** role; rows: **Edit profile** (`user`), **Change password** (`key`), **Logout** (`logout`, tile `dangerSoft`, label `dangerText`).
- **Actions:** Logout asks for confirmation in a ConfirmDialog then follows the existing sign-out.
- **Rules:** Edit profile and Change password appear only if the app supports them (Google-only accounts do not get Change password).

### D24. Additional screens to style with the same system (if they exist)
Forgot Password (mail field + "Send reset link", success inline card); Edit Profile; Change Password; Notifications list; Client Assignment; Drive settings; any error/offline/maintenance full-screen state. Use the closest pattern above. Do not invent screens that the project does not have.

---

# Part E — Where the assets go

| Package folder | Purpose | Android native | Web / PWA / hybrid |
|---|---|---|---|
| `android_res/drawable/ic_af_*.xml` | Vector icons (67) | `res/drawable/` | not used |
| `svg/icons/*.svg` | Vector icons (67, `currentColor`) | not used | `public/assets/icons/` (inline or via an icon component) |
| `png/icons/{blue,white,muted,navy}/` | Raster fallback icons, 48/96/192 px | only if vectors cannot be used | only if SVG cannot be used |
| `android_res/drawable-nodpi/bg_*.webp` | Backgrounds | `res/drawable-nodpi/` | use `webp/backgrounds/` |
| `webp/backgrounds/`, `png/backgrounds/` | Backgrounds (720, 1080, 1440 px wide) | optional | `public/assets/backgrounds/` |
| `svg/brand/assetflow_mark*.svg`, `png/brand/` | Logo mark (approximation) | `res/drawable/` (convert SVG with Android Studio Vector Asset if desired) | `public/assets/brand/` |
| `svg/illustrations/`, `png/illustrations/` | Welcome hero (approximation) | `res/drawable-nodpi/` | `public/assets/illustrations/` |
| `android_res/mipmap-*` | Launcher icons, adaptive layers | `res/mipmap-*/` | not used |
| `png/launcher/` | Play Store 512/1024, favicons | store listing | `public/` (favicon, manifest icons) |
| `tokens/af_colors.xml`, `AssetFlowColors.kt` | Colour tokens | `res/values/`, theme package | not used |
| `tokens/assetflow_tokens.css` | CSS variables | not used | global stylesheet |
| `00_originals/` | The kit exactly as supplied | reference only | reference only |

Never use `01_references/*.png` inside the running app.

If the project is native Android, prefer the vector drawables with `android:tint` or Compose `Icon(tint = …)`. If it is a web or hybrid app, load the SVGs with `currentColor` so state and theme colours apply.

---

# Part F — Role and access rules (non-negotiable)

1. The UI never grants permissions. Role Selection is a navigation hint. Every privileged capability is authorised by the existing trusted backend and enforced by existing Firestore rules.
2. Admin and Owner features are hidden from users the backend has not authorised; they are not merely disabled.
3. Owner is not selectable at registration or at role selection, and no Owner artwork is supplied.
4. An unassigned Auditor may authenticate; before assignment they see no client data and get the approved access-request flow as an inline state.
5. Client Selection lists only clients the signed-in user may access.
6. Do not alter registration, login, assignment or role-governance policy to suit the visuals. If the visuals and the policy conflict, the policy wins and the conflict is recorded.

---

# Part G — Definition of done and reporting

Complete when: every existing required screen uses the shared tokens and components; Welcome leads to the two-card Role Selection; Admin and Auditor flows respect their authorised workflows; all forms keep their fields, validation and mapping; layouts do not clip or overlap at 320 dp width or 200% font scale; assets are in the resource folders in Part E; existing business logic and integrations are unchanged; incomplete items and bugs are recorded honestly in the existing final bug-fixing register (no separate testing roadmap).

Final report format: (1) Files changed; (2) Screens restyled / screens in the spec not found in the project; (3) Components added; (4) Assets placed; (5) Deviations from this spec and why; (6) Open questions; (7) Bug-register entries.
