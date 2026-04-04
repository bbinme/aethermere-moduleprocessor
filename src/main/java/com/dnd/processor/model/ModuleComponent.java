package com.dnd.processor.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModuleComponent {

    private String source;

    @JsonProperty("component_type")
    private ComponentType componentType;

    private String section;

    private Map<String, Object> fields;

    @JsonProperty("raw_text")
    private String rawText;

    public ModuleComponent() {
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public void setComponentType(ComponentType componentType) {
        this.componentType = componentType;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public Map<String, Object> getFields() {
        return fields;
    }

    public void setFields(Map<String, Object> fields) {
        this.fields = fields;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }
}
