# Text Extraction Fixes Spec

Issues identified in B4-page05-middle markdown output that need to be fixed.
Current output is accepted as baseline; each fix should update the expected markdown.

## Tasks

### 1. Empty sub-page sections from left column illustration
The left column of the two-column zone contains an illustration with minimal/no text.
When the layout PDF splits this into sub-pages, the illustration sub-page and any
small text-only sub-page above it extract as empty or whitespace-only sections.
These produce empty `---` separated blocks in the markdown output:

```
 

---

 
The first dungeon level...
```

**Fix:** PdfConverter should suppress empty or whitespace-only sections instead of
emitting them as blank blocks between horizontal rules.

### 2. Italic text not properly delimited
The phrase `must have these supplies soon or they will die. In your descriptions,`
should be wrapped in `_..._` italics. Current output uses `*...*` with a trailing
space inside the delimiter:

```
*must have these supplies soon or they will die. In your descriptions, *
```

**Fix:** Ensure italic runs use `_..._` markers and trim whitespace inside delimiters.

### 3. Bold text has trailing space inside delimiter
Bold headings like `**Wandering Monsters **` have a trailing space before the
closing `**`. Should be `**Wandering Monsters**`.

**Fix:** Trim whitespace inside bold delimiters during markdown generation.

### 4. Letter-spaced heading text (future)
The "PART 2: TIERS 1 AND 2" heading in the original PDF uses letter-spacing,
which can cause PDFTextStripper to insert spaces between characters or extract
partial fragments. Currently the heading extracts correctly in this fixture, but
on the full page the left-column crop produces fragments like `PART 2: TIERS 1 AN`.

**Fix:** Detect and merge letter-spaced text runs where inter-character gaps are
uniform and smaller than a word space.

### 5. Table fixture content not detected (B4Page05TableTest)
The B4-page05-table.pdf fixture contains a wandering monster table with sparse
horizontal rules between rows. The layout analyzer detects only the footer,
missing all table content. All 8 monster entry tests fail.

**Fix:** Table rows with thin horizontal rules and sparse text need a different
detection strategy — possibly lowering the ink threshold for table regions or
using the table detection pass to handle ruled tables.
