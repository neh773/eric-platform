// designer/e2e/studio.ts reads the Studio's dev test hook; declare it so this project typechecks on its own.
interface Window {
  __oipTest?: { setTag: (path: string, value: string | number | boolean) => void } & Record<string, unknown>;
}
