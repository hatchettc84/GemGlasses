# BobAI Industrial Enterprise UI/UX Design Specification

Branch: `bobai-ui-scaffold` · Target Hardware: **Samsung Galaxy Z Fold7 (Cover 411dp / Unfolded 2-Pane ≥600dp)** + **Ray-Ban Meta Smart Glasses (DAT SDK 1.0.1)**

---

## 1. Executive Summary & Design Philosophy

BobAI is a deterministic, citation-backed field service workstation designed for MRO distributors, commercial tool ecosystems, and industrial OEMs (**Grainger MRO, DeWalt Commercial Fleet, Milwaukee ONE-KEY, HVAC/Mechanical OEMs**).

Studying the existing layout and architecture in [`BobAiUi.kt`](../app/src/main/java/com/lpecom/gemglasses/bobai/ui/BobAiUi.kt) and [`Screens.kt`](../app/src/main/java/com/lpecom/gemglasses/bobai/ui/Screens.kt), we preserved every core safety invariant while upgrading the visual, typographic, and ergonomic language to a **Ruggedized Industrial Design System**:

1. **Multi-Tenant OEM / MRO Brand Skinning (`IndustrialBrand`)** — Live enterprise profile switching across:
   - **BobAI Field Core (`BAI-PRO`)**: Safety Signal Orange (`#FF8C00`) + ANSI Green + Tactical Carbon Slate.
   - **Grainger MRO Connect (`GWW-MRO`)**: Grainger Industrial Red (`#E1251B`) + MRO Supply Catalog Badges.
   - **DEWALT SiteManager (`DW-20V`)**: Guaranteed Tough High-Vis Yellow (`#FEBD17`) + Heavy Contrast Black/Slate.
   - **Milwaukee ONE-KEY Field (`MW-M18`)**: Heavy Duty Red (`#DB011C`) + Asset Telemetry Tags.
2. **Tactical Slate & Daylight Shop-Floor Contrast Modes** — Defaults to a deep carbon/machined-slate dark theme (`#0B0E12` chassis, `#141920` panel surface, `#212832` steel bezel) engineered for mechanical rooms and glare, plus a 1-tap toggle to **Daylight Shop Floor (Light)** mode in Settings.
3. **Glove-Ready Ergonomics & Fold7 Hinge Continuity** — Minimum `48dp–56dp` touch targets, reinforced `1.5dp` borders, `24dp` central hinge exclusion zone (`HINGE_GAP`), hoisted `LazyListState` scroll continuity across fold/unfold transitions, and left/right split bottom action bars.

---

## 2. Industrial Visual Language & Component Architecture

| Component | Existing Layout Role | Industrial Enterprise Upgrade |
|---|---|---|
| `MockBanner` | Top disclosure strip | Dual-tier **Enterprise Telemetry Bar** (`badgeCode`, `displayName`, `divisionTag`) + OSHA Amber Mock Disclosure strip. |
| `SectionCard` | Standard Material card | **Machined Bezel Card**: `1.5dp` steel border, top header bar with `4dp × 16dp` semantic accent bar (`OK`, `WARN`, `BAD`, or brand primary), uppercase title, and right-aligned `TechnicalBadge`. |
| `TechnicalBadge` | *(New component)* | Monospace key/value telemetry badge (`WO#`, `ASSET`, `S/N`, `REV`, `SKU/CAT`, `LOTO`, `NODE`, `CACHE`) for rapid scanning of industrial identifiers. |
| `StatusChip` | Status indicator with icon + text | High-contrast tinted surface (`12%` semantic fill + `1.5dp` semantic border) pairing icon + bold label so status is never communicated by color alone. |
| `PrimaryAction` & `SecondaryAction` | `56dp` / `48dp` buttons | Glove-ready industrial buttons with high-contrast fills, `ExtraBold` labels, and reinforced borders. |

---

## 3. Screen-by-Screen UX Walkthrough

### 3.1 Home — Field Dispatch & Command Console (`HomeScreen`)
- **Header & Telemetry**: Displays active partner skin (`Grainger MRO Connect`, `DEWALT SiteManager`, `Milwaukee ONE-KEY Field`, or `BobAI Field Core`), `TENANT` badge, and the framed `StatusStrip` (Glasses link status, Operating Mode, Camera/Mic hardware assurance).
- **Foldable Layout**:
  - **Cover Screen (<600dp)**: Single vertical column stacking *Ready for a service call?*, *Current job*, *Enterprise OEM / MRO Partner Profile*, *Quick actions*, and *Offline readiness*.
  - **Inner Unfolded Display (≥600dp)**: 2-column split separated by `24dp` (`HINGE_GAP`). Left column handles active work order dispatch & partner profile; right column handles 4 quick tools (*Identify*, *Troubleshoot*, *Find manual*, *Capture note*) and offline pack readiness.

### 3.2 Equipment Confirmation — Stamped Rating Plate & Nameplate Gate (`EquipmentScreen`)
- **Stamped Metal Rating Plate Simulator**: Renders the OCR / label string inside a machined monospace rating plate container with `EQUIPMENT RATING PLATE · STAMPED METAL` and `● REV-SPEC` header metadata.
- **Ambiguity & Smudge Simulation**: Preserves the 48dp accessible toggle to test smudged/ambiguous labels (`ABC-123 / ABC-128`).
- **MRO Catalog Cross-Reference**: When a known model is entered, displays `OEM`, `SKU/CAT` (`MRO-ABC-123`), and `VOLTAGE` (`120V 60Hz`) technical badges before explicit human confirmation.

### 3.3 Assistant — Dual-Pane Tactical Troubleshooting Workstation (`AssistantScreen`)
- **OSHA / LOTO Safety Prerequisite Gate**: Before any troubleshooting step can run, prerequisites are framed inside an OSHA Caution Amber gate (`LOTO 0/3`) with individual `52dp` tactile confirmation rows that turn ANSI Safety Green when checked.
- **Deterministic Step Execution**:
  - Displays `PROGRESS` (`Check N on this path`) and `PATH` (`THERMOSTAT → ERROR_CODE → IGNITER`) technical badges.
  - Frames the active step instruction in a high-contrast callout box (`18sp`) with a direct `Source: [Manual, rev B, page 42] -> Open` citation bar.
  - Glove-sized (`54dp`) observation buttons plus a dedicated amber-bordered `"I am not sure"` button that refuses to guess or advance.
- **Unfolded 2-Pane Split (`SplitPanes`)**:
  - **Left Pane (`WorkflowPane`)**: Equipment card, LOTO/Procedure step card, Ask/Record evidence box, and categorized Session Timeline (`TECH`, `OPTIC`, `SPEC`, `SYS` badges).
  - **Right Pane (`ReferencePane`)**: Synchronized `Manual`, `Photos`, and `Notes` tabs defaulted to the active step's cited manual page.

### 3.4 Library & CAD Schematic Document Viewer (`LibraryScreen`, `DocumentScreen`)
- **Approval & Pack Filtering**: Filter by *All*, *Applies to current equipment*, or *Downloaded*, with clear `Approved`, `Draft`, and `Revoked` badges and `PAGES` / `REV` metadata.
- **Technical Blueprint Page Viewer (`DocumentBody`)**: Displays `DOC-ID`, `REV`, and `MODELS` badges, tactile page stepper (`◀ Prev | Page N | Next ▶`), zoom slider with live `SCALE` percentage badge (`100%–200%`), and a styled CAD/Schematic diagram container.

### 3.5 Jobs, Work Orders & Audit Sign-Off (`JobsScreen`, `JobDetailScreen`, `SummaryScreen`)
- **Work Order Tracking**: Displays `WO#` badges, local sync states, confirmed asset serial numbers, and session history.
- **Compliance Summary Sign-Off**: Monospace editable audit summary with `ASSET` and `CITATIONS` counters, allowing the technician to save a local draft or mark reviewed & complete the work order.
