package com.dnd.processor.pipeline;

import com.dnd.processor.converters.RulesetInfo;
import com.dnd.processor.model.ComponentType;
import com.dnd.processor.model.ModuleComponent;
import com.dnd.processor.parsers.ComponentClassifier;
import com.dnd.processor.parsers.EncounterExtractor;
import com.dnd.processor.parsers.LocationExtractor;
import com.dnd.processor.parsers.NpcExtractor;
import com.dnd.processor.parsers.SectionSplitter;
import com.dnd.processor.parsers.StatBlockExtractor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phase 2 orchestrator: parses Markdown into a list of ModuleComponents.
 * Also extracts the YAML frontmatter (written by Phase 1) to recover the ruleset.
 */
public class MarkdownToComponentParser {

    private static final Pattern FRONTMATTER_BLOCK = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n",
            Pattern.DOTALL
    );

    private static final Pattern FRONTMATTER_FIELD = Pattern.compile(
            "^([\\w_]+):\\s*(.+)$",
            Pattern.MULTILINE
    );

    private final SectionSplitter splitter = new SectionSplitter();
    private final ComponentClassifier classifier = new ComponentClassifier();
    private final LocationExtractor locationExtractor = new LocationExtractor();
    private final NpcExtractor npcExtractor = new NpcExtractor();
    private final EncounterExtractor encounterExtractor = new EncounterExtractor();
    private final StatBlockExtractor statBlockExtractor = new StatBlockExtractor();

    /**
     * Parses the given Markdown string into structured ModuleComponents.
     * Reads the YAML frontmatter (if present) to extract the ruleset.
     *
     * @param markdown   the Markdown text (from Phase 1 or a .md input file)
     * @param sourceName the original source filename (e.g., "curse-of-strahd.pdf")
     * @return parse result containing components and ruleset
     */
    public ParseResult parse(String markdown, String sourceName) {
        // Extract and strip YAML frontmatter
        RulesetInfo ruleset = RulesetInfo.unknown();
        String body = markdown;

        Matcher fm = FRONTMATTER_BLOCK.matcher(markdown);
        if (fm.find()) {
            ruleset = parseFrontmatter(fm.group(1));
            body = markdown.substring(fm.end());
        }

        List<ModuleComponent> components = new ArrayList<>();
        List<SectionSplitter.Section> sections = splitter.split(body);

        for (SectionSplitter.Section section : sections) {
            ComponentType type = classifier.classify(section);

            ModuleComponent component = new ModuleComponent();
            component.setSource(sourceName);
            component.setComponentType(type);
            component.setSection(section.heading());
            component.setRawText(section.body());

            Map<String, Object> fields = extractFields(type, section.body());
            if (fields != null && !fields.isEmpty()) {
                component.setFields(fields);
            }

            components.add(component);
        }

        return new ParseResult(components, ruleset);
    }

    // ── Frontmatter parsing ───────────────────────────────────────────────────

    private RulesetInfo parseFrontmatter(String frontmatterBody) {
        Map<String, String> fields = new HashMap<>();
        Matcher m = FRONTMATTER_FIELD.matcher(frontmatterBody);
        while (m.find()) {
            String key = m.group(1).trim();
            String value = m.group(2).trim().replaceAll("^\"|\"$", ""); // strip surrounding quotes
            fields.put(key, value);
        }

        String id        = fields.getOrDefault("ruleset",      "unknown");
        String name      = fields.getOrDefault("ruleset_name", "Unknown");
        String publisher = fields.getOrDefault("publisher",    "Unknown");
        return new RulesetInfo(id, name, publisher);
    }

    // ── Field extraction ──────────────────────────────────────────────────────

    private Map<String, Object> extractFields(ComponentType type, String sectionBody) {
        return switch (type) {
            case LOCATION      -> locationExtractor.extract(sectionBody);
            case NPC           -> npcExtractor.extract(sectionBody);
            case ENCOUNTER     -> encounterExtractor.extract(sectionBody);
            case STAT_BLOCK    -> statBlockExtractor.extract(sectionBody);
            default            -> null;
        };
    }
}
