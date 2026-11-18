# ERIC UI style guide: Graphite

One calm, dark-first design system for the Gateway admin, Control Canvas, and the HMI runtime. Gray by default. Color only when something is wrong.

The shared tokens live in `web/styles/tokens.css`. Admin, Control Canvas, and the HMI import that file. Legacy theme names still resolve so saved projects keep working.

## Themes

Graphite dark is the default. Daylight light is the only other theme the switcher offers. The switcher labels are **Dark** and **Light**.

| Saved name | Resolves to | What you see |
| --- | --- | --- |
| `nightwatch` | Dark | Graphite. Near-black `#0B0D10`, cool grays, white ink. |
| `process` | Dark | The same Graphite tokens. Not a separate theme. |
| `daylight` | Light | Daylight. `#F4F5F7` paper, black ink. |
| `graphite`, `dark` | Dark | Aliases of Graphite. |
| `light` | Light | Alias of Daylight. |

There is no Process theme to pick. `process` is kept only so a project that already stored that name opens in Dark. A project theme still `extends` `daylight` or `nightwatch` and overrides tokens. An unknown name uses Dark.

Daylight is the light theme for offices, bright floors, and the report page. Graphite is the theme for admin, Control Canvas, control rooms, and dim plants.

## Principles

1. Calm until it matters. Screens are graphite and gray. Color is a signal, so when it appears an operator should look.
2. One thing is the hero. Every screen has one number or one action that is biggest. Everything else steps back.
3. Numbers are the product. Large, tabular, steady digits that never jiggle as they update. Units are small and quiet.
4. Delete before you add. No gradients, glow, glass, drop shadows, or ornaments. Depth comes from a lighter surface and a hairline.
5. Alarm colors are sacred. Red, orange, yellow, and violet mean alarm priority and nothing else, and always come with a shape and a number.
6. Fast is a feature. A tag change paints in one frame. No blur, no backdrop-filter, no animated values on the HMI.
7. Built for gloves and distance. 44 px minimum targets in touch mode, and hero values readable from three meters.
8. One system, two appearances. Admin, Control Canvas, and HMI share the same tokens, so a gauge looks the same in Control Canvas and on the plant floor.

## Color tokens

Primary actions are ink-filled (white on dark, black on light). The only saturated pixels on a healthy screen are the accent focus ring and the selected item. The accent is one cool blue, used only for focus, selection, links, and bindings.

### Graphite (Dark, default)

| Token | Value | Use |
| --- | --- | --- |
| `--oip-bg` | `#0B0D10` | App and HMI canvas |
| `--oip-surface-1` | `#14171C` | Panels, cards |
| `--oip-surface-2` | `#1C2027` | Inputs, tiles |
| `--oip-surface-3` | `#252A33` | Selected, pressed |
| `--oip-line` | `#2A2F38` | Hairlines |
| `--oip-line-strong` | `#3A414D` | Borders, tracks |
| `--oip-ink` | `#F2F4F7` | Text, values |
| `--oip-ink-2` | `#A3ABB8` | Labels |
| `--oip-ink-3` | `#8A93A1` | Units, hints |
| `--oip-primary` | `#F2F4F7` | Primary button fill |
| `--oip-primary-ink` | `#0B0D10` | Primary button text |
| `--oip-accent` | `#3D8BFF` | Focus, selection, links, bindings |
| `--oip-accent-subtle` | `rgba(61, 139, 255, 0.16)` | Focus ring |
| `--oip-ok` | `#32D583` | System status in admin and Control Canvas chrome only |
| `--oip-alarm-1` | `#FF453A` | P1 Critical |
| `--oip-alarm-2` | `#FF9F0A` | P2 High |
| `--oip-alarm-3` | `#FFD60A` | P3 Medium |
| `--oip-alarm-4` | `#C77DFF` | P4 Low |
| `--oip-state-on` | `#F2F4F7` | Running or open |
| `--oip-state-off` | `#5B6370` | Stopped or closed |

### Daylight (Light)

| Token | Value | Use |
| --- | --- | --- |
| `--oip-bg` | `#F4F5F7` | App and HMI canvas |
| `--oip-surface-1` | `#FFFFFF` | Panels, cards |
| `--oip-surface-2` | `#ECEEF1` | Inputs, tiles |
| `--oip-surface-3` | `#E1E4E8` | Selected, pressed |
| `--oip-line` | `#E1E4E8` | Hairlines |
| `--oip-line-strong` | `#C5CAD2` | Borders, tracks |
| `--oip-ink` | `#0B0D10` | Text, values |
| `--oip-ink-2` | `#4A5260` | Labels |
| `--oip-ink-3` | `#636B78` | Units, hints |
| `--oip-primary` | `#0B0D10` | Primary button fill |
| `--oip-primary-ink` | `#FFFFFF` | Primary button text |
| `--oip-accent` | `#0A66E0` | Focus, selection, links, bindings |
| `--oip-accent-subtle` | `rgba(10, 102, 224, 0.10)` | Focus ring |
| `--oip-ok` | `#11804B` | System status in admin and Control Canvas chrome only |
| `--oip-alarm-1` | `#D70F1F` | P1 Critical |
| `--oip-alarm-2` | `#B54A00` | P2 High |
| `--oip-alarm-3` | `#7A5E00` | P3 Medium text |
| `--oip-alarm-3-fill` | `#FFD60A` | P3 Medium fill |
| `--oip-alarm-4` | `#8E3CCB` | P4 Low |
| `--oip-state-on` | `#0B0D10` | Running or open |
| `--oip-state-off` | `#9AA1AC` | Stopped or closed |

P3 text on a yellow fill is `#0B0D10` in both themes. On Daylight, the P3 ink token (`--oip-alarm-3`) is the dark gold used when the priority is drawn as text on a light surface. The fill stays `#FFD60A`.

### Legacy aliases

These names keep working. Component CSS written against them does not have to change in the same edit.

| Legacy variable | Maps to |
| --- | --- |
| `--oip-color-canvas` | `--oip-bg` |
| `--oip-color-surface` | `--oip-surface-1` |
| `--oip-color-ink` | `--oip-ink` |
| `--oip-color-muted` | `--oip-ink-2` |
| `--oip-color-line` | `--oip-line` |
| `--oip-color-accent` | `--oip-accent` |
| `--oip-color-accent-ink` | `#FFFFFF` |
| `--oip-color-ok` | `--oip-ok` |
| `--oip-color-alarm` | `--oip-alarm-1` |
| `--oip-color-warning` | `--oip-alarm-2` |
| `--oip-color-running` | `--oip-state-on` |
| `--oip-color-stopped` | `--oip-state-off` |
| `--oip-color-idle` | A gray that is not the running or stopped color |
| `--oip-radius` | `--oip-radius-sm` (6 px) |

## Alarms and states

| Priority | Token | Shape | Unacknowledged | Acknowledged | Returned |
| --- | --- | --- | --- | --- | --- |
| P1 Critical | `--oip-alarm-1` | Square | Blink 1 Hz | Steady fill | Hollow outline |
| P2 High | `--oip-alarm-2` | Triangle | Blink 1 Hz | Steady fill | Hollow outline |
| P3 Medium | `--oip-alarm-3` | Diamond | Blink 1 Hz | Steady fill | Hollow outline |
| P4 Low | `--oip-alarm-4` | Circle | Blink 1 Hz | Steady fill | Hollow outline |

- Every alarm indicator is shape + priority number + color, so it reads under red-green color blindness and in grayscale.
- Only the glyph blinks, via opacity at 1 Hz. Rows and screens never flash. With reduced motion on, the row gets a 3 px outline and an UNACK tag instead.
- Process equipment is gray-first: running or open is a solid ink fill, stopped or closed is a hollow gray outline. Green and red are never used for run or stop.
- Bad quality or comms loss is gray with a `?` glyph and a struck-through value, never an alarm color.
- Green (`--oip-ok`) appears only in admin and Control Canvas chrome as a small status dot, never on a process graphic.
- Shelved is a dashed gray box. Unacknowledged rows sit on surface-2.

| Priority | Meaning |
| --- | --- |
| P1 Critical | Act now. Safety, environment, major loss. |
| P2 High | Act within minutes. |
| P3 Medium | Act this shift. |
| P4 Low | Advisory, awareness. |

## Type

Inter for the UI. JetBrains Mono for tag paths, scripts, and IDs. Both are SIL Open Font License. The token stack names them first and falls back to the system UI font when the face is not installed.

| Step | Size | Weight | Example |
| --- | --- | --- | --- |
| 5xl | 96 px | 300 | Wall display value |
| 4xl | 64 px | 300 | HMI hero value |
| 3xl | 40 px | 500 | Large KPI |
| 2xl | 28 px | 600 | Page title |
| xl | 20 px | 600 | Section title |
| lg | 16 px | 400 | HMI body and touch inputs |
| md | 14 px | 400 | Admin body |
| sm | 13 px | 400 | Dense tables and Control Canvas panels |
| xs | 12 px | 500 | Units, captions, badges |

- Every live value uses `font-variant-numeric: tabular-nums` (`.oip-num`) so digits keep their width as they update.
- Values sit at weight 400–500. Headings sit at 600. Nothing heavier.
- Sizes of 28 px and up take −0.02 em tracking. Small uppercase labels take +0.04 em and `--oip-ink-3`.
- Units are always smaller and quieter than the value.
- An HMI hero value is 64 px (about 3 m). A wall display is 96 px (about 6 m).

## Space, radius, elevation, motion

| Scale | Tokens | Rule |
| --- | --- | --- |
| Space | 4 · 8 · 12 · 16 · 24 · 32 · 48 · 64 px | 4 px grid. Card padding 16, page gutter 24, section gap 32. |
| Control height | 28 Control Canvas · 36 admin · 48 HMI | Touch mode raises all to 44 / 48 / 56 px. |
| Radius | 6 · 10 · 16 · pill | Inputs and buttons 6, cards 10, HMI tiles and dialogs 16. |
| Elevation | Surface step + 1 px hairline | No shadows on in-page surfaces. Shadow only on menus, popovers, and dialogs. |
| Motion | 0 · 120 · 200 · 320 ms, ease `(0.2, 0, 0, 1)` | Hover and toggles 120 ms. Panels 200. Dialogs 320. Values and alarm onset are instant. |
| Performance | PRF-011: tag to screen under 250 ms p95 at 50 sessions | The HMI never uses blur, backdrop-filter, box-shadow, or filter. Animate only opacity and transform. |

Touch mode is `data-oip-touch="true"` on an ancestor of the screen. It raises the minimum target to 44 px.

## Components

| Component | Spec |
| --- | --- |
| Button | Primary is ink-filled, one per view. Secondary is surface-2 with a strong hairline. Ghost is text only. Destructive is an outline in alarm-1 and needs confirmation. Height 36, 48 on the HMI. |
| Input / select | Surface-2 fill, line-strong border, 6 px radius, label above in ink-2. Focus is an accent border plus a 3 px accent-subtle ring. On the HMI the ring is an outline, not a shadow. Units sit inside on the right. |
| Table | Uppercase 11 px headers, 40 px rows, hairline dividers, numbers right-aligned and tabular. Selected row is surface-3 with a 2 px accent edge. No zebra stripes. |
| Tabs | Text tabs with a 2 px ink underline on the active tab. Wrap or scroll. Never shrink labels. |
| Nav sidebar | 240 px, surface-1, icons + labels. Section order and names stay as they are. Collapses to a 64 px icon rail on a narrow window. |
| Top bar | 52 px: search, system status dot, theme (Dark / Light), user menu. Sign-out lives with the user. |
| Status banner | Surface-1 card with a 3 px left edge in the state color, glyph, one-line message, and a link for detail. Several warnings collapse into one summary banner that still lists each cause. |
| Card / KPI tile | Surface-1, 10 px radius, 16 px padding. Label (11 px caps), value (34–40 px tabular), one line of context. A glyph appears only if the value is out of limits. |
| HMI value display | Label, value, unit. Border turns alarm color only in alarm. Bad quality strikes the value and shows `?`. |
| Gauge | 240° arc, 10 px track in line-strong, fill in state-on, limit ticks in ink-3. Fill goes alarm color only past a limit. The caption is part of the gauge SVG, below the arc, inside the viewBox. |
| Trend | 1–2 px ink line, hairline grid, setpoint dashed, alarm limit dotted, current value labelled at the right end. Series colors never use alarm hues. |
| Toggle | Pill switch, ink-filled when on, 40 × 22 (56 × 32 in touch mode), with a text label that states the result. |
| Alarm row | 44 px minimum, 4 px left edge in the priority color, glyph, time, source, message, value, Ack. Unacknowledged rows sit on surface-2. |
| Control Canvas rail | Icon rail (Project, Tags, Add, Styles, Scripts, Pages, Tools) with a label under each icon, 48 px items, plus one panel. Properties sit on the right, 300–360 px, resizable. |
| Canvas workspace | 8 px snap grid (dots every 16 px), rulers on the top and left, accent selection outline with handles and a size tag. |

## Admin Overview

Overview is a dashboard, not a long key-value list.

- One summary banner. Zero warnings reads as all clear. Several warnings read as one count plus the causes.
- Four KPI tiles: system CPU, data disk free, heap, clock drift. Each tile keeps its existing test id.
- A CPU trend when the gateway has returned samples, and a services list (modules running, and the other figures that were on the page).
- Version, profile, uptime, deployment mode, and the mode switch stay on the page. Deployment stays a form with the same labels.

## Control Canvas

- The top bar carries the product name, the project, save state, a live dot, and **Dark** / **Light**. Choosing Dark stores `nightwatch`. Choosing Light stores `daylight`. A session already on `process` shows Dark as selected.
- Project and Tags are on the left of the canvas. Properties are on the right.
- The palette is a labelled vertical rail, not a wrapping strip of text tabs.
- The canvas has a dot grid, rulers, and an accent selection with handles and a size tag.
- A script field uses the mono face, wraps, and sits in a dock that can open to 360 px. The script builder stacks its fields in one column so the handler menu is not crushed.

## HMI screen

- The hero value is the largest type on the screen.
- Only a point that is in alarm carries an alarm color. A running pump is a solid ink fill, not green.
- Start and Stop are 48 px (56 px in touch mode).
- Alarm rows sit on the bottom of the screen and do not flash the page.
- Touch mode (`data-oip-touch`) comes from the project touchscreen setting or `?touchscreen=on`.

## What changed from the previous UI

| Problem | Fix |
| --- | --- |
| Palette tab strip cramped, labels overlap | Labelled vertical rail, 48 px items. |
| Properties on the left, project browser on the right. Tags only in the palette. | Project and Tags on the left. Properties on the right. |
| Canvas has no grid, rulers, or selection styling | 8 px snap grid, rulers, accent selection with handles and a size tag. |
| Script handler menu cramped in a 300 px editor | Script fields wrap in mono. The builder is one column. The property dock is 360 px. |
| Gauge caption clipped | Caption is drawn inside the gauge SVG, below the arc, inside the viewBox. |
| No form for touchscreen mode | Project settings has a Touch mode toggle. The HMI sets `data-oip-touch`. |
| Overview is a long key-value list | Dashboard: summary banner, KPI tiles, trend, services. |
| Cream paper, copper accents, navy rail | Graphite surfaces, one blue accent for focus and selection, ink-filled primary buttons. |
| Banners stack and push content down | One summary banner with a count. Each cause stays in the banner text. |

Two items are not part of this restyle: missing admin form fields (`userGrants` and identity-provider claim fields), and bindings that ignore schema defaults. Those are behavior bugs, not the visual system.

## Performance

PRF-011 requires a tag change to reach the rendered HMI value in under 250 ms at p95, with 50 sessions. HMI styles do not add blur, backdrop-filter, box-shadow, or filter on that path. Popovers and dialogs may use a shadow. In-page surfaces use a lighter fill and a 1 px hairline.
