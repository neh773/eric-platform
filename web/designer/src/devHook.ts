import type { TagValue } from "@oip/hmi-runtime";
import type { TagRuntime } from "./runtime";

/** Installed only from the dev server. The production build must not contain this module. */

export function installTestHook(runtime: TagRuntime): () => void {
  window.__oipTest = {
    setTag(path: string, value: TagValue) {
      runtime.server.set(path, value);
    },
    getTag(path: string) {
      return runtime.server.get(path)?.value;
    },
  };
  return () => {
    delete window.__oipTest;
  };
}

declare global {
  interface Window {
    __oipTest?: {
      setTag: (path: string, value: TagValue) => void;
      getTag: (path: string) => unknown;
    };
  }
}
