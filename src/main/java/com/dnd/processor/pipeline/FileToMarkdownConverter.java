package com.dnd.processor.pipeline;

import com.dnd.processor.converters.ConversionResult;
import com.dnd.processor.converters.DocxConverter;
import com.dnd.processor.converters.PdfConverter;
import com.dnd.processor.converters.RulesetInfo;
import com.dnd.processor.converters.TxtConverter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Phase 1 orchestrator: routes input files to the appropriate converter,
 * prepends YAML frontmatter (source + ruleset), and writes the resulting
 * Markdown to the output directory.
 */
public class FileToMarkdownConverter {

    private final PdfConverter pdfConverter = new PdfConverter();
    private final DocxConverter docxConverter = new DocxConverter();
    private final TxtConverter txtConverter = new TxtConverter();

    /**
     * Converts the input file to Markdown and writes it to the output directory.
     * The written .md file includes YAML frontmatter with source and ruleset.
     *
     * @param inputPath path to the source file
     * @param outputDir directory where the .md file will be written
     * @return path to the written .md file
     */
    public Path convert(Path inputPath, Path outputDir) throws IOException {
        String fileName = inputPath.getFileName().toString();
        String lowerName = fileName.toLowerCase();

        String baseName = stripExtension(fileName);
        Path outputPath = outputDir.resolve(baseName + ".md");

        Files.createDirectories(outputDir);

        if (lowerName.endsWith(".pdf")) {
            ConversionResult result = pdfConverter.convert(inputPath);
            String content = buildFrontmatter(fileName, result.ruleset()) + result.markdown();
            Files.writeString(outputPath, content, StandardCharsets.UTF_8);

        } else if (lowerName.endsWith(".docx")) {
            String markdown = docxConverter.convert(inputPath);
            String content = buildFrontmatter(fileName, RulesetInfo.unknown()) + markdown;
            Files.writeString(outputPath, content, StandardCharsets.UTF_8);

        } else if (lowerName.endsWith(".txt")) {
            String text = txtConverter.convert(inputPath);
            String content = buildFrontmatter(fileName, RulesetInfo.unknown()) + text;
            Files.writeString(outputPath, content, StandardCharsets.UTF_8);

        } else if (lowerName.endsWith(".md")) {
            // Pass-through: copy the file as-is
            if (!inputPath.toAbsolutePath().equals(outputPath.toAbsolutePath())) {
                Files.copy(inputPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                outputPath = inputPath;
            }
        } else {
            throw new IllegalArgumentException(
                    "Unsupported file format: " + fileName +
                    ". Supported formats: .pdf, .docx, .txt, .md"
            );
        }

        return outputPath;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Builds a YAML frontmatter block for the given source file and ruleset.
     */
    private String buildFrontmatter(String sourceFileName, RulesetInfo ruleset) {
        return "---\n" +
               "source: " + yamlEscape(sourceFileName) + "\n" +
               "ruleset: " + yamlEscape(ruleset.id()) + "\n" +
               "ruleset_name: " + yamlEscape(ruleset.name()) + "\n" +
               "publisher: " + yamlEscape(ruleset.publisher()) + "\n" +
               "---\n\n";
    }

    /**
     * Escapes a value for use in a YAML frontmatter string field.
     * Wraps in double quotes if the value contains special characters.
     */
    private String yamlEscape(String value) {
        if (value == null) return "\"\"";
        if (value.contains(":") || value.contains("#") || value.contains("\"")
                || value.contains("'") || value.contains("&")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
}
