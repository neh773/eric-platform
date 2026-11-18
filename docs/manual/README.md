# ERIC user manual

Task-oriented how-tos for the gateway admin pages, the Control Canvas (designer) and runtime sessions. Start at [index.md](index.md).

## Regenerating the screenshots

Every image in `img/` is taken by `web/manual/shots.spec.ts` against a demo project (`demo-plant`) that the spec builds through the config API. After a restyle, run from `web/`:

```
PLAYWRIGHT_BROWSERS_PATH=/path/to/browsers npx playwright test --project=manual
```

Images are written to `docs/manual/img/` at 1440x900. The gateway comes from the Playwright config's web server (`OIP_ADMIN_E2E_PORT`, default 8091).
