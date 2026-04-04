package com.dnd.processor.converters;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects the game ruleset of a module by scanning trademark and copyright text
 * on the first two pages.
 *
 * Detection is applied in priority order (most specific first).
 * See ruleset-detection-spec.md for the full pattern table.
 */
public class RulesetDetector {

    private static final Pattern COPYRIGHT_YEAR = Pattern.compile("[©Cc]opyright\\s+(\\d{4})|©(\\d{4})");

    /**
     * Detects the ruleset by extracting text from pages 1–2 of the document.
     */
    public RulesetInfo detect(PDDocument doc) throws IOException {
        String text = extractFirstTwoPages(doc);
        return detectFromText(text);
    }

    /**
     * Detects the ruleset from the given text (first two pages of a module).
     * Package-private for testing.
     */
    RulesetInfo detectFromText(String raw) {
        String text = raw.toLowerCase(Locale.ROOT);

        // ── AD&D family (ADVANCED present) ─────────────────────────────────────
        if (text.contains("advanced dungeons & dragons")) {
            if (text.contains("2nd edition") || text.contains("second edition")) {
                if (text.contains("revised") || text.contains("player's option")) {
                    return new RulesetInfo("adnd2e_revised", "AD&D 2nd Edition Revised", "TSR, Inc.");
                }
                return new RulesetInfo("adnd2e", "AD&D 2nd Edition", "TSR, Inc.");
            }
            return new RulesetInfo("adnd1e", "AD&D 1st Edition", "TSR, Inc.");
        }

        // ── WotC family (Wizards of the Coast present) ──────────────────────────
        if (text.contains("wizards of the coast")) {
            // Check Essentials before 4e (both may say "4th Edition")
            if (text.contains("essentials") || text.contains("heroes of the")) {
                return new RulesetInfo("dnd4e_essentials", "DnD Essentials", "Wizards of the Coast");
            }
            if (text.contains("4th edition") || containsToken(text, "4e")) {
                return new RulesetInfo("dnd4e", "DnD 4th Edition", "Wizards of the Coast");
            }
            if (text.contains("v5.5") || text.contains("one d&d") || copyrightYear(raw) >= 2024) {
                return new RulesetInfo("dnd55", "DnD v5.5", "Wizards of the Coast");
            }
            if (text.contains("5th edition") || text.contains("d&d beyond") || containsToken(text, "5e")) {
                return new RulesetInfo("dnd5e", "DnD 5th Edition", "Wizards of the Coast");
            }
            if (text.contains("3.5") || text.contains("v.3.5") || text.contains("revised edition")) {
                return new RulesetInfo("dnd35", "DnD v3.5", "Wizards of the Coast");
            }
            return new RulesetInfo("dnd3e", "DnD 3rd Edition", "Wizards of the Coast");
        }

        // ── TSR Basic/Expert/BECMI family ───────────────────────────────────────
        if (text.contains("dungeons & dragons") || text.contains("dungeons and dragons")) {
            if (text.contains("rules cyclopedia")) {
                return new RulesetInfo("dnd_rc", "DnD Rules Cyclopedia", "TSR, Inc.");
            }
            if (text.contains("companion set") || text.contains("masters set") || text.contains("immortals set")) {
                return new RulesetInfo("dnd_becmi", "DnD BECMI", "TSR, Inc.");
            }
            if (text.contains("basic set") && text.contains("expert set")) {
                return new RulesetInfo("dnd_bx", "DnD B/X", "TSR, Inc.");
            }
            if (text.contains("expert set")) {
                // Expert-only reference → B/X (Expert modules reference the Expert Set)
                return new RulesetInfo("dnd_bx", "DnD B/X", "TSR, Inc.");
            }
            if (text.contains("basic set")) {
                return new RulesetInfo("dnd_basic", "DnD Basic", "TSR");
            }
            if (text.contains("tactical studies rules")) {
                return new RulesetInfo("dnd_og", "DnD Original", "TSR");
            }
            // DUNGEONS & DRAGONS + TSR, no set marker → conservative default
            if (text.contains("tsr")) {
                return new RulesetInfo("dnd_basic", "DnD Basic", "TSR");
            }
        }

        // ── Other systems ───────────────────────────────────────────────────────
        if (text.contains("daggerheart") && text.contains("darrington press")) {
            return new RulesetInfo("daggerheart", "Daggerheart", "Darrington Press");
        }

        return RulesetInfo.unknown();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private String extractFirstTwoPages(PDDocument doc) throws IOException {
        // Scan up to 4 pages: cover art and blank pages push copyright to page 3+
        int pageCount = Math.min(4, doc.getNumberOfPages());
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(pageCount);
        return stripper.getText(doc);
    }

    /** Returns the first copyright year found in the text, or 0 if none found. */
    private int copyrightYear(String text) {
        Matcher m = COPYRIGHT_YEAR.matcher(text);
        if (m.find()) {
            String year = m.group(1) != null ? m.group(1) : m.group(2);
            try { return Integer.parseInt(year); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /** Checks whether {@code token} appears as a whole word in {@code text}. */
    private boolean containsToken(String text, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(text).find();
    }
}
