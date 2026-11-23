import { stop } from "./harness.mjs";

export default async function globalTeardown() {
  if (process.env.ERIC_UI_REUSE !== "1") await stop();
}
