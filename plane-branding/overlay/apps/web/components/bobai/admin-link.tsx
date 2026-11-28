/**
 * Copyright (c) 2023-present Plane Software, Inc. and contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 * See the LICENSE file for details.
 *
 * Modified for BobAI (see plane-branding/ in the BobAI repository).
 */

import { ListItem, ListItemLabel } from "@makeplane/propel/components/list";
import { ListItemLink } from "@makeplane/propel/elements/list";
import { BOBAI_ADMIN_URL } from "./constants";

/** Sidebar entry that opens the BobAI admin page (phones & manuals) in a new tab. */
export function BobAIAdminSidebarLink() {
  return (
    <ListItem density="comfortable">
      <ListItemLink
        href={BOBAI_ADMIN_URL}
        render={<a href={BOBAI_ADMIN_URL} target="_blank" rel="noopener noreferrer" />}
      >
        <svg
          className="size-4 flex-shrink-0"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.75"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <rect x="6" y="2.5" width="12" height="19" rx="2.5" />
          <path d="M10.5 18h3" />
        </svg>
        <ListItemLabel>Phones &amp; manuals</ListItemLabel>
      </ListItemLink>
    </ListItem>
  );
}
