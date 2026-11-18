// SPDX-License-Identifier: AGPL-3.0-or-later WITH LicenseRef-ERIC-EPL-Linking-Exception
// Copyright (C) 2026 Nick Hepburn

import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { BetaNotice } from "./betaNotice";

describe("beta notice", () => {
  it("states the beta limit and has no dismiss control", () => {
    render(<BetaNotice />);
    const note = screen.getByTestId("beta-notice");
    expect(note.textContent).toContain("BETA");
    expect(note.textContent).toContain("life-safety");
    expect(note.textContent).toContain("AS IS");
    expect(note.querySelector("button")).toBeNull();
  });
});
