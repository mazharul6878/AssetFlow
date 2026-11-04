# AssetFlow — UI Asset Inventory

Version 1.1. Every file below was opened and its dimensions read from disk. "Status" uses three values: **Ready** (use as is), **Modify** (use after the change noted, or use the replacement listed), **Native** (implement in code, do not use the image).

**Limits of this audit.** The two reference mockups are flattened pictures, so no editable source assets exist for anything drawn in them. All icons, the logo mark and the welcome illustration in this package are re-creations in the same style, not the original source files. The kit file names suggest an icon library (Lucide-style names) but this could not be confirmed. The new vector icons follow the open-source Lucide geometry convention (ISC licence) and were redrawn here. Photos shown in the mockups (laptop, chair, table, projector) are not part of the kit; they stand in for real evidence photos and must come from app data.

## 1. Supplied kit (`00_originals/`, unchanged)

### 1.1 Documents and references

| File | Format | Dimensions | Intended use | Status | Notes |
|---|---|---|---|---|---|
| `AssetFlow_UI_Theme_Specification_and_Google_AI_Studio_Prompt.md` | Markdown | — | Source specification and prompt | Ready | Preserved. Superseded in detail by the v1.1 specification in this delivery; its business rules are carried over unchanged. |
| `README.md` | Markdown | — | Kit readme | Ready | States that icons and elements are approximations. Confirmed. |
| `references/reference_light_theme_all_pages.png` | PNG RGB | 1536 × 1024 | 21-screen collage (Welcome … Profile) | Native | Reference only. Never use as an in-app image. Contains the typos and garbled labels listed in the specification, Part B. |
| `references/reference_welcome_role_selection_flow.png` | PNG RGB | 1536 × 1024 | 12-screen flow (Welcome, Role Selection, Login, Registration, Client, Fiscal Year, Admin/Auditor Home, Search, Counting, Photo, Success) | Native | Reference only. Shows an **Owner** role card that must not be implemented. Has two Login variants and two Counting Form variants across the two images. |

The two images you attached to the request have the same content as these two files (same screens and size; the bytes differ only through re-encoding), so the kit copies are the ones packaged.

### 1.2 Backgrounds, brand and surfaces

| File | Format | Dimensions | Intended use | Status | Notes |
|---|---|---|---|---|---|
| `assets/backgrounds/assetflow_light_gradient_1440x2560.png` | PNG RGBA | 1440 × 2560 | Default screen background | Modify | Opaque. Grey halos around the blue and violet glows (visible as dirty rings, contrast with the clean glow in the mockup). Replaced by `bg_aurora`. |
| `assets/backgrounds/welcome_ambient_background_1440x2560.png` | PNG RGBA | 1440 × 2560 | Welcome background | Modify | Nearly identical to the file above and has the same grey-halo defect. Replaced by `bg_welcome`. |
| `assets/elements/assetflow_mark_transparent_512.png` | PNG RGBA | 512 × 512 | Logo mark | Modify | Clean transparent raster; dot is 92% opaque and a soft shadow is baked in. Only 512 px. Re-created as SVG (`assetflow_mark.svg`) plus 512/1024 PNG. **An approximation of the mockup logo, not the source.** |
| `assets/elements/glass_card_surface.png` | PNG RGBA | 800 × 500 | Glass card reference | Native | Flat white rounded rectangle with a baked shadow. A raster card cannot resize with content. Build the card in code from the tokens. |
| `assets/elements/primary_button_surface.png` | PNG RGBA | 800 × 180 | Primary button reference | Native | Same reason. Also the label contrast of white on the supplied blue is 3.88:1, below 4.5:1. Use the gradient in the tokens file. |
| `assets/elements/secondary_button_surface.png` | PNG RGBA | 800 × 180 | Secondary button reference | Native | Build in code. |
| `assets/elements/color_palette_reference.png` | PNG RGB | 1200 × 360 | Palette swatches | Ready | Reference only. Supplied palette is kept, with the accessibility corrections in `AssetFlow_Design_Tokens.md`. |

### 1.3 Icons (43 files, all 96 × 96 RGBA, single fixed blue `#2370EB`)

Fixed-colour PNGs cannot take theme or state colours and are soft when scaled to 24 dp on high-density screens. All 43 are therefore marked **Modify**: use the vector replacement from §2. Contact-sheet review at 96 px found these to be visibly malformed or ambiguous:

- `settings.png` draws a sun-like sunburst, not a gear or sliders.
- `help-circle.png` has a broken question mark with a gap in the circle.
- `user.png` has a cut-off base.
- `download.png` draws both an up and a down arrowhead, so it reads as a transfer icon rather than download.

| Original | Replacement vector | Replacement PNG (blue, 96) | Status |
|---|---|---|---|
| `assets/icons/activity.png` | `svg/icons/activity.svg` · `ic_af_activity.xml` | `png/icons/blue/ic_activity_96.png` | Modify |
| `assets/icons/arrow-left.png` | `svg/icons/arrow-left.svg` · `ic_af_arrow_left.xml` | `png/icons/blue/ic_arrow_left_96.png` | Modify |
| `assets/icons/bell.png` | `svg/icons/bell.svg` · `ic_af_bell.xml` | `png/icons/blue/ic_bell_96.png` | Modify |
| `assets/icons/briefcase.png` | `svg/icons/briefcase.svg` · `ic_af_briefcase.xml` | `png/icons/blue/ic_briefcase_96.png` | Modify |
| `assets/icons/building.png` | `svg/icons/building.svg` · `ic_af_building.xml` | `png/icons/blue/ic_building_96.png` | Modify |
| `assets/icons/calendar.png` | `svg/icons/calendar.svg` · `ic_af_calendar.xml` | `png/icons/blue/ic_calendar_96.png` | Modify |
| `assets/icons/camera.png` | `svg/icons/camera.svg` · `ic_af_camera.xml` | `png/icons/blue/ic_camera_96.png` | Modify |
| `assets/icons/chart.png` | `svg/icons/chart.svg` · `ic_af_chart.xml` | `png/icons/blue/ic_chart_96.png` | Modify |
| `assets/icons/check-circle.png` | `svg/icons/check-circle.svg` · `ic_af_check_circle.xml` | `png/icons/blue/ic_check_circle_96.png` | Modify |
| `assets/icons/check.png` | `svg/icons/check.svg` · `ic_af_check.xml` | `png/icons/blue/ic_check_96.png` | Modify |
| `assets/icons/chevron-right.png` | `svg/icons/chevron-right.svg` · `ic_af_chevron_right.xml` | `png/icons/blue/ic_chevron_right_96.png` | Modify |
| `assets/icons/clipboard-check.png` | `svg/icons/clipboard-check.svg` · `ic_af_clipboard_check.xml` | `png/icons/blue/ic_clipboard_check_96.png` | Modify |
| `assets/icons/clipboard.png` | `svg/icons/clipboard.svg` · `ic_af_clipboard.xml` | `png/icons/blue/ic_clipboard_96.png` | Modify |
| `assets/icons/clock.png` | `svg/icons/clock.svg` · `ic_af_clock.xml` | `png/icons/blue/ic_clock_96.png` | Modify |
| `assets/icons/cloud.png` | `svg/icons/cloud.svg` · `ic_af_cloud.xml` | `png/icons/blue/ic_cloud_96.png` | Modify |
| `assets/icons/database.png` | `svg/icons/database.svg` · `ic_af_database.xml` | `png/icons/blue/ic_database_96.png` | Modify |
| `assets/icons/download.png` | `svg/icons/download.svg` · `ic_af_download.xml` | `png/icons/blue/ic_download_96.png` | Modify (malformed) |
| `assets/icons/edit.png` | `svg/icons/edit.svg` · `ic_af_edit.xml` | `png/icons/blue/ic_edit_96.png` | Modify |
| `assets/icons/eye.png` | `svg/icons/eye.svg` · `ic_af_eye.xml` | `png/icons/blue/ic_eye_96.png` | Modify |
| `assets/icons/file-spreadsheet.png` | `svg/icons/file-spreadsheet.svg` · `ic_af_file_spreadsheet.xml` | `png/icons/blue/ic_file_spreadsheet_96.png` | Modify |
| `assets/icons/file.png` | `svg/icons/file.svg` · `ic_af_file.xml` | `png/icons/blue/ic_file_96.png` | Modify |
| `assets/icons/filter.png` | `svg/icons/filter.svg` · `ic_af_filter.xml` | `png/icons/blue/ic_filter_96.png` | Modify |
| `assets/icons/folder.png` | `svg/icons/folder.svg` · `ic_af_folder.xml` | `png/icons/blue/ic_folder_96.png` | Modify |
| `assets/icons/help-circle.png` | `svg/icons/help-circle.svg` · `ic_af_help_circle.xml` | `png/icons/blue/ic_help_circle_96.png` | Modify (malformed) |
| `assets/icons/home.png` | `svg/icons/home.svg` · `ic_af_home.xml` | `png/icons/blue/ic_home_96.png` | Modify |
| `assets/icons/image.png` | `svg/icons/image.svg` · `ic_af_image.xml` | `png/icons/blue/ic_image_96.png` | Modify |
| `assets/icons/key.png` | `svg/icons/key.svg` · `ic_af_key.xml` | `png/icons/blue/ic_key_96.png` | Modify |
| `assets/icons/list.png` | `svg/icons/list.svg` · `ic_af_list.xml` | `png/icons/blue/ic_list_96.png` | Modify |
| `assets/icons/lock.png` | `svg/icons/lock.svg` · `ic_af_lock.xml` | `png/icons/blue/ic_lock_96.png` | Modify |
| `assets/icons/logout.png` | `svg/icons/logout.svg` · `ic_af_logout.xml` | `png/icons/blue/ic_logout_96.png` | Modify |
| `assets/icons/map-pin.png` | `svg/icons/map-pin.svg` · `ic_af_map_pin.xml` | `png/icons/blue/ic_map_pin_96.png` | Modify |
| `assets/icons/menu.png` | `svg/icons/menu.svg` · `ic_af_menu.xml` | `png/icons/blue/ic_menu_96.png` | Modify |
| `assets/icons/plus.png` | `svg/icons/plus.svg` · `ic_af_plus.xml` | `png/icons/blue/ic_plus_96.png` | Modify |
| `assets/icons/search.png` | `svg/icons/search.svg` · `ic_af_search.xml` | `png/icons/blue/ic_search_96.png` | Modify |
| `assets/icons/settings.png` | `svg/icons/settings.svg` · `ic_af_settings.xml` | `png/icons/blue/ic_settings_96.png` | Modify (malformed) |
| `assets/icons/shield.png` | `svg/icons/shield.svg` · `ic_af_shield.xml` | `png/icons/blue/ic_shield_96.png` | Modify |
| `assets/icons/sync.png` | `svg/icons/sync.svg` · `ic_af_sync.xml` | `png/icons/blue/ic_sync_96.png` | Modify |
| `assets/icons/tag.png` | `svg/icons/tag.svg` · `ic_af_tag.xml` | `png/icons/blue/ic_tag_96.png` | Modify |
| `assets/icons/trash.png` | `svg/icons/trash.svg` · `ic_af_trash.xml` | `png/icons/blue/ic_trash_96.png` | Modify |
| `assets/icons/upload.png` | `svg/icons/upload.svg` · `ic_af_upload.xml` | `png/icons/blue/ic_upload_96.png` | Modify |
| `assets/icons/user.png` | `svg/icons/user.svg` · `ic_af_user.xml` | `png/icons/blue/ic_user_96.png` | Modify (malformed) |
| `assets/icons/users.png` | `svg/icons/users.svg` · `ic_af_users.xml` | `png/icons/blue/ic_users_96.png` | Modify |
| `assets/icons/x-circle.png` | `svg/icons/x-circle.svg` · `ic_af_x_circle.xml` | `png/icons/blue/ic_x_circle_96.png` | Modify |

## 2. Added or regenerated assets (`AssetFlow_UI_Assets_Organized/`)

### 2.1 Icons — 67 vectors

Stroke 1.75 on a 24 dp grid, round caps and joins, single path per icon. Same file set exists as SVG (`currentColor`), Android VectorDrawable (`ic_af_*`, `?attr/colorControlNormal` tint) and PNG in four colours (`blue #147DFF`, `white`, `muted #8092B4`, `navy #172A4A`) at 48, 96 and 192 px. Status: **Ready**.

The 43 kit names are all covered. 24 were added for needs the specification names but the kit lacked:

| Icon | Used for |
|---|---|
| `alert-triangle` | Warning / error banners |
| `info` | Info banners, About |
| `arrow-right` | Welcome and Role Selection button affordance |
| `chevron-left` | Back in sheets |
| `chevron-down` | Dropdowns, context pill |
| `x` | Remove photo, close sheet |
| `more-vertical` | Row action menu |
| `eye-off` | Password hide |
| `mail` | Email fields |
| `globe` | Language setting |
| `moon` | Theme setting (only if a second theme exists) |
| `refresh` | Retry |
| `cloud-off` | Offline / Sync Failed |
| `inbox` | Empty states |
| `scan-barcode` | Barcode scan (only if scanning exists) |
| `dashboard` | Optional home destination |
| `package` | Total Assets KPI, default asset class |
| `laptop` | IT Equipment class, hero |
| `monitor` | Monitor asset class |
| `printer` | Printer asset class |
| `armchair` | Furniture class, hero |
| `retake` | Retake photo, Returned status |
| `send` | Submit alternative icon |
| `user-check` | Approved user / access granted |

### 2.2 Backgrounds (regenerated, no halo)

| File | Dimensions | Use | Status |
|---|---|---|---|
| `png/backgrounds/bg_aurora_1440x2560.png` | 1440 × 2560 | Default screens | Ready |
| `png/backgrounds/bg_calm_1440x2560.png` | 1440 × 2560 | Long forms and lists | Ready |
| `png/backgrounds/bg_welcome_1440x2560.png` | 1440 × 2560 | Welcome, Role Selection | Ready |
| `webp/backgrounds/bg_aurora_1080x1920.webp` | 1080 × 1920 | Default screens | Ready |
| `webp/backgrounds/bg_aurora_720x1280.webp` | 720 × 1280 | Default screens | Ready |
| `webp/backgrounds/bg_calm_1080x1920.webp` | 1080 × 1920 | Long forms and lists | Ready |
| `webp/backgrounds/bg_calm_720x1280.webp` | 720 × 1280 | Long forms and lists | Ready |
| `webp/backgrounds/bg_welcome_1080x1920.webp` | 1080 × 1920 | Welcome, Role Selection | Ready |
| `webp/backgrounds/bg_welcome_720x1280.webp` | 720 × 1280 | Welcome, Role Selection | Ready |

### 2.3 Brand and launcher (all approximations of the mockup logo)

| File | Dimensions | Use | Status |
|---|---|---|---|
| `svg/brand/assetflow_mark.svg` | — | Logo | Ready (approximation) |
| `svg/brand/assetflow_mark_flat.svg` | — | Logo | Ready (approximation) |
| `png/brand/assetflow_mark_1024.png` | 1024 × 1024 | Logo | Ready (approximation) |
| `png/brand/assetflow_mark_flat_1024.png` | 1024 × 1024 | Logo | Ready (approximation) |
| `png/brand/assetflow_mark_512.png` | 512 × 512 | Logo | Ready (approximation) |
| `png/launcher/play_store_icon_1024.png` | 1024 × 1024 | Store / web icon | Ready (approximation) |
| `png/launcher/play_store_icon_512.png` | 512 × 512 | Store / web icon | Ready (approximation) |
| `png/launcher/favicon_192.png` | 192 × 192 | Store / web icon | Ready (approximation) |
| `png/launcher/favicon_32.png` | 32 × 32 | Store / web icon | Ready (approximation) |
| `android_res/mipmap-hdpi/ic_launcher.png` | 72 × 72 | Android launcher | Ready (approximation) |
| `android_res/mipmap-hdpi/ic_launcher_background.png` | 162 × 162 | Android launcher | Ready (approximation) |
| `android_res/mipmap-hdpi/ic_launcher_foreground.png` | 162 × 162 | Android launcher | Ready (approximation) |
| `android_res/mipmap-hdpi/ic_launcher_round.png` | 72 × 72 | Android launcher | Ready (approximation) |
| `android_res/mipmap-mdpi/ic_launcher.png` | 48 × 48 | Android launcher | Ready (approximation) |
| `android_res/mipmap-mdpi/ic_launcher_background.png` | 108 × 108 | Android launcher | Ready (approximation) |
| `android_res/mipmap-mdpi/ic_launcher_foreground.png` | 108 × 108 | Android launcher | Ready (approximation) |
| `android_res/mipmap-mdpi/ic_launcher_round.png` | 48 × 48 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xhdpi/ic_launcher.png` | 96 × 96 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xhdpi/ic_launcher_background.png` | 216 × 216 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xhdpi/ic_launcher_foreground.png` | 216 × 216 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xhdpi/ic_launcher_round.png` | 96 × 96 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxhdpi/ic_launcher.png` | 144 × 144 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxhdpi/ic_launcher_background.png` | 324 × 324 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxhdpi/ic_launcher_foreground.png` | 324 × 324 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxhdpi/ic_launcher_round.png` | 144 × 144 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxxhdpi/ic_launcher.png` | 192 × 192 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxxhdpi/ic_launcher_background.png` | 432 × 432 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxxhdpi/ic_launcher_foreground.png` | 432 × 432 | Android launcher | Ready (approximation) |
| `android_res/mipmap-xxxhdpi/ic_launcher_round.png` | 192 × 192 | Android launcher | Ready (approximation) |
| `android_res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | — | Adaptive icon definitions | Ready |

### 2.4 Illustration

| File | Dimensions | Use | Status |
|---|---|---|---|
| `svg/illustrations/welcome_hero_approx.svg` | — | Welcome hero | Ready (approximation; simpler than the mockup) |
| `png/illustrations/welcome_hero_approx_1200x880.png` | 1200 × 880 | Welcome hero | Ready (approximation; simpler than the mockup) |

### 2.5 Tokens

`tokens/assetflow_tokens.css`, `tokens/af_colors.xml`, `tokens/AssetFlowColors.kt`, `tokens/tokens_colors.json`: generated from one source, so values match `AssetFlow_Design_Tokens.md` exactly.

## 3. Missing assets (not supplied, not created, with reason)

| Needed | Why not provided | What to do |
|---|---|---|
| Google "G" logo for Continue with Google | Brand asset with usage rules | Use Google's official sign-in button assets |
| Owner role artwork | Owner is not selectable | None. Do not add |
| Product photos (laptop, chair, table, projector) | Mockup stand-ins for real evidence | Use real photos from app data; fall back to class icon tiles |
| User avatar photos | Mockup stand-ins | Use account photo or initials |
| Client logos | Not supplied | Use initials tile; use logo if client data has one |
| Confetti, spinners, skeletons | Motion belongs in code | Implement natively; honour reduced motion |
| Dark theme assets | Out of scope; kit is Light only | None |
| Exact original logo and illustration sources | Not recoverable from flat mockups | Replace with approved brand files if they exist |
| Typeface files | Not in kit | Bundle Inter or keep the project's existing sans |

## 4. Preservation

All original files are copied unchanged into `00_originals/` with their original folder names and no renames. Nothing in the kit was overwritten. New files use new names and folders.
