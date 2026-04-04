package com.dnd.processor.converters;

/**
 * Result of a Phase 1 PDF conversion: the extracted Markdown content and the
 * detected ruleset.
 *
 * @param markdown the extracted Markdown text (without YAML frontmatter)
 * @param ruleset  the detected game ruleset
 */
public record ConversionResult(String markdown, RulesetInfo ruleset) {}
