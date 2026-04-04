# Whitespace Cleanup Phase — Spec

## Problem

PDF text extraction from some modules produces character-fragmented text: every word
is broken into 1–3 character chunks separated by spaces, because the PDF stores each
glyph (or small ligature group) as a separate text object.

Example input (B2 raw extraction):
```
Th e th ree fa ction s d o n ot g et a long w ell. E a ch fa ction is s u re th at
on ly its m em b ers k n ow th e p rop er w a y to restor e th e lost g rea tn ess.
```

Expected output:
```
The three factions do not get along well. Each faction is sure that
only its members know the proper way to restore the lost greatness.
```

## Phase

CLI flag: `--phase whitespace-cleanup`  
Input:  `.md` file (output of `convert` phase)  
Output: `<name>-clean.md` in the same output directory

## Pass Structure

The cleanup runs multiple passes in order. Pass 1 is the only required pass for
the current problem. Future passes can be added as new `CleanupPass` implementations.

---

## Pass 1 — Fragment Reconstruction

### Detection

A text run (contiguous span of tokens on a line) is **fragmented** if:
- It contains ≥ 5 tokens, AND
- ≥ 60% of alphabetic tokens are 1–3 characters long, AND
- The average alphabetic-token length < 3.5 characters

Detection is applied per-line. Lines below the threshold are left unchanged.

Markdown formatting lines (headings `#`, fenced code `` ` ``, horizontal rules `---`,
blockquote `>`) are passed through unchanged.

### Reconstruction Algorithm

1. **Collect** consecutive fragmented lines into a single "fragment block" (blank lines
   end a block).
2. **Strip spaces** from the block: concatenate all tokens removing spaces, preserving
   punctuation attached to tokens (e.g., trailing `.`, `,`).
3. **Segment** the concatenated string back into words using a dictionary-based DP:
   - Word list: bundled `english_words.txt` resource (~5 000 most-common words +
     a D&D supplement list of ~200 domain terms).
   - Score: `log(frequency_rank⁻¹)` per word — higher-frequency words score higher.
   - DP finds the segmentation that maximises total score.
   - Unknown substrings (no dictionary path) fall back to the original concatenated
     form for that segment.
4. **Restore** sentence capitalisation: first word of a sentence (after `.!?`) is
   title-cased; remaining words are lower-cased unless they were ALL-CAPS in the
   original (e.g. NPC, HD).
5. **Re-wrap** at 80 characters.

### Fallback

If segmentation fails for a line (no complete DP path), the original line is kept
and a `[CLEANUP-FAILED]` marker is appended for manual review.

---

## Debug Output

When `--debug` is passed, for every modified paragraph print:

```
[BEFORE] Th e th ree fa ction s d o n ot g et a long w ell.
[AFTER]  The three factions do not get along well.
```

## Files

| File | Purpose |
|------|---------|
| `src/main/resources/english_words.txt` | Bundled word list (word\trank per line) |
| `src/main/java/com/dnd/processor/pipeline/CleanupPass.java` | Interface |
| `src/main/java/com/dnd/processor/pipeline/FragmentReconstructionPass.java` | Pass 1 impl |
| `src/main/java/com/dnd/processor/pipeline/WhitespaceCleanup.java` | Orchestrator |
| `Main.java` | Add `whitespace-cleanup` case |
