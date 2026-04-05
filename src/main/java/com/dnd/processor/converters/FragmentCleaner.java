package com.dnd.processor.converters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cleans up character-fragmented text produced by PDF extraction.
 *
 * Some PDFs store each glyph (or small ligature group) as a separate text
 * object, causing extractors to insert spaces between every 1-3 character
 * fragment. This class detects such lines and reconstructs the original
 * words using dictionary-based dynamic programming.
 *
 * Also collapses spurious blank lines that result from each PDF text object
 * being extracted as a separate line.
 */
public class FragmentCleaner {

    private static final int MIN_TOKEN_COUNT = 5;
    private static final double MIN_SHORT_FRACTION = 0.60;
    private static final double MAX_AVG_TOKEN_LENGTH = 3.5;
    private static final int DEFAULT_WRAP_WIDTH = 120;

    // word -> rank (1 = most common). Used for DP scoring.
    private final Map<String, Integer> wordRanks;
    private final int totalWords;
    private final int wrapWidth;

    public FragmentCleaner() {
        this(DEFAULT_WRAP_WIDTH, null);
    }

    public FragmentCleaner(int wrapWidth) {
        this(wrapWidth, null);
    }

    /**
     * @param wrapWidth  target line width for paragraph rewrapping
     * @param glossary   optional module-specific glossary resource path
     *                   (e.g. "glossaries/B4.txt"), loaded in addition to
     *                   the base english.txt and dnd_terms.txt dictionaries
     */
    public FragmentCleaner(int wrapWidth, String glossary) {
        this.wordRanks = loadDictionary(glossary);
        this.totalWords = wordRanks.size();
        this.wrapWidth = wrapWidth;
    }

    /**
     * Scans raw text for words not in the dictionary. Useful for discovering
     * module-specific terms that should be added to a glossary file.
     * Only considers tokens ≥ 4 alphabetic characters to skip fragments.
     *
     * @return sorted list of unknown words (lowercased, deduplicated)
     */
    public List<String> findUnknownWords(String text) {
        Set<String> unknown = new TreeSet<>();
        for (String line : text.split("\n")) {
            // Skip fragmented lines — their tokens are broken, not real words
            if (isFragmented(line)) continue;
            // Strip bold/italic markers before tokenizing
            String stripped = line.replace("**", " ").replace("*", " ");
            for (String token : stripped.split("[^a-zA-Z]+")) {
                if (token.length() < 4) continue;
                String lower = token.toLowerCase();
                if (!wordRanks.containsKey(lower)) {
                    unknown.add(lower);
                }
            }
        }
        return new ArrayList<>(unknown);
    }

    public String clean(String markdown) {
        String stripped = stripInterleavedBoldMarkers(markdown);
        String joined = rejoinHyphenatedLines(stripped);
        String reconstructed = reconstructFragments(joined);
        String scattered = collapseScatteredLetters(reconstructed);
        String apostrophes = collapseApostrophes(scattered);
        String collapsed = collapseBlankLines(apostrophes);
        String wrapped = rewrapParagraphs(collapsed);
        // Second pass: rewrapping may merge content from different raw lines,
        // creating new adjacent fragments or interleaved bold markers.
        String rescattered = collapseAdjacentFragments(wrapped);
        return stripInterleavedBoldMarkers(rescattered);
    }

    // ── Apostrophe collapse ──────────────────────────────────────────────

    /**
     * Removes spaces around apostrophes and hyphens introduced by PDF extraction.
     * E.g. "DM ' s" → "DM's", "won ' t" → "won't", "pale - skinned" → "pale-skinned"
     */
    String collapseApostrophes(String text) {
        String result = text;
        // " ' s" → "'s" (possessive)
        result = result.replaceAll(" ' s\\b", "'s");
        // " ' t" → "'t" (contractions: won't, can't, don't, etc.)
        result = result.replaceAll(" ' t\\b", "'t");
        // General: word ' word → word'word
        result = result.replaceAll("(\\w) ' (\\w)", "$1'$2");
        // Compound words: word - word → word-word (alpha on both sides)
        result = result.replaceAll("([a-zA-Z]) - ([a-zA-Z])", "$1-$2");
        return result;
    }

    // ── Scattered single-letter collapse ──────────────────────────────────

    /** Pattern matching 2+ single letters each separated by a single space. */
    private static final Pattern SCATTERED_LETTERS = Pattern.compile(
            "(?<![a-zA-Z])([a-zA-Z] ){2,}[a-zA-Z](?![a-zA-Z])");

    /** Pattern matching 2+ single digits each separated by a single space. */
    private static final Pattern SCATTERED_DIGITS = Pattern.compile(
            "(?<!\\d)(\\d )+\\d(?!\\d)");

    /**
     * Finds runs of single letters separated by spaces (e.g. "m a p") on
     * otherwise normal lines and collapses them into words if found in the
     * dictionary.  Also collapses fragmented tokens inside bold markers
     * (e.g. "**C ynidicea n.**" → "**Cynidicean.**").
     */
    String collapseScatteredLetters(String text) {
        // Pass 1: collapse single-digit runs (e.g. "6 0" → "60")
        Matcher dm = SCATTERED_DIGITS.matcher(text);
        StringBuilder dsb = new StringBuilder();
        while (dm.find()) {
            dm.appendReplacement(dsb, Matcher.quoteReplacement(dm.group().replace(" ", "")));
        }
        dm.appendTail(dsb);
        String result = dsb.toString();

        // Pass 2: collapse single-letter runs
        Matcher m = SCATTERED_LETTERS.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String collapsed = m.group().replace(" ", "");
            if (wordRanks.containsKey(collapsed.toLowerCase())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(collapsed));
            }
        }
        m.appendTail(sb);
        result = sb.toString();

        // Pass 3: collapse adjacent fragment pairs (e.g. "b ronze" → "bronze")
        result = collapseAdjacentFragments(result);

        // Pass 4: collapse fragmented tokens inside bold markers
        result = collapseBoldFragments(result);
        return result;
    }

    /**
     * Joins adjacent tokens where at least one is a short non-word fragment
     * and the combination is a known dictionary word.
     * E.g. "b ronze" → "bronze", "do or" → "door".
     */
    private String collapseAdjacentFragments(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) result.append("\n");
            result.append(collapseAdjacentFragmentsInLine(lines[i]));
        }
        return result.toString();
    }

    private String collapseAdjacentFragmentsInLine(String line) {
        // Split into tokens preserving separators
        List<String> tokens = new ArrayList<>();
        List<String> seps = new ArrayList<>();
        int pos = 0;
        while (pos < line.length()) {
            if (line.charAt(pos) == ' ') {
                int start = pos;
                while (pos < line.length() && line.charAt(pos) == ' ') pos++;
                seps.add(line.substring(start, pos));
            } else {
                int start = pos;
                while (pos < line.length() && line.charAt(pos) != ' ') pos++;
                tokens.add(line.substring(start, pos));
                if (seps.size() < tokens.size() - 1) seps.add("");
            }
        }
        // Leading space handling
        if (line.startsWith(" ")) {
            // seps has one extra at the start — prepend to first token
            if (!tokens.isEmpty() && !seps.isEmpty()) {
                tokens.set(0, seps.remove(0) + tokens.get(0));
            }
        }

        if (tokens.size() < 2) return line;

        // Try joining pairs: check if token[i] + token[i+1] forms a word
        // when at least one token has a short alpha fragment
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < tokens.size() - 1; i++) {
                // Try joining 3 tokens first (e.g. "ab ronze" where "a"+"b" already merged)
                if (i + 2 < tokens.size()) {
                    String ta = tokens.get(i), tb = tokens.get(i + 1), tc = tokens.get(i + 2);
                    String aa = ta.replaceAll("[^a-zA-Z]", "");
                    String ab = tb.replaceAll("[^a-zA-Z]", "");
                    String ac = tc.replaceAll("[^a-zA-Z]", "");
                    if (ta.equals(aa) && tb.equals(ab) && tc.equals(ac)) {
                        String j3 = aa + ab + ac;
                        if (!j3.isEmpty() && wordRanks.containsKey(j3.toLowerCase())) {
                            boolean anyUnknown = !wordRanks.containsKey(aa.toLowerCase())
                                    || !wordRanks.containsKey(ab.toLowerCase())
                                    || !wordRanks.containsKey(ac.toLowerCase());
                            if (anyUnknown) {
                                tokens.set(i, ta + tb + tc);
                                tokens.remove(i + 2);
                                tokens.remove(i + 1);
                                if (i + 1 < seps.size()) seps.remove(i + 1);
                                if (i < seps.size()) seps.remove(i);
                                changed = true;
                                break;
                            }
                        }
                    }
                }
                // Try joining 2 tokens
                String a = tokens.get(i);
                String b = tokens.get(i + 1);
                String alphaA = a.replaceAll("[^a-zA-Z]", "");
                String alphaB = b.replaceAll("[^a-zA-Z]", "");
                boolean aKnown = alphaA.length() >= 1
                        && wordRanks.containsKey(alphaA.toLowerCase());
                boolean bKnown = alphaB.length() >= 1
                        && wordRanks.containsKey(alphaB.toLowerCase());
                if (alphaA.isEmpty() || alphaB.isEmpty()) continue;
                String joined = alphaA + alphaB;
                // First token must be pure alpha; second may have trailing punct
                // e.g. "h" + "ollow." → "hollow."
                if (!a.equals(alphaA)) continue;
                String bTrailing = alphaB.isEmpty() ? b : b.substring(b.indexOf(alphaB) + alphaB.length());
                if (!b.startsWith(alphaB)) continue;
                Integer joinedRank = wordRanks.get(joined.toLowerCase());
                if (joinedRank == null) continue;
                if (aKnown && bKnown) {
                    // Both are real words — only join if the combined word is
                    // at least 2x more common than the rarer part.
                    // e.g. "dam"(6553)+"age"(557) → "damage"(2242): 6553/2242=2.9x → join
                    // e.g. "in"(7)+"sight"(4951) → "insight"(4766): 4951/4766=1.04x → skip
                    int aRank = wordRanks.get(alphaA.toLowerCase());
                    int bRank = wordRanks.get(alphaB.toLowerCase());
                    int rarerRank = Math.max(aRank, bRank);
                    if (joinedRank == 0 || rarerRank < joinedRank * 2) continue;
                }
                tokens.set(i, alphaA + alphaB + bTrailing);
                tokens.remove(i + 1);
                if (i < seps.size()) seps.remove(i);
                changed = true;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0 && i - 1 < seps.size()) sb.append(seps.get(i - 1));
            sb.append(tokens.get(i));
        }
        return sb.toString();
    }

    /**
     * Finds bold segments containing short fragments (e.g. "**C ynidicea n.**")
     * and tries joining them into dictionary words.
     */
    private String collapseBoldFragments(String text) {
        // Match ** ... ** segments
        Matcher m = Pattern.compile("\\*\\*([^*]+)\\*\\*").matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1).trim();
            if (inner.contains(" ") && hasFragmentedTokens(inner)) {
                // Strip trailing punctuation, try joining
                String trailingPunct = "";
                String core = inner;
                while (!core.isEmpty() && !Character.isLetterOrDigit(core.charAt(core.length() - 1))) {
                    trailingPunct = core.charAt(core.length() - 1) + trailingPunct;
                    core = core.substring(0, core.length() - 1);
                }
                String joined = core.replace(" ", "");
                if (wordRanks.containsKey(joined.toLowerCase())) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            "**" + joined + trailingPunct + "**"));
                    continue;
                }
                // Try DP segmentation on the joined text
                List<String> sentences = splitOnSentenceBoundaries(joined);
                StringBuilder seg = new StringBuilder();
                for (int j = 0; j < sentences.size(); j++) {
                    if (j > 0) seg.append(" ");
                    seg.append(segmentSentence(sentences.get(j)));
                }
                String segmented = seg.toString();
                // Only use if segmentation didn't leave single-char fragments
                if (!segmented.equals(joined) && !segmented.matches(".*\\b[a-zA-Z]\\b.*")) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            "**" + segmented + trailingPunct + "**"));
                }
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Interleaved bold marker stripping ───────────────────────────────────

    /**
     * Strips interleaved bold markers from lines where letter-spaced PDF
     * typography causes output like {@code **C * * * * en tip * * * * ed**}.
     *
     * Each bold character is wrapped in its own {@code **...**}, producing
     * {@code * *} sequences between character fragments. Lines with 3+
     * such sequences have all {@code *} removed so the fragment cleaner
     * can reconstruct the words.
     */
    String stripInterleavedBoldMarkers(String text) {
        // Letter-spaced bold text produces per-character bold wrapping:
        //   **C** **entip** **ed** **e** **, G** **ia** **nt.**
        // Merge adjacent bold runs by collapsing "** **" → "" so the outer
        // **...** stays intact:  **Centipede, Giant.**
        //
        // Also collapse "* *" sequences (single-asterisk interleaving).
        String cleaned = text;
        // Merge adjacent bold runs: "** **" → " " (preserves word boundaries)
        // **C** **entip** **ed** → **C entip ed**
        String prev;
        do {
            prev = cleaned;
            cleaned = cleaned.replace("** **", " ");
        } while (!cleaned.equals(prev));
        // Collapse spaces inside bold-wrapped numbers: **1 0 0** → **100**
        java.util.regex.Matcher numMatcher = java.util.regex.Pattern
                .compile("\\*\\*([\\d ]+)\\*\\*").matcher(cleaned);
        cleaned = numMatcher.replaceAll(m -> "**" + m.group(1).replace(" ", "") + "**");
        // Ensure spaces around ** bold markers by tracking open/close state.
        cleaned = ensureSpacesAroundBold(cleaned);
        // Collapse runs of spaces left behind
        cleaned = cleaned.replaceAll(" {2,}", " ");
        // Remove spaces before closing **: "word **" → "word**"
        // Closing ** is followed by space, punctuation, or end-of-line — not a word char
        cleaned = cleaned.replaceAll(" +\\*\\*(?=[\\s),.:;!?]|$)", "**");
        return cleaned;
    }

    /**
     * Ensures a space exists before opening ** and after closing **.
     * Tracks bold state so we know which ** is opening vs closing.
     * e.g. "room**100**)" → "room **100** )"
     *      "**Goblin.**Goblins" → "**Goblin.** Goblins"
     */
    private static String ensureSpacesAroundBold(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inBold = false;
        int i = 0;
        while (i < text.length()) {
            if (i < text.length() - 1 && text.charAt(i) == '*' && text.charAt(i + 1) == '*') {
                if (!inBold) {
                    // Opening **: ensure space before if preceded by alnum
                    if (sb.length() > 0) {
                        char prev = sb.charAt(sb.length() - 1);
                        if (Character.isLetterOrDigit(prev)) {
                            sb.append(' ');
                        }
                    }
                    sb.append("**");
                } else {
                    // Closing **: ensure space after if followed by alnum
                    sb.append("**");
                    if (i + 2 < text.length()) {
                        char next = text.charAt(i + 2);
                        if (Character.isLetterOrDigit(next)) {
                            sb.append(' ');
                        }
                    }
                }
                inBold = !inBold;
                i += 2;
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    private static int countOccurrences(String text, String target) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(target, idx)) >= 0) {
            count++;
            idx += target.length();
        }
        return count;
    }

    // ── Hyphenated line-break rejoining ────────────────────────────────────

    /**
     * Rejoins words split across lines by a hyphen. When a line ends with a
     * hyphen followed by a blank line and then the next word continues in
     * lowercase, the hyphen and line break are removed and the word is joined.
     * e.g. "great-\n\nness" → "greatness\n"
     *
     * Must run BEFORE fragment reconstruction so that "n es s" is joined
     * back to its prefix "great-" before the DP tries to segment it.
     */
    String rejoinHyphenatedLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            // Check if this line ends with a hyphen (hyphenated word break)
            if (trimmed.endsWith("-") && trimmed.length() > 1
                    && Character.isLetter(trimmed.charAt(trimmed.length() - 2))) {
                // Look ahead: skip blank lines, find the continuation
                int next = i + 1;
                while (next < lines.length && lines[next].trim().isEmpty()) {
                    next++;
                }
                if (next < lines.length && !lines[next].trim().isEmpty()) {
                    // Prepend this line (minus hyphen) onto the next line.
                    // Fragment reconstruction will handle the combined line.
                    String prefix = trimmed.substring(0, trimmed.length() - 1);
                    lines[next] = prefix + lines[next].trim();
                    // Skip current line and intervening blanks
                    i = next;
                    continue;
                }
            }
            result.append(lines[i]).append("\n");
            i++;
        }

        // Remove trailing extra newline added by the loop
        if (result.length() > 0 && result.charAt(result.length() - 1) == '\n'
                && (text.isEmpty() || text.charAt(text.length() - 1) != '\n')) {
            result.deleteCharAt(result.length() - 1);
        }

        return result.toString();
    }

    // ── Blank-line collapse ─────────────────────────────────────────────────

    String collapseBlankLines(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (!trimmed.isEmpty()) {
                sb.append(lines[i]).append("\n");
                continue;
            }

            // Blank line. Keep if:
            // - part of a multi-blank run (explicit paragraph break)
            // - adjacent to structural elements (headings, fences, rules)
            // - previous text line ends with sentence-ending punctuation
            //   (indicates a paragraph boundary, not a mid-sentence line wrap)
            // - at start/end of text
            boolean prevIsBlank = i > 0 && lines[i - 1].trim().isEmpty();
            boolean nextIsBlank = i + 1 < lines.length && lines[i + 1].trim().isEmpty();
            boolean nextIsStructural = i + 1 < lines.length && isStructural(lines[i + 1].trim());
            boolean prevIsStructural = i > 0 && isStructural(lines[i - 1].trim());
            boolean prevEndsSentence = i > 0 && endsWithSentencePunctuation(lines[i - 1].trim());

            if (prevIsBlank || nextIsBlank || nextIsStructural || prevIsStructural
                    || prevEndsSentence || i == 0 || i == lines.length - 1) {
                sb.append(lines[i]).append("\n");
            }
            // Single blank between two text lines mid-paragraph — skip
        }

        return sb.toString();
    }

    private boolean isStructural(String trimmed) {
        return trimmed.startsWith("#") || trimmed.startsWith("```")
                || trimmed.equals("---") || trimmed.startsWith(">");
    }

    private boolean endsWithSentencePunctuation(String trimmed) {
        if (trimmed.isEmpty()) return false;
        char last = trimmed.charAt(trimmed.length() - 1);
        return last == '.' || last == '!' || last == '?';
    }

    // ── Paragraph rewrap ─────────────────────────────────────────────────────

    /**
     * Groups lines into paragraphs (separated by blank lines or structural
     * elements), rejoins hyphenated line breaks within each paragraph, and
     * re-wraps at the configured width.
     */
    String rewrapParagraphs(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        List<String> paragraph = new ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();

            if (trimmed.isEmpty() || isStructural(trimmed)) {
                // Flush current paragraph
                if (!paragraph.isEmpty()) {
                    result.append(joinAndWrap(paragraph));
                    paragraph.clear();
                }
                result.append(lines[i]).append("\n");
            } else {
                paragraph.add(trimmed);
            }
        }
        // Flush trailing paragraph
        if (!paragraph.isEmpty()) {
            result.append(joinAndWrap(paragraph));
        }

        return result.toString();
    }

    /**
     * Joins paragraph lines into a single string, rejoins hyphenated line
     * breaks, and wraps at the configured width.
     */
    private String joinAndWrap(List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > 0) {
                // If previous line ended with a letter-hyphen, rejoin the word
                // (e.g. "adven-" + "turers" → "adventurers")
                // Don't rejoin number ranges like "2-" + "12"
                if (joined.length() >= 2
                        && joined.charAt(joined.length() - 1) == '-'
                        && Character.isLetter(joined.charAt(joined.length() - 2))) {
                    joined.deleteCharAt(joined.length() - 1);
                } else {
                    joined.append(" ");
                }
            }
            joined.append(line);
        }

        return wordWrap(joined.toString(), wrapWidth);
    }

    /**
     * Wraps text at word boundaries to fit within the given width.
     */
    private String wordWrap(String text, int width) {
        StringBuilder result = new StringBuilder();
        int lineStart = 0;

        String[] words = text.split(" ");
        int col = 0;
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (col == 0) {
                result.append(word);
                col = word.length();
            } else if (col + 1 + word.length() <= width) {
                result.append(" ").append(word);
                col += 1 + word.length();
            } else {
                result.append("\n").append(word);
                col = word.length();
            }
        }
        result.append("\n");
        return result.toString();
    }

    // ── Fragment reconstruction ──────────────────────────────────────────────

    String reconstructFragments(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            if (isFragmented(lines[i])) {
                result.append(reconstructLine(lines[i]));
            } else {
                result.append(lines[i]);
            }
            if (i < lines.length - 1) result.append("\n");
        }

        return result.toString();
    }

    boolean isFragmented(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || isStructural(trimmed)) return false;

        String[] tokens = trimmed.split("\\s+");
        if (tokens.length < MIN_TOKEN_COUNT) return false;

        int alphaTokens = 0, shortTokens = 0, totalAlphaLen = 0;
        for (String token : tokens) {
            String alpha = token.replaceAll("[^a-zA-Z]", "");
            if (alpha.isEmpty()) continue;
            alphaTokens++;
            totalAlphaLen += alpha.length();
            if (alpha.length() <= 3) shortTokens++;
        }

        if (alphaTokens < MIN_TOKEN_COUNT) return false;
        double shortFraction = (double) shortTokens / alphaTokens;
        double avgLen = (double) totalAlphaLen / alphaTokens;
        return shortFraction >= MIN_SHORT_FRACTION && avgLen < MAX_AVG_TOKEN_LENGTH;
    }

    /**
     * Checks if a short text segment has tokens that look like word fragments
     * (alpha tokens ≤ 3 chars that aren't known dictionary words).
     */
    private boolean hasFragmentedTokens(String text) {
        String[] tokens = text.trim().split("\\s+");
        for (String token : tokens) {
            String alpha = token.replaceAll("[^a-zA-Z]", "");
            if (alpha.length() >= 1 && alpha.length() <= 3
                    && !wordRanks.containsKey(alpha.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String reconstructLine(String line) {
        String trimmed = line.trim();

        // If the line contains bold markers, split on ** boundaries,
        // reconstruct each segment independently, and rejoin.
        if (trimmed.contains("**")) {
            return reconstructWithBoldMarkers(trimmed);
        }

        String joined = trimmed.replace(" ", "");

        // Split on sentence boundaries (punctuation + uppercase)
        List<String> sentences = splitOnSentenceBoundaries(joined);

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < sentences.size(); i++) {
            if (i > 0) result.append(" ");
            result.append(segmentSentence(sentences.get(i)));
        }
        return result.toString();
    }

    /**
     * Splits a line on {@code **} bold marker boundaries, reconstructs each
     * text segment independently, and rejoins with the markers preserved.
     */
    private String reconstructWithBoldMarkers(String line) {
        // Split on "**" — odd-indexed segments are inside bold, even are outside.
        String[] parts = line.split("\\*\\*", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) result.append("**");
            String part = parts[i].trim();
            if (!part.isEmpty() && isFragmented(part)) {
                String joined = part.replace(" ", "");
                List<String> sentences = splitOnSentenceBoundaries(joined);
                for (int j = 0; j < sentences.size(); j++) {
                    if (j > 0) result.append(" ");
                    result.append(segmentSentence(sentences.get(j)));
                }
            } else if (!part.isEmpty() && part.contains(" ") && hasFragmentedTokens(part)) {
                // Short segment not flagged as fragmented — try joining and
                // re-segmenting to recover words split across 2-3 fragments
                String joined = part.replace(" ", "");
                List<String> sentences = splitOnSentenceBoundaries(joined);
                StringBuilder seg = new StringBuilder();
                for (int j = 0; j < sentences.size(); j++) {
                    if (j > 0) seg.append(" ");
                    seg.append(segmentSentence(sentences.get(j)));
                }
                result.append(seg);
            } else {
                result.append(part);
            }
        }
        return result.toString();
    }

    private List<String> splitOnSentenceBoundaries(String s) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && Character.isUpperCase(s.charAt(i + 1))) {
                parts.add(s.substring(start, i + 1));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private String segmentSentence(String sentence) {
        if (sentence.isEmpty()) return sentence;

        // Separate leading punctuation
        int start = 0;
        while (start < sentence.length() && !Character.isLetterOrDigit(sentence.charAt(start))) {
            start++;
        }
        String leading = sentence.substring(0, start);

        // Separate trailing punctuation including hyphen
        int end = sentence.length();
        while (end > start && !Character.isLetterOrDigit(sentence.charAt(end - 1))) {
            end--;
        }
        String trailing = sentence.substring(end);
        String core = sentence.substring(start, end);

        if (core.isEmpty()) return sentence;

        String segmented = segmentAlphaRun(core);
        return leading + segmented + trailing;
    }

    /**
     * Splits on embedded commas between letters, then segments each piece.
     */
    private String segmentAlphaRun(String run) {
        List<String> pieces = new ArrayList<>();
        List<String> separators = new ArrayList<>();

        StringBuilder current = new StringBuilder();
        for (int i = 0; i < run.length(); i++) {
            char c = run.charAt(i);
            if (c == ',' && i > 0 && i < run.length() - 1
                    && Character.isLetter(run.charAt(i - 1))
                    && Character.isLetter(run.charAt(i + 1))) {
                pieces.add(current.toString());
                separators.add(", ");
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        pieces.add(current.toString());

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) result.append(separators.get(i - 1));
            result.append(segmentPiece(pieces.get(i)));
        }
        return result.toString();
    }

    private String segmentPiece(String piece) {
        if (piece.isEmpty()) return piece;
        String lower = piece.toLowerCase();
        String segmented = dpSegment(lower);
        return restoreCase(piece, segmented);
    }

    // ── DP segmentation ─────────────────────────────────────────────────────

    /**
     * DP word segmentation using frequency-weighted scoring.
     * Score per word = length_bonus + frequency_bonus.
     * This ensures "for the" (two very common words) beats "forth e"
     * (one less common word + unsegmentable remainder).
     */
    private String dpSegment(String s) {
        if (s.isEmpty()) return s;

        int n = s.length();
        double[] dp = new double[n + 1];
        int[] parent = new int[n + 1];
        Arrays.fill(dp, Double.NEGATIVE_INFINITY);
        Arrays.fill(parent, -1);
        dp[0] = 0;

        for (int i = 0; i < n; i++) {
            if (dp[i] == Double.NEGATIVE_INFINITY) continue;
            // Fallback: consume a single character with a heavy penalty.
            // This ensures the DP always finds a path, even through unknown
            // substrings. The penalty makes it strongly prefer real words.
            {
                double fallbackScore = dp[i] - 20;
                // Single-char dictionary words ("a", "i") get a small bonus
                // instead of the fallback penalty, but less than multi-char words
                String singleChar = s.substring(i, i + 1);
                if (wordRanks.containsKey(singleChar)) {
                    fallbackScore = dp[i] + 0.5;
                }
                if (fallbackScore > dp[i + 1]) {
                    dp[i + 1] = fallbackScore;
                    parent[i + 1] = i;
                }
            }

            int maxLen = Math.min(n - i, 30);
            for (int len = 2; len <= maxLen; len++) {
                String candidate = s.substring(i, i + len);
                Integer rank = wordRanks.get(candidate);
                if (rank != null) {
                    double freqBonus = Math.log((double) (totalWords + 1) / rank);
                    double score = dp[i] + (len * len) + freqBonus;
                    if (score > dp[i + len]) {
                        dp[i + len] = score;
                        parent[i + len] = i;
                    }
                }
            }
        }

        if (dp[n] == Double.NEGATIVE_INFINITY) {
            return s; // no segmentation found
        }

        List<String> words = new ArrayList<>();
        int pos = n;
        while (pos > 0) {
            words.add(s.substring(parent[pos], pos));
            pos = parent[pos];
        }
        Collections.reverse(words);
        return String.join(" ", words);
    }

    private String restoreCase(String original, String segmented) {
        StringBuilder result = new StringBuilder();
        int origIdx = 0;
        for (int i = 0; i < segmented.length(); i++) {
            if (segmented.charAt(i) == ' ') {
                result.append(' ');
            } else if (origIdx < original.length()) {
                result.append(original.charAt(origIdx));
                origIdx++;
            } else {
                result.append(segmented.charAt(i));
            }
        }
        return result.toString();
    }

    // ── Dictionary loading ──────────────────────────────────────────────────

    private Map<String, Integer> loadDictionary(String glossary) {
        Map<String, Integer> ranks = new HashMap<>();
        List<String> suffixes = loadSuffixes("suffixes.txt");
        loadWordFile(ranks, "english.txt", suffixes);
        loadWordFile(ranks, "dnd_terms.txt", suffixes);
        if (glossary != null) {
            loadWordFile(ranks, glossary, suffixes);
        }
        return ranks;
    }

    private List<String> loadSuffixes(String resourceName) {
        List<String> suffixes = new ArrayList<>();
        try (var is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) return suffixes;
            try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        suffixes.add(line.toLowerCase());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to load " + resourceName + ": " + e.getMessage());
        }
        return suffixes;
    }

    private void loadWordFile(Map<String, Integer> ranks, String resourceName,
                              List<String> suffixes) {
        try (var is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                System.err.println("Warning: " + resourceName + " not found on classpath");
                return;
            }
            try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                int lineNum = 0;
                while ((line = reader.readLine()) != null) {
                    lineNum++;
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // Support "word\trank" or plain "word" (rank = line number)
                    String word;
                    int rank;
                    if (line.contains("\t")) {
                        String[] parts = line.split("\t");
                        word = parts[0];
                        rank = Integer.parseInt(parts[1]);
                    } else {
                        word = line;
                        rank = lineNum;
                    }
                    String lower = word.toLowerCase();
                    ranks.merge(lower, rank, Math::min);
                }
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to load " + resourceName + ": " + e.getMessage());
        }
    }
}
