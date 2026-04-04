# Ruleset Detection — Spec

## Purpose

Detect the game ruleset a module was written for by scanning the copyright and trademark text on
its first two pages. The detected ruleset is recorded in the Markdown frontmatter and the JSON
output so that downstream conversion (AD&D → 5e, AD&D → Daggerheart, etc.) knows the source
stat block schema.

---

## D&D Version History Reference

| Year | Ruleset ID | Display Name | Publisher |
|------|-----------|--------------|-----------|
| 1974 | `dnd_og` | DnD Original | TSR |
| 1977 | `adnd1e` | AD&D 1st Edition | TSR, Inc. |
| 1977 | `dnd_basic` | DnD Basic | TSR, Inc. / TSR Hobbies |
| 1981 | `dnd_bx` | DnD B/X | TSR, Inc. |
| 1983 | `dnd_becmi` | DnD BECMI | TSR, Inc. |
| 1989 | `adnd2e` | AD&D 2nd Edition | TSR, Inc. |
| 1991 | `dnd_rc` | DnD Rules Cyclopedia | TSR, Inc. |
| 1995 | `adnd2e_revised` | AD&D 2nd Edition Revised | TSR, Inc. |
| 2000 | `dnd3e` | DnD 3rd Edition | Wizards of the Coast |
| 2003 | `dnd35` | DnD v3.5 | Wizards of the Coast |
| 2008 | `dnd4e` | DnD 4th Edition | Wizards of the Coast |
| 2010 | `dnd4e_essentials` | DnD Essentials | Wizards of the Coast |
| 2014 | `dnd5e` | DnD 5th Edition | Wizards of the Coast |
| 2024 | `dnd55` | DnD v5.5 | Wizards of the Coast |
| —    | `daggerheart` | Daggerheart | Darrington Press |
| —    | `unknown` | Unknown | — |

---

## Why First Two Pages

Copyright and trademark boilerplate always appears on page 1 or 2 of every TSR/WotC module.
It is the most stable and unambiguous signal — more reliable than title-page text or module
codes, which follow no consistent format.

---

## Detection Patterns (in priority order)

### AD&D Family

**`adnd1e`** — Advanced D&D 1st Edition (1977–1988)

Present: `ADVANCED DUNGEONS & DRAGONS`  
Absent: `2nd Edition`, `Second Edition`

Seen in corpus:
- DL1 (1984): `ADVANCED DUNGEONS & DRAGONS, AD&D, and PRODUCTS OF YOUR IMAGINATION are trademarks of TSR, Inc.`
- DL15 (1988): `ADVANCED DUNGEONS & DRAGONS, AD&D, PRODUCTS OF YOUR IMAGINATION, and the TSR logo are trademarks owned by TSR, Inc.`

Both map to `adnd1e`. The "TSR logo" addition is a late-1e trademark change, not an edition change.

---

**`adnd2e`** — Advanced D&D 2nd Edition (1989–1994)

Present: `ADVANCED DUNGEONS & DRAGONS`  
Present: `2nd Edition` OR `Second Edition`  
Absent: `revised` near `2nd Edition`

---

**`adnd2e_revised`** — AD&D 2nd Edition Revised (1995–1997)

Present: `ADVANCED DUNGEONS & DRAGONS`  
Present: `2nd Edition` AND (`revised` OR `Player's Option`)

---

### DnD Basic Family (TSR)

Detection for these editions requires distinguishing which set the module targets. The
module's own set membership text and trademark are the primary signals.

**`dnd_basic`** — DnD Basic (Holmes 1977 / Moldvay 1981)

Present: `DUNGEONS & DRAGONS` (without `ADVANCED`)  
Present: `Basic Set`  
Absent: `Expert Set`, `Companion Set`, `BECMI`, `Rules Cyclopedia`  
Publisher: `TSR Hobbies` OR `TSR, Inc.`

Seen in corpus (B2, 1980/1981):
```
DUNGEONS & DRAGONS® Basic Set. It has been specifically designed for use by beginning Dungeon Masters
DUNGEONS & DRAGONS® and D&D® are registered trade marks owned by TSR Hobbies, Inc.
```

---

**`dnd_bx`** — DnD B/X (Moldvay/Cook, 1981)

Present: `DUNGEONS & DRAGONS` (without `ADVANCED`)  
Present: `Basic Set` AND `Expert Set` (both referenced together in the same module)

B/X modules often reference both sets. When only `Basic Set` appears alone, prefer `dnd_basic`.

---

**`dnd_becmi`** — DnD BECMI (Mentzer, 1983–1986)

Present: `DUNGEONS & DRAGONS` (without `ADVANCED`)  
Present: at least one of `Companion Set`, `Masters Set`, `Immortals Set`, `BECMI`

CM-series modules (Companion-tier adventures) fall here.

---

**`dnd_rc`** — DnD Rules Cyclopedia (1991)

Present: `DUNGEONS & DRAGONS` (without `ADVANCED`)  
Present: `Rules Cyclopedia`

---

**`dnd_og`** — DnD Original (1974)

Present: `DUNGEONS & DRAGONS` (without `ADVANCED`)  
Present: `Original` or `White Box` or copyright year 1974–1976  
Publisher: `TSR` or `Tactical Studies Rules`

Rare in practice. Fallback for pre-1977 TSR text.

---

### WotC Family

**`dnd3e`** — DnD 3rd Edition (2000–2002)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Absent: `3.5`, `v.3.5`, `Revised`, `4th Edition`, `5th Edition`, `5e`  
Copyright year: 2000–2002 (secondary confirmation)

Seen in corpus (Forge of Fury, ©2000):
```
DUNGEONS & DRAGONS, D&D, DRAGON, DUNGEON MASTER, and the Wizards of the Coast logo are
registered trademarks owned by Wizards of the Coast, Inc.
©2000 Wizards of the Coast, Inc.
```

Note: the filename prefix `DD35` is a library catalog code; the ©2000 date confirms 3e.

---

**`dnd35`** — DnD v3.5 (2003–2007)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Present: `3.5` OR `v.3.5` OR `Revised` (near `Edition`)  
Copyright year: 2003–2007 (secondary confirmation)

---

**`dnd4e`** — DnD 4th Edition (2008–2009)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Present: `4th Edition` OR (`4e` as a standalone token)  
Absent: `Essentials`

---

**`dnd4e_essentials`** — DnD Essentials (2010–2013)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Present: `Essentials` OR (`Heroes of the` in title — Essentials product line naming pattern)

---

**`dnd5e`** — DnD 5th Edition (2014–2023)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Present: `5th Edition` OR `5e` OR `D&D Beyond`  
Absent: `2024`, `v5.5`

---

**`dnd55`** — DnD v5.5 (2024+)

Present: `DUNGEONS & DRAGONS` AND `Wizards of the Coast`  
Present: `2024` (in copyright) OR `v5.5` OR `One D&D`

---

### Other

**`daggerheart`**

Present: `Daggerheart` AND `Darrington Press`

---

**`unknown`** — Fallback

No pattern matched. Logged as a warning. Module processes normally; ruleset-sensitive
conversion steps are skipped or flagged.

---

## Detection Algorithm

```
RulesetInfo detectRuleset(String pagesOneAndTwoText):
  text = normalize(pagesOneAndTwoText)  // lowercase for matching

  // AD&D family (ADVANCED present)
  if contains("advanced dungeons & dragons"):
    if contains("2nd edition") or contains("second edition"):
      if contains("revised") or contains("player's option"):
        return ("adnd2e_revised", "AD&D 2nd Edition Revised", "TSR, Inc.")
      return ("adnd2e", "AD&D 2nd Edition", "TSR, Inc.")
    return ("adnd1e", "AD&D 1st Edition", "TSR, Inc.")

  // WotC family (Wizards present)
  if contains("wizards of the coast"):
    if contains("essentials"):
      return ("dnd4e_essentials", "DnD Essentials", "Wizards of the Coast")
    if contains("4th edition") or standalone("4e"):
      return ("dnd4e", "DnD 4th Edition", "Wizards of the Coast")
    if contains("v5.5") or contains("one d&d") or copyrightYear >= 2024:
      return ("dnd55", "DnD v5.5", "Wizards of the Coast")
    if contains("5th edition") or contains("d&d beyond") or standalone("5e"):
      return ("dnd5e", "DnD 5th Edition", "Wizards of the Coast")
    if contains("3.5") or contains("v.3.5") or contains("revised edition"):
      return ("dnd35", "DnD v3.5", "Wizards of the Coast")
    return ("dnd3e", "DnD 3rd Edition", "Wizards of the Coast")

  // TSR Basic family (DUNGEONS & DRAGONS without ADVANCED)
  if contains("dungeons & dragons"):
    if contains("rules cyclopedia"):
      return ("dnd_rc", "DnD Rules Cyclopedia", "TSR, Inc.")
    if contains("companion set") or contains("masters set") or contains("immortals set"):
      return ("dnd_becmi", "DnD BECMI", "TSR, Inc.")
    if contains("basic set") and contains("expert set"):
      return ("dnd_bx", "DnD B/X", "TSR, Inc.")
    if contains("basic set"):
      return ("dnd_basic", "DnD Basic", "TSR")
    if contains("expert set"):
      return ("dnd_bx", "DnD B/X", "TSR, Inc.")   // Expert-only reference → B/X
    // DUNGEONS & DRAGONS + TSR, no set marker
    if contains("tsr"):
      return ("dnd_basic", "DnD Basic", "TSR")     // conservative default
    if contains("tactical studies rules"):
      return ("dnd_og", "DnD Original", "TSR")

  // Other systems
  if contains("daggerheart") and contains("darrington press"):
    return ("daggerheart", "Daggerheart", "Darrington Press")

  return ("unknown", "Unknown", "Unknown")
```

`copyrightYear` is extracted with `©(\d{4})` or `Copyright (\d{4})` from the same text.

---

## Where Detection Runs

**Phase 1**, in `PdfConverter`, after extracting pages 1 and 2 with a plain
`PDFTextStripper` (no column logic needed — copyright text is always single-column).

New class: `RulesetDetector.java` in `converters/`.

```java
public record RulesetInfo(String id, String name, String publisher) {}

public class RulesetDetector {
    public RulesetInfo detect(PDDocument doc) { ... }
}
```

`PdfConverter.convert()` calls `RulesetDetector` first, before the main page loop, and
passes the result to the output pipeline.

---

## Output

### Markdown frontmatter

The `.md` file gains a YAML frontmatter block prepended before all other content:

```markdown
---
source: DL1 - Dragons_of_Despair.pdf
ruleset: adnd1e
ruleset_name: AD&D 1st Edition
publisher: TSR, Inc.
---

# TABLE OF CONTENTS
...
```

### JSON output

The JSON gains a top-level `metadata` object wrapping the existing components array:

```json
{
  "metadata": {
    "source": "DL1 - Dragons_of_Despair.pdf",
    "ruleset": "adnd1e",
    "ruleset_name": "AD&D 1st Edition",
    "publisher": "TSR, Inc."
  },
  "components": [
    { "component_type": "LOCATION", ... },
    ...
  ]
}
```

**Breaking change:** `JsonWriter` is updated to emit the wrapper object. The current flat
array format is replaced. Downstream consumers must be updated.

---

## Implementation

| File | Change |
|------|--------|
| `RulesetDetector.java` *(new)* | Detection logic + `RulesetInfo` record |
| `PdfConverter.java` | Call `RulesetDetector` on pages 1–2; pass result downstream |
| `FileToMarkdownConverter.java` | Prepend YAML frontmatter to `.md` output |
| `JsonWriter.java` | Wrap components array in `{ metadata, components }` |

---

## Test Cases

| Module | Year | Expected ID |
|--------|------|-------------|
| DL1 | 1984 | `adnd1e` |
| DL15 | 1988 | `adnd1e` |
| B2 | 1980 | `dnd_basic` |
| DD35 / Forge of Fury | 2000 | `dnd3e` |
| X2 Castle Amber | 1981 | `dnd_bx` (Expert Set module) |
| CM-series module | 1984 | `dnd_becmi` |
| AD&D 2e module (to be added) | 1990+ | `adnd2e` |
| No recognizable trademark | — | `unknown` |
