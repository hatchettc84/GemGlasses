/**
 * Copyright (c) 2023-present Plane Software, Inc. and contributors
 * SPDX-License-Identifier: AGPL-3.0-only
 * See the LICENSE file for details.
 *
 * Modified for BobAI (see plane-branding/ in the BobAI repository).
 */

// Build-time settings (Vite inlines VITE_* vars; set via docker build args, see plane-branding/build.sh).
export const BOBAI_ADMIN_URL: string = import.meta.env.VITE_BOBAI_ADMIN_URL || "http://localhost:5173";
// Optional public URL of BobAI's modified source (plane-branding/). Empty = shown as text only.
export const BOBAI_SOURCE_URL: string = import.meta.env.VITE_BOBAI_SOURCE_URL || "";
export const PLANE_UPSTREAM_URL = "https://github.com/makeplane/plane";
export const BOBAI_AGPL_NOTICE = "BobAI is based on Plane (AGPL-3.0)";
export const BOBAI_MODIFICATIONS_NOTE = "BobAI's modifications are in plane-branding/";
