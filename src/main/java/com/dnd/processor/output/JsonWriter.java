package com.dnd.processor.output;

import com.dnd.processor.converters.RulesetInfo;
import com.dnd.processor.model.ModuleComponent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes module components to a pretty-printed JSON file with a metadata wrapper.
 *
 * Output format:
 * <pre>
 * {
 *   "metadata": { "source": "...", "ruleset": "...", ... },
 *   "components": [ ... ]
 * }
 * </pre>
 */
public class JsonWriter {

    private final ObjectMapper mapper;

    public JsonWriter() {
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Serializes the components and metadata to a pretty-printed JSON file.
     *
     * @param components   the list of components to write
     * @param ruleset      the detected ruleset (written into metadata)
     * @param sourceName   the original source filename
     * @param outputPath   path to the output JSON file
     */
    public void write(List<ModuleComponent> components, RulesetInfo ruleset,
                      String sourceName, Path outputPath) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source",       sourceName);
        metadata.put("ruleset",      ruleset.id());
        metadata.put("ruleset_name", ruleset.name());
        metadata.put("publisher",    ruleset.publisher());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("metadata",   metadata);
        output.put("components", components);

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        Files.writeString(outputPath, json, StandardCharsets.UTF_8);
    }
}
