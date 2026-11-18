// SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
// Copyright (C) 2026 Nick Hepburn

/** Shown when Control Canvas starts, and again from File → About. Not dismissible. */
export const BETA_NOTICE =
  "ERIC v0.1.0 is BETA software and must only be used with hardware for testing purposes under safe conditions — not for live production, life-safety, or equipment-protection use. It is provided AS IS with no warranty (AGPL-3.0).";

export function BetaNotice() {
  return (
    <p className="oip-beta-notice" role="note" data-testid="beta-notice">
      {BETA_NOTICE}
    </p>
  );
}
