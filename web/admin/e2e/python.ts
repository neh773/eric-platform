import { execFileSync } from "node:child_process";

/** `python3` where that command exists, otherwise `python`. */
export function pythonCommand(): string {
  for (const name of ["python3", "python"]) {
    try {
      execFileSync(name, ["--version"], { stdio: "ignore" });
      return name;
    } catch {
      // Try the next name. The Windows app-execution alias exits non-zero.
    }
  }
  return "python3";
}
