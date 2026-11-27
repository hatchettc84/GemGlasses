/**
 * Copyright (c) 2023-present Plane Software, Inc. and contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 * See the LICENSE file for details.
 *
 * Modified for BobAI (see plane-branding/ in the BobAI repository).
 */

import { MenuItem, MenuSeparator } from "@makeplane/propel/components/menu";
import { BOBAI_AGPL_NOTICE, BOBAI_MODIFICATIONS_NOTE, BOBAI_SOURCE_URL, PLANE_UPSTREAM_URL } from "./constants";

/** AGPL-3.0 attribution entries for the help menu. */
export function BobAIAboutMenuItems() {
  return (
    <>
      <MenuSeparator />
      <MenuItem
        label={BOBAI_AGPL_NOTICE}
        onClick={() => window.open(PLANE_UPSTREAM_URL, "_blank", "noopener,noreferrer")}
      />
      <MenuItem
        label={BOBAI_MODIFICATIONS_NOTE}
        onClick={() => {
          if (BOBAI_SOURCE_URL) window.open(BOBAI_SOURCE_URL, "_blank", "noopener,noreferrer");
        }}
      />
    </>
  );
}
