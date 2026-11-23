export const PLC_WINDOWS_BANNER =
  "This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel. Scan times under 100 mS are difficult to execute. For higher performance, use on Linux.";

export function showPlcWindowsBanner(os: string | null | undefined): boolean {
  return os === "windows";
}

/** Persistent. There is no control that dismisses it. */
export function PlcWindowsBanner({ os }: { os: string | null }) {
  if (!showPlcWindowsBanner(os)) return null;
  return (
    <p className="oip-plc-banner" role="note" data-testid="plc-windows-banner">
      {PLC_WINDOWS_BANNER}
    </p>
  );
}
