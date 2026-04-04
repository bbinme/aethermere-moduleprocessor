# Module Config — Spec

## Purpose

Stores per-module processing hints that guide the pipeline when auto-detection fails or produces
inconsistent results. Configs are hierarchical: a child config inherits all settings from its
parent and overrides only what it specifies.

---

## Storage

Config files live in `data/configs/<configId>.json`.

The processor looks for a config whose `sourcePattern` regex matches the input filename.
If none matches, no config is applied (full auto-detection).

---

## Hierarchy

A config may declare a `parent` field containing the `configId` of another config.
Inheritance is resolved at load time by merging parent fields into the child:
- Child fields take precedence over parent fields.
- List fields (e.g. `pages`) are merged by page number — child entries override parent
  entries for the same page, parent entries for other pages are inherited.
- Scalar fields (e.g. margin values) are overridden completely, not blended.

Inheritance depth is unlimited but cycles are an error.

---

## Config File Format

```json
{
  "configId":        "DnD_BasicSet",
  "description":     "D&D Basic Set (Moldvay 1981) — shared settings for all B-series modules",
  "parent":          null,
  "sourcePattern":   null,

  "defaultPageLayout": "TWO_COLUMN",

  "margins": {
    "minTop":    null,
    "maxTop":    null,
    "minBottom": null,
    "maxBottom": null,
    "minLeft":   null,
    "maxLeft":   null,
    "minRight":  null,
    "maxRight":  null
  },

  "pages": []
}
```

---

## Fields

### Top-level

| Field               | Type    | Description                                                                         |
|---------------------|---------|-------------------------------------------------------------------------------------|
| `configId`          | string  | Unique identifier; also the filename (without `.json`)                              |
| `description`       | string  | Human-readable label                                                                |
| `parent`            | string? | `configId` of the parent config, or `null`                                          |
| `sourcePattern`     | string? | Regex matched against the input filename to auto-select this config. `null` = manual only |
| `defaultPageLayout` | string? | Layout type hint for unspecified pages (e.g. `TWO_COLUMN`). `null` = auto-detect   |

### `margins` object

All values are in **inches**. `null` means no constraint on that side.

| Field       | Description                                                                      |
|-------------|----------------------------------------------------------------------------------|
| `minTop`    | Floor: if auto-detected top margin < this, use this value instead                |
| `maxTop`    | Ceiling: if auto-detected top margin > this, use this value instead              |
| `minBottom` | Floor for bottom margin                                                          |
| `maxBottom` | Ceiling for bottom margin — useful when footer-absorption eats into content      |
| `minLeft`   | Floor for left margin                                                            |
| `maxLeft`   | Ceiling for left margin                                                          |
| `minRight`  | Floor for right margin                                                           |
| `maxRight`  | Ceiling for right margin                                                         |

At runtime, the processor converts inches → pixels using `floor(inches × dpi)`.

`min` values define where each edge scan **starts** — the detector begins inward from the
floor position and never searches closer to the page edge than that. The result is
inherently ≥ the floor; no post-process clamp is needed.

`max` values define where each edge scan **stops** — if no content boundary is found
before reaching the ceiling, the ceiling position is used. This caps over-detection
(e.g. footer-absorption walking too far into content).

```
top scan:    starts at minTop_px,   stops at maxTop_px    (searches downward)
bottom scan: starts at minBottom_px from bottom, stops at maxBottom_px from bottom (searches upward)
left scan:   starts at minLeft_px,  stops at maxLeft_px   (searches rightward)
right scan:  starts at minRight_px from right, stops at maxRight_px from right (searches leftward)
```

### `pages` array

Each entry overrides settings for a specific page or contiguous range.

| Field    | Type    | Description                                                                 |
|----------|---------|-----------------------------------------------------------------------------|
| `page`   | int     | 1-based page number (first page of range if `pageTo` is set)                |
| `pageTo` | int?    | Last page of range (inclusive). `null` = single page                        |
| `type`   | string? | Layout type override: `COVER`, `BACK_COVER`, `TITLE`, `MAP`, `TWO_COLUMN`, etc. |
| `skip`   | bool?   | If `true`, the page is excluded from all processing and output              |

---

## Defined Configs

### `DnD_BasicSet`

Parent for all Moldvay Basic Set B-series modules. Establishes the default two-column layout.
No margin constraints — subconfigs set those.

```json
{
  "configId":          "DnD_BasicSet",
  "description":       "D&D Basic Set (Moldvay 1981) — base config for B-series modules",
  "parent":            null,
  "sourcePattern":     null,
  "defaultPageLayout": "TWO_COLUMN",
  "margins":           {},
  "pages":             []
}
```

---

### `DnD_BasicSet_B1-4`

Covers modules B1 through B4. Inherits from `DnD_BasicSet`.
Sets margin floors to prevent near-zero detections caused by the decorative border frame
and full-bleed illustrations that extend to the page edge.

Page 1 is a full-page cover image; the last page is the back cover.

```json
{
  "configId":      "DnD_BasicSet_B1-4",
  "description":   "D&D Basic Set modules B1–B4",
  "parent":        "DnD_BasicSet",
  "sourcePattern": "B[1-4](?!\\d).*\\.pdf",

  "margins": {
    "minTop":    0.25,
    "minBottom": 0.25,
    "minLeft":   0.50,
    "minRight":  0.50
  },

  "pages": [
    { "page": 1, "type": "COVER",      "skip": true },
    { "page": -1, "type": "BACK_COVER", "skip": true }
  ]
}
```

> `"page": -1` is a sentinel meaning "last page of the document".

---

## Java Package

```
src/main/java/com/dnd/processor/config/
  ModuleConfig.java        — root record (configId, description, parent, sourcePattern,
                             defaultPageLayout, margins, pages)
  MarginsConfig.java       — record (minTop, maxTop, minBottom, maxBottom,
                             minLeft, maxLeft, minRight, maxRight) — all Double, nullable
  PageConfig.java          — record (page, pageTo, type, skip)
  ModuleConfigLoader.java  — load(Path) → ModuleConfig
                           — loadResolved(Path) → ModuleConfig  (parent chain merged)
                           — findForFile(String filename, Path configDir) → Optional<ModuleConfig>
```

`ModuleConfigLoader.loadResolved` walks the parent chain and merges fields bottom-up
(child wins). Returns a single flat `ModuleConfig` with all inherited values applied.
The pipeline always works with the resolved config, never the raw one.

---

## Pipeline Integration

`PDFPreprocessor` accepts an optional `ModuleConfig` parameter on `processLayout`,
`classifyPages`, and `analyzeMargins`. When present:

1. Config inch values are converted to pixels (`floor(inches × dpi)`) and passed into
   `ProjectionAnalyzer.detectMargins` as start/stop bounds for each edge scan.
   The scanner never searches outside the floor–ceiling window, so the result is
   naturally bounded without any post-process step.
2. Page-type overrides (`COVER`, `BACK_COVER`, etc.) replace the auto-detected `LayoutType`
   for the specified pages before zone analysis proceeds.
3. `skip: true` pages are passed through as a single full-page sub-page with no zone splitting.

`Main` resolves the config before calling any phase runner:
- If `--config <path>` is provided, load that file.
- Otherwise, call `ModuleConfigLoader.findForFile(filename, configDir)` to auto-match by
  `sourcePattern`.
- If no config matches, proceed with `null` (full auto-detection, current behaviour).

---

## Open Questions

1. Should `maxBottom` be set on `DnD_BasicSet_B1-4`? The aggregate data showed bottom max
   reaching 0.93 in — likely footer-absorption misfires on table-heavy pages. A `maxBottom`
   of ~0.60 in would cap those without cutting real content.
2. Should `skip: true` pages be omitted from the output JSON entirely, or included with a
   `"skipped": true` field for traceability?
3. The `-1` sentinel for "last page" is convenient but implicit. An alternative is a
   `fromEnd: 1` style. Decide before implementing.
