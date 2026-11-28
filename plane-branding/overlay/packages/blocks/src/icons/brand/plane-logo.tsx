/**
 * Copyright (c) 2023-present Plane Software, Inc. and contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 * See the LICENSE file for details.
 *
 * Modified for BobAI (see plane-branding/ in the BobAI repository).
 */

import * as React from "react";

// BobAI: the export keeps Plane's name so every existing call site picks up the BobAI mark.
export type PlaneLogoProps = React.ComponentPropsWithoutRef<"svg">;

export function PlaneLogo({ width = "52", height = "52", className }: PlaneLogoProps) {
  return (
    <svg
      width={width}
      height={height}
      viewBox="0 0 100 100"
      xmlns="http://www.w3.org/2000/svg"
      className={className}
      role="img"
      aria-label="BobAI"
    >
      <rect x="0" y="0" width="100" height="100" rx="22" fill="#ff8c00" />
      <path d="M31 21H55C66.5 21 72.5 27 72.5 35.5C72.5 41.5 69.5 45.5 64.5 47.5C71 49.5 75 55 75 62.5C75 73 67.5 79 56 79H31ZM43 31V44H53C57.5 44 60.5 41.5 60.5 37.5C60.5 33.5 57.5 31 53 31ZM43 53.5V68.5H55C60 68.5 63 65.5 63 61C63 56.5 60 53.5 55 53.5Z" fill="#101317" fillRule="evenodd" />
    </svg>
  );
}
