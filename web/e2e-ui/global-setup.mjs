import { readState, start, stop, newDataDir } from "./harness.mjs";

/** Fresh install before the suite; ERIC_UI_REUSE=1 keeps a gateway that is already running. */
export default async function globalSetup() {
  if (process.env.ERIC_UI_REUSE === "1" && readState()?.pid) return;
  await stop().catch(() => {});
  await start(newDataDir());
}
