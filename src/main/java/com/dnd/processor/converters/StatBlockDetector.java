package com.dnd.processor.converters;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Detects AD&D stat blocks in raw extracted text and wraps them in fenced
 * ```stat code blocks so they survive Markdown formatting and are trivially
 * consumed by Phase 2.
 *
 * Detects the following formats (see stat-block-formats-spec.md):
 *   F1 – Inline semicolon-separated  (". AL LE; MV 12; hp 22; ...")
 *   F2 – Labeled block               (FREQUENCY: / ARMOR CLASS: / etc.)
 *   F3 – Narrative character block   (Strength 13 / THAC0 6 / etc.)
 *   F4 – NPC Capsule section         ("NPC Capsule" header)
 *   F5 – Compact card                (STR 16 WIS 13 CON 12 THAC0 14 ...)
 *   F6 – Army unit                   (N Name (Age/Size) hp)
 *
 * Detection runs BEFORE toMarkdown() so the heading tagger never sees raw
 * stat-block field lines.
 */
public class StatBlockDetector {

    // ── F1: Inline Semicolon ─────────────────────────────────────────────────

    private static final Pattern F1_AL_ANCHOR = Pattern.compile(
            "\\.\\s+AL\\s+(LE|LG|LN|NE|NG|N|CE|CG|CN|TN)\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Field labels that appear semicolon-separated in F1 blocks
    private static final Pattern[] F1_FIELD_PATTERNS = {
        Pattern.compile("[;:]\\s*MV\\s+",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*hp\\s+",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*AC\\s+\\d",     Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*HD\\s+",        Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*#AT\\s+",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*Dmg\\s+",       Pattern.CASE_INSENSITIVE),
        Pattern.compile("[;:]\\s*THAC0\\s+",     Pattern.CASE_INSENSITIVE),
    };

    // ── F2: Labeled Block ────────────────────────────────────────────────────

    private static final Pattern F2_FIELD_LINE = Pattern.compile(
            "^(FREQUENCY|#\\s*APPEARING|ARMOR\\s+CLASS|MOVE|HIT\\s+DICE|" +
            "%\\s*IN\\s+LAIR|TREASURE\\s+TYPE|#\\s*ATTACKS?|DAMAGE|" +
            "SPECIAL\\s+ATTACKS?|SPECIAL\\s+DEFENSES?|MAGIC\\s+RESISTANCE|" +
            "INTELLIGENCE|ALIGNMENT|SIZE|PSIONIC\\s+ABILITY|XP\\s+VALUE)" +
            "[:\\s]",
            Pattern.CASE_INSENSITIVE
    );

    // ── F3: Narrative Character ───────────────────────────────────────────────

    private static final Pattern F3_THAC0 = Pattern.compile(
            "\\bTHAC0\\s+\\d",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern F3_ABILITY = Pattern.compile(
            "\\b(Strength|Intelligence|Wisdom|Dexterity|Constitution|Charisma)\\s+\\d",
            Pattern.CASE_INSENSITIVE
    );

    // ── F4: NPC Capsule ───────────────────────────────────────────────────────

    private static final Pattern F4_HEADER = Pattern.compile(
            "^NPC\\s+Capsule\\s*$",
            Pattern.CASE_INSENSITIVE
    );

    // ── F5: Compact Card ─────────────────────────────────────────────────────

    private static final Pattern F5_ABILITY_LINE = Pattern.compile(
            "\\bSTR\\s+\\d+\\b.*\\b(?:WIS|DEX)\\s+\\d+\\b.*\\b(?:CON|CHR|INT)\\s+\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ── F6: Army Unit ─────────────────────────────────────────────────────────

    private static final Pattern F6_UNIT_LINE = Pattern.compile(
            "^\\d+\\s+[A-Z][a-z]+.*\\([A-Za-z/]+\\)\\s+\\d+\\s+hp\\b",
            Pattern.CASE_INSENSITIVE
    );

    // ── F7: D&D Basic Inline (comma-separated) ─────────────────────────────────
    // e.g. "Taverner (AC 9, LVL 0, hp 6, #AT 1, D 1-6, ML 8)"
    // Requires AC + hp + at least one of #AT / ML / D N-N / MV inside parens.

    private static final Pattern F7_ENOUGH_FIELDS = Pattern.compile(
            "\\bAC\\s+\\d+\\b.*\\bhp\\s+\\d+\\b.*(?:#AT|\\b(?:ML|MV|D\\s+\\d+-\\d+)\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );

    // Pre-compiled word-boundary AC check (matches "AC" even at end-of-line, unlike "ac ")
    private static final Pattern HAS_AC = Pattern.compile("\\bAC\\b", Pattern.CASE_INSENSITIVE);

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main entry point. Runs all format detectors in priority order on the
     * fully assembled document text (after header/footer stripping, before
     * toMarkdown()).
     */
    public String markStatBlocks(String text) {
        text = markF4NpcCapsule(text);
        text = markF2LabeledBlock(text);
        text = markF3NarrativeCharacter(text);
        text = markF5CompactCard(text);
        text = markF1InlineSemicolon(text);
        text = markF6ArmyUnit(text);
        text = markF7BasicInline(text);
        return text;
    }

    // ── F4: NPC Capsule ───────────────────────────────────────────────────────

    private String markF4NpcCapsule(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < lines.length) {
            if (isInsideFence(lines, i)) {
                result.append(lines[i]).append("\n");
                i++;
                continue;
            }

            if (F4_HEADER.matcher(lines[i].trim()).matches()) {
                // Collect everything until next blank line or heading
                int start = i;
                i++;
                List<String> block = new ArrayList<>();
                block.add(lines[start].trim());
                while (i < lines.length) {
                    String t = lines[i].trim();
                    if (t.isEmpty() || t.startsWith("##") || t.startsWith("###") || t.equals("---")) break;
                    block.add(t);
                    i++;
                }
                result.append("\n```stat\n");
                for (String l : block) result.append(l).append("\n");
                result.append("```\n\n");
            } else {
                result.append(lines[i]).append("\n");
                i++;
            }
        }

        return result.toString();
    }

    // ── F2: Labeled Block ────────────────────────────────────────────────────

    private String markF2LabeledBlock(String text) {
        String[] lines = text.split("\n", -1);

        // Pass 1: identify F2 block ranges
        // Each entry: [nameLineIdx (-1 if none), firstFieldIdx, lastFieldIdx]
        List<int[]> blocks = new ArrayList<>();
        Set<Integer> alreadyFenced = fencedLineIndices(lines);

        int i = 0;
        while (i < lines.length) {
            if (alreadyFenced.contains(i)) { i++; continue; }

            String trimmed = lines[i].trim();
            if (!isF2FieldLine(trimmed)) { i++; continue; }

            // Count consecutive F2 field lines
            int firstField = i;
            int j = i;
            while (j < lines.length && !alreadyFenced.contains(j)
                    && !lines[j].trim().isEmpty()
                    && isF2FieldLine(lines[j].trim())) {
                j++;
            }
            int fieldCount = j - firstField;

            if (fieldCount >= 3) {
                // Look for creature name: nearest preceding non-blank, non-F2 line
                int nameLine = -1;
                for (int k = firstField - 1; k >= 0 && k >= firstField - 4; k--) {
                    String prev = lines[k].trim();
                    if (prev.isEmpty() || prev.equals("---") || prev.startsWith("```")
                            || prev.startsWith("##") || prev.startsWith("###")) {
                        break;
                    }
                    if (!isF2FieldLine(prev) && !alreadyFenced.contains(k)) {
                        nameLine = k;
                        break;
                    }
                }
                blocks.add(new int[]{nameLine, firstField, j - 1});
            }
            i = j == i ? i + 1 : j;
        }

        if (blocks.isEmpty()) return text;

        // Pass 2: reconstruct text, replacing identified ranges with stat fences
        Set<Integer> consumed = new HashSet<>();
        Map<Integer, int[]> blockByStart = new LinkedHashMap<>();
        for (int[] block : blocks) {
            int startIdx = block[0] >= 0 ? block[0] : block[1];
            if (!blockByStart.containsKey(startIdx)) {
                blockByStart.put(startIdx, block);
                if (block[0] >= 0) consumed.add(block[0]);
                for (int k = block[1]; k <= block[2]; k++) consumed.add(k);
            }
        }

        StringBuilder result = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (blockByStart.containsKey(k)) {
                int[] block = blockByStart.get(k);
                result.append("\n```stat\n");
                if (block[0] >= 0) result.append(lines[block[0]].trim()).append("\n");
                for (int m = block[1]; m <= block[2]; m++) {
                    result.append(lines[m].trim()).append("\n");
                }
                result.append("```\n\n");
            } else if (!consumed.contains(k)) {
                result.append(lines[k]).append("\n");
            }
        }

        return result.toString();
    }

    // ── F3: Narrative Character ───────────────────────────────────────────────

    private String markF3NarrativeCharacter(String text) {
        // Process paragraph by paragraph
        return processParagraphs(text, para -> {
            // Needs THAC0 + at least 2 ability score labels
            if (!F3_THAC0.matcher(para).find()) return null;
            int abilityCount = 0;
            java.util.regex.Matcher m = F3_ABILITY.matcher(para);
            while (m.find()) abilityCount++;
            if (abilityCount < 2) return null;
            return para; // wrap the whole paragraph
        });
    }

    // ── F5: Compact Card ─────────────────────────────────────────────────────

    private String markF5CompactCard(String text) {
        String[] lines = text.split("\n", -1);
        Set<Integer> alreadyFenced = fencedLineIndices(lines);
        List<int[]> blocks = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            if (alreadyFenced.contains(i)) continue;
            if (F5_ABILITY_LINE.matcher(lines[i].trim()).find()) {
                // Name is the preceding non-blank line; block extends to the next blank line
                int nameLine = -1;
                for (int k = i - 1; k >= 0 && k >= i - 3; k--) {
                    String prev = lines[k].trim();
                    if (prev.isEmpty()) break;
                    if (!alreadyFenced.contains(k) && !prev.startsWith("##")) {
                        nameLine = k;
                        break;
                    }
                }
                // Extend forward to end of card block
                int j = i;
                while (j < lines.length && !alreadyFenced.contains(j) && !lines[j].trim().isEmpty()) {
                    j++;
                }
                blocks.add(new int[]{nameLine, i, j - 1});
            }
        }

        if (blocks.isEmpty()) return text;

        Set<Integer> consumed = new HashSet<>();
        Map<Integer, int[]> blockByStart = new LinkedHashMap<>();
        for (int[] block : blocks) {
            int startIdx = block[0] >= 0 ? block[0] : block[1];
            if (!blockByStart.containsKey(startIdx)) {
                blockByStart.put(startIdx, block);
                if (block[0] >= 0) consumed.add(block[0]);
                for (int k = block[1]; k <= block[2]; k++) consumed.add(k);
            }
        }

        StringBuilder result = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (blockByStart.containsKey(k)) {
                int[] block = blockByStart.get(k);
                result.append("\n```stat\n");
                if (block[0] >= 0) result.append(lines[block[0]].trim()).append("\n");
                for (int m = block[1]; m <= block[2]; m++) {
                    result.append(lines[m].trim()).append("\n");
                }
                result.append("```\n\n");
            } else if (!consumed.contains(k)) {
                result.append(lines[k]).append("\n");
            }
        }
        return result.toString();
    }

    // ── F1: Inline Semicolon ─────────────────────────────────────────────────

    private String markF1InlineSemicolon(String text) {
        // Ensure blockquote runs and plain-text runs are always in separate
        // paragraphs so processParagraphs() never sees them merged.
        text = splitMixedBlockquoteParagraphs(text);
        return processParagraphs(text, para -> {
            if (para.startsWith("```")) return null;

            // Join lines of the paragraph into one candidate string
            String joined = Arrays.stream(para.split("\n", -1))
                    .map(String::trim)
                    .filter(l -> !l.isEmpty())
                    .reduce((a, b) -> a + " " + b)
                    .orElse(para);

            if (F1_AL_ANCHOR.matcher(joined).find()) {
                return joined;
            }
            if (hasEnoughF1Fields(joined)) {
                return joined;
            }
            return null;
        });
    }

    /** Returns true if the text has 3+ distinct semicolon-prefixed F1 field labels. */
    private boolean hasEnoughF1Fields(String text) {
        int count = 0;
        for (Pattern p : F1_FIELD_PATTERNS) {
            if (p.matcher(text).find()) {
                count++;
                if (count >= 3) return true;
            }
        }
        return false;
    }

    // ── F6: Army Unit ─────────────────────────────────────────────────────────

    private String markF6ArmyUnit(String text) {
        String[] lines = text.split("\n", -1);
        Set<Integer> alreadyFenced = fencedLineIndices(lines);
        List<int[]> blocks = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            if (alreadyFenced.contains(i)) { i++; continue; }

            if (F6_UNIT_LINE.matcher(lines[i].trim()).find()) {
                int start = i;
                while (i < lines.length && !alreadyFenced.contains(i)
                        && !lines[i].trim().isEmpty()
                        && F6_UNIT_LINE.matcher(lines[i].trim()).find()) {
                    i++;
                }
                if (i - start >= 2) {
                    blocks.add(new int[]{start, i - 1});
                }
            } else {
                i++;
            }
        }

        if (blocks.isEmpty()) return text;

        Set<Integer> consumed = new HashSet<>();
        Map<Integer, int[]> blockByStart = new LinkedHashMap<>();
        for (int[] block : blocks) {
            if (!blockByStart.containsKey(block[0])) {
                blockByStart.put(block[0], block);
                for (int k = block[0]; k <= block[1]; k++) consumed.add(k);
            }
        }

        StringBuilder result = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (blockByStart.containsKey(k)) {
                int[] block = blockByStart.get(k);
                result.append("\n```stat\n");
                for (int m = block[0]; m <= block[1]; m++) {
                    result.append(lines[m].trim()).append("\n");
                }
                result.append("```\n\n");
            } else if (!consumed.contains(k)) {
                result.append(lines[k]).append("\n");
            }
        }
        return result.toString();
    }

    // Sentence boundary inside the "before-paren" text: punctuation + whitespace + capital.
    // Used to strip leading sentence fragments from lines like "tire fortress.  Two men-at-arms ("
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.!?]\\s+[A-Z]");

    // ── F7: D&D Basic Inline ──────────────────────────────────────────────────

    /**
     * Detects D&D Basic compact inline stat blocks of the form:
     *   Name (AC N, LVL/F/HD N, hp N, #AT N, D N-N, ML N)
     * Entries may wrap across two lines (unclosed paren on the first line).
     * Consecutive entries are grouped into a single fence.
     * An isolated entry is fenced only if it has AC + hp + a third field.
     *
     * Prose fragments stripped from the boundaries of fence lines are preserved:
     *   prefix (text before the stat block name) is emitted as prose before the fence.
     *   suffix (text after the closing ')') is emitted as prose after the fence.
     */
    private String markF7BasicInline(String text) {
        String[] lines = text.split("\n", -1);
        Set<Integer> alreadyFenced = fencedLineIndices(lines);

        // Prose fragments stripped from the boundaries of fence lines.
        Map<Integer, String> fenceLinePrefix = new HashMap<>();  // prose before stat block start
        Map<Integer, String> fenceLineSuffix = new HashMap<>();  // prose after closing ')'

        // Build logical entries — each entry is a list of consecutive line indices
        List<List<Integer>> entries = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            if (alreadyFenced.contains(i)) { i++; continue; }

            String line = lines[i].trim();
            // Must contain a '('. Also require AC on the line unless the line starts
            // with '(' (multi-line entry where AC may appear on the next line).
            if (!line.contains("(")) { i++; continue; }
            if (!line.startsWith("(") && !HAS_AC.matcher(line).find()) { i++; continue; }

            // Save original in case we reject this candidate after stripping
            String originalLine = lines[i];

            // Strip any leading sentence fragment before the creature name so the
            // fence output starts cleanly at the name (or at '(' if no name).
            //   ". (AC 5..."            → strip ".", fence starts at '('
            //   "by. (AC 5..."          → strip "by.", fence starts at '('
            //   "tire fortress.  Two men (…" → strip "tire fortress.  ", fence at "Two"
            {
                int parenIdx = line.indexOf('(');
                String beforeParen = line.substring(0, parenIdx).trim();
                if (beforeParen.isEmpty()) {
                    // nothing to strip
                } else if (!beforeParen.matches(".*[a-zA-Z0-9].*")) {
                    // Only punctuation/whitespace before '(' — strip it, start fence at '('
                    fenceLinePrefix.put(i, beforeParen);
                    line = line.substring(line.indexOf('('));
                    lines[i] = line;
                } else if (beforeParen.matches(".*[a-zA-Z]\\.")) {
                    // Text ends with letter+period (sentence end) — strip, start fence at '('
                    fenceLinePrefix.put(i, beforeParen);
                    line = line.substring(line.indexOf('('));
                    lines[i] = line;
                } else {
                    // Look for a sentence boundary (punctuation + space + capital) inside beforeParen
                    java.util.regex.Matcher sbm = SENTENCE_BOUNDARY.matcher(beforeParen);
                    int lastSBEnd = -1;
                    while (sbm.find()) lastSBEnd = sbm.end();
                    if (lastSBEnd >= 0) {
                        String nameAndRest = beforeParen.substring(lastSBEnd - 1);
                        int namePos = line.indexOf(nameAndRest);
                        if (namePos >= 0) {
                            fenceLinePrefix.put(i, line.substring(0, namePos).trim());
                            line = line.substring(namePos);
                            lines[i] = line;
                        }
                    }
                }
            }

            // Reject if text before the first '(' is too long to be a creature name
            if (!hasShortNameBefore(line)) {
                lines[i] = originalLine;
                fenceLinePrefix.remove(i);
                i++;
                continue;
            }

            List<Integer> entryLines = new ArrayList<>();
            entryLines.add(i);
            String accumulated = line;
            int j = i + 1;

            // If the paren isn't closed, absorb continuation lines.
            // Allow skipping up to one blank line (paragraph separator injected by
            // PDFTextStripper for wrapped stat-block lines).
            int blanksSkipped = 0;
            while (!isParenBalanced(accumulated) && j < lines.length
                    && !alreadyFenced.contains(j)) {
                String next = lines[j].trim();
                if (next.isEmpty()) {
                    if (blanksSkipped < 1) { blanksSkipped++; j++; continue; }
                    break;
                }
                blanksSkipped = 0;
                entryLines.add(j);
                accumulated += " " + next;
                j++;
            }

            if (F7_ENOUGH_FIELDS.matcher(accumulated).find()) {
                // Trim any prose after the closing ')' from the last entry line so
                // the fence ends cleanly at ')'.  Store the tail to emit as prose.
                int lastIdx = entryLines.get(entryLines.size() - 1);
                String lastContent = lines[lastIdx].trim();
                int closeParen = lastContent.lastIndexOf(')');
                if (closeParen >= 0 && closeParen < lastContent.length() - 1) {
                    String tail = lastContent.substring(closeParen + 1).trim();
                    if (!tail.isEmpty()) fenceLineSuffix.put(lastIdx, tail);
                    lines[lastIdx] = lastContent.substring(0, closeParen + 1);
                }
                entries.add(entryLines);
                i = j;
            } else {
                lines[i] = originalLine;
                fenceLinePrefix.remove(i);
                i++;
            }
        }

        if (entries.isEmpty()) return text;

        // Group consecutive entries (no blank lines and no prose tail between them)
        List<List<List<Integer>>> groups = new ArrayList<>();
        List<List<Integer>> currentGroup = new ArrayList<>();
        currentGroup.add(entries.get(0));

        for (int k = 1; k < entries.size(); k++) {
            List<Integer> prev = entries.get(k - 1);
            List<Integer> curr = entries.get(k);
            int prevLast = prev.get(prev.size() - 1);
            int currFirst = curr.get(0);

            boolean adjacent = true;
            // A prose suffix on the previous entry's last line means there's content
            // between the two entries — keep them in separate fences.
            if (fenceLineSuffix.containsKey(prevLast)) {
                adjacent = false;
            } else {
                for (int m = prevLast + 1; m < currFirst; m++) {
                    if (lines[m].trim().isEmpty()) { adjacent = false; break; }
                }
            }

            if (adjacent) {
                currentGroup.add(curr);
            } else {
                groups.add(currentGroup);
                currentGroup = new ArrayList<>();
                currentGroup.add(curr);
            }
        }
        groups.add(currentGroup);

        // Determine which groups to fence
        Set<Integer> consumed = new HashSet<>();
        Map<Integer, List<List<Integer>>> blockByStart = new LinkedHashMap<>();

        for (List<List<Integer>> group : groups) {
            boolean fence = group.size() >= 2; // always fence multi-entry groups
            if (!fence) {
                // Single entry: fence only if field count is convincing
                List<Integer> entry = group.get(0);
                StringBuilder sb = new StringBuilder();
                for (int idx : entry) { if (sb.length() > 0) sb.append(" "); sb.append(lines[idx].trim()); }
                fence = F7_ENOUGH_FIELDS.matcher(sb.toString()).find();
            }
            if (fence) {
                int startLine = group.get(0).get(0);
                blockByStart.put(startLine, group);
                for (List<Integer> entry : group) consumed.addAll(entry);
            }
        }

        if (blockByStart.isEmpty()) return text;

        StringBuilder result = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (blockByStart.containsKey(k)) {
                List<List<Integer>> group = blockByStart.get(k);
                // Emit any prose prefix stripped from the start line
                String prefix = fenceLinePrefix.get(group.get(0).get(0));
                if (prefix != null && !prefix.isEmpty()) {
                    result.append(prefix).append("\n");
                }
                result.append("\n```stat\n");
                for (List<Integer> entry : group)
                    for (int idx : entry)
                        result.append(lines[idx].trim()).append("\n");
                result.append("```\n\n");
                // Emit any prose suffix from the last line of the group
                List<Integer> lastEntry = group.get(group.size() - 1);
                int lastLineIdx = lastEntry.get(lastEntry.size() - 1);
                String suffix = fenceLineSuffix.get(lastLineIdx);
                if (suffix != null && !suffix.isEmpty()) {
                    result.append(suffix).append("\n");
                }
            } else if (!consumed.contains(k)) {
                result.append(lines[k]).append("\n");
            }
        }
        return result.toString();
    }

    /**
     * Returns true when the text before the first '(' is a short name (≤ 3 words),
     * optionally preceded by a label like "Examples: ".
     * Rejects prose leads such as "There are 6 males total (".
     */
    private boolean hasShortNameBefore(String line) {
        int p = line.indexOf('(');
        if (p < 0) return false;
        String before = line.substring(0, p).trim();
        // Strip a leading "Label: " prefix
        int colonSpace = before.lastIndexOf(": ");
        if (colonSpace >= 0) before = before.substring(colonSpace + 2).trim();
        // Strip leading markdown bold/italic markers
        before = before.replaceAll("^\\*+", "").trim();
        if (before.isEmpty()) return true;
        // If the text before '(' contains a sentence boundary (punctuation + whitespace
        // + capital letter), use only the text after the last such boundary as the name.
        // e.g. "tire fortress.  Two men-at-arms" → name = "Two men-at-arms"
        java.util.regex.Matcher m = SENTENCE_BOUNDARY.matcher(before);
        int lastEnd = -1;
        while (m.find()) lastEnd = m.end();
        if (lastEnd >= 0) {
            // m.end()-1 is the position of the capital letter starting the new sentence
            before = before.substring(lastEnd - 1).trim();
        }
        return before.split("\\s+").length <= 3;
    }

    /**
     * Returns true when there are ≤ 3 words of prose after the last ')'.
     * Rejects entries like "...) who will attack. If all these males are killed..."
     */
    private boolean hasShortTailAfter(String text) {
        int p = text.lastIndexOf(')');
        if (p < 0) return true;
        String tail = text.substring(p + 1).trim();
        if (tail.isEmpty()) return true;
        return tail.split("\\s+").length <= 3;
    }

    /**
     * Returns true once the first opened paren has been matched (depth reaches 0).
     * This stops accumulation as soon as the stat block's closing ')' is found,
     * preventing the absorber from running into a second stat block on the same line.
     */
    private boolean isParenBalanced(String text) {
        int depth = 0;
        for (char c : text.toCharArray()) {
            if (c == '(') depth++;
            else if (c == ')') {
                depth--;
                if (depth <= 0) return true;
            }
        }
        return depth <= 0;
    }

    // ── Shared Helpers ────────────────────────────────────────────────────────

    /** Checks whether the line at index i is inside an existing ```stat fence. */
    private boolean isInsideFence(String[] lines, int i) {
        boolean inFence = false;
        for (int k = 0; k < i; k++) {
            String t = lines[k].trim();
            if (t.startsWith("```")) inFence = !inFence;
        }
        return inFence;
    }

    /** Returns the set of line indices that are inside existing ``` fences. */
    private Set<Integer> fencedLineIndices(String[] lines) {
        Set<Integer> fenced = new HashSet<>();
        boolean inFence = false;
        for (int k = 0; k < lines.length; k++) {
            if (lines[k].trim().startsWith("```")) {
                inFence = !inFence;
                fenced.add(k);
                continue;
            }
            if (inFence) fenced.add(k);
        }
        return fenced;
    }

    private boolean isF2FieldLine(String trimmed) {
        return F2_FIELD_LINE.matcher(trimmed).find();
    }

    /**
     * Ensures that blockquote-prefixed lines ("> ") and plain lines never share
     * the same blank-line paragraph. Whenever a run of ">" lines is immediately
     * followed by non-">" lines (or vice versa), a blank line is inserted so that
     * processParagraphs() sees them as separate paragraphs and never joins them.
     *
     * This prevents stat block detection from ingesting "> " markers that the
     * column-aware stripper placed on read-aloud boxed text.
     */
    private String splitMixedBlockquoteParagraphs(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        Boolean prevWasBlockquote = null;
        boolean inFence = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                result.append(line).append("\n");
                prevWasBlockquote = null;
                continue;
            }
            if (inFence) {
                result.append(line).append("\n");
                continue;
            }
            if (trimmed.isEmpty() || trimmed.equals("---")) {
                result.append(line).append("\n");
                prevWasBlockquote = null;
                continue;
            }

            boolean isBlockquote = trimmed.startsWith(">");
            if (prevWasBlockquote != null && prevWasBlockquote != isBlockquote) {
                result.append("\n"); // blank line forces new paragraph
            }
            result.append(line).append("\n");
            prevWasBlockquote = isBlockquote;
        }

        return result.toString();
    }

    /**
     * Splits text into paragraph blocks (separated by blank lines) and applies
     * the given detector to each. If the detector returns non-null, the paragraph
     * is replaced by a ```stat fence containing the returned text.
     *
     * For blockquote paragraphs (all lines start with "> "), blockquote prefixes
     * are stripped before detection. If the stripped content is a stat block it is
     * wrapped in a fence (without the "> " markers); otherwise the original
     * blockquote paragraph is preserved unchanged.
     *
     * Normalises \r\n to \n before splitting so PDFBox's Windows line-endings
     * are handled correctly.
     */
    private String processParagraphs(String text, ParagraphDetector detector) {
        // Normalise line endings — PDFBox produces \r\n which breaks blank-line splitting
        String normalised = text.replace("\r\n", "\n").replace("\r", "\n");
        String[] parts = normalised.split("\n{2,}");
        StringBuilder result = new StringBuilder();

        for (int idx = 0; idx < parts.length; idx++) {
            if (idx > 0) result.append("\n\n");

            String part = parts[idx];
            String trimmed = part.trim();

            // Skip empty parts, existing fences, and page separators
            if (trimmed.isEmpty() || trimmed.startsWith("```")
                    || trimmed.equals("---") || part.contains("```stat")) {
                result.append(part);
                continue;
            }

            // For blockquote paragraphs: strip "> " prefixes and try detection.
            // If not a stat block, preserve the original blockquote lines.
            String candidate = trimmed.startsWith(">")
                    ? stripBlockquotePrefixes(trimmed)
                    : trimmed;

            String replacement = detector.detect(candidate);
            if (replacement != null) {
                result.append("```stat\n").append(replacement).append("\n```");
            } else {
                result.append(part);
            }
        }

        return result.toString();
    }

    /**
     * Strips leading "> " or ">" blockquote prefixes from every line of a paragraph.
     */
    private String stripBlockquotePrefixes(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append("\n");
            String l = lines[i];
            if (l.startsWith("> "))      sb.append(l.substring(2));
            else if (l.startsWith(">"))  sb.append(l.substring(1));
            else                         sb.append(l);
        }
        return sb.toString();
    }

    @FunctionalInterface
    private interface ParagraphDetector {
        /** Returns the text to place inside the fence, or null if not a stat block. */
        String detect(String paragraph);
    }
}
