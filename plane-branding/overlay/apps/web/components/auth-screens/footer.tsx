/**
 * Copyright (c) 2023-present Plane Software, Inc. and contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 * See the LICENSE file for details.
 *
 * Modified for BobAI (see plane-branding/ in the BobAI repository).
 */

import React from "react";
import { BOBAI_AGPL_NOTICE, BOBAI_MODIFICATIONS_NOTE, PLANE_UPSTREAM_URL } from "@/components/bobai/constants";

// BobAI: Plane's footer advertised Plane's customers; replaced with the AGPL-3.0 attribution.
export function AuthFooter() {
  return (
    <div className="flex flex-col items-center gap-1 text-center">
      <a
        href={PLANE_UPSTREAM_URL}
        target="_blank"
        rel="noopener noreferrer"
        className="text-body-xs-regular text-tertiary underline-offset-2 hover:underline"
      >
        {BOBAI_AGPL_NOTICE}
      </a>
      <span className="text-body-xs-regular text-tertiary">{BOBAI_MODIFICATIONS_NOTE}</span>
    </div>
  );
}
