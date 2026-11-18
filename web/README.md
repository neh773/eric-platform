# Web HMI and designer

TypeScript, React, and Vite workspace for the Phase 4 designer and HMI runtime. The gateway does not serve it yet. Run it from this directory:

```bash
pnpm install
pnpm dev
```

Packages:

- `@oip/hmi-runtime` renders a JSON view (coordinate, flex, column, and tab containers), applies bindings, and talks to tags through a WebSocket client. Tests use an in-memory server that speaks the same frames. Pages, docks, popups, and embedded views share that client.
- `@oip/components` is the component set: label, numeric, button, toggle, text input, indicator, linear gauge, trend, table, dropdown, slider, checkbox, alarm-status placeholder, image, and embedded view.
- `@oip/designer` is the studio shell: project tree, canvas (drag, select, resize), a property editor built from JSON Schema, and a live session preview. A separate session is mounted at `/hmi`.

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm exec playwright install chromium
pnpm test:e2e
```

Themes are `daylight` and `nightwatch`. They are original CSS variable sets. Visuals are not taken from the reference platform.

## Assumptions

- Bindings are applied in the browser for this scaffold. SPEC §7 evaluates bindings on the gateway and pushes property deltas. The JSON frames (`hello`, `subscribe`, `unsubscribe`, `update`, `write`, `heartbeat`, resume token) are the stand-in for `/api/v1/ws`.
- Implemented binding kinds: tag (direct and `{name}` indirection from a param, custom property, or component prop), property (same view, plus session and page fields), expression (a small arithmetic subset, not the full the reference platform expression language), and a named-query interface. There is no SQL engine. A missing query source keeps the authored value and sets quality `bad`. Expression and query bindings are read-only. Tag and property bindings are bidirectional when the document says so. Transforms (map, format, script hook) run inbound only and are not reversed on write. A `[provider]` segment in an expression tag ref is ignored; there is one tag namespace.
- The script transform and the script action call a host hook by name. Python is not embedded. The public events page routes tag writes through a script; this runtime also has an explicit tag-write action because the task and HMP-117 ask for one.
- Pages are mounted at `/hmi` so the studio keeps `/`. A page URL `:param` becomes the raw decoded path segment on the primary view's input params. The query string is `page.urlParams` and is not copied onto the view. One dock per edge; a page dock replaces the shared dock on that edge. `autoHide` shows the handle only while the dock is collapsed, because this runtime does not track which page is focused. `modal` is honored only for `onDemand` docks.
- Embedded views pass params by name. `output` and `inout` write back to the parent; `input` does not. `useDefaultViewWidth` and `useDefaultViewHeight` default to the view's authored size.
- The alarm-status component filters, acknowledges, and shelves the rows it is given. It is not connected to an alarm service.
- `/latency`, `/bench`, `/bench-embed`, and `window.__oipTest` exist only in dev and test builds. A production Vite build test asserts those strings are absent.
- Not in this slice: expression-structure bindings, tag history, HTTP bindings, coalesce, fallback delay, overlay opt-out, the full expression language, Python scripts, SQL, gateway hosting, and the designer config-store save.
- Column breakpoints default to 0 / 480 / 960. The public docs describe the mechanism and one example (280 / 500), not a required default set.
- Coordinate `rotate` is a number of degrees. CSS angle strings and an anchor point are not accepted yet.
- Session state is the subset the runtime needs: id, theme, BCP-47 locale, time zone, auth, device type, gateway connection, and custom. `theme`, `locale`, `timeZoneId`, and `custom.*` can be written. `id`, auth, device, and gateway cannot.
- Quality is `good`, `uncertain`, or `bad`, with codes `uncertain_initial` and `bad_not_connected`. A non-good quality draws an original hatch caption, not a copied overlay.
- The 250 ms gate is one browser p95 over 20 samples on the mock path. The 500-component render gate and the 50-embedded-view gate are each a single batch under 1000 ms.
