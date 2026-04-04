package com.dnd.processor.pipeline;

import com.dnd.processor.converters.RulesetInfo;
import com.dnd.processor.model.ModuleComponent;

import java.util.List;

/**
 * Result of Phase 2 parsing: the extracted components and the ruleset read
 * from the Markdown frontmatter.
 *
 * @param components ordered list of parsed module components
 * @param ruleset    the game ruleset detected during Phase 1 (or unknown)
 */
public record ParseResult(List<ModuleComponent> components, RulesetInfo ruleset) {}
