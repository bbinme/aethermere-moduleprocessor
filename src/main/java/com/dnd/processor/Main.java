package com.dnd.processor;

import com.dnd.processor.converters.PDFPreprocessor;
import com.dnd.processor.converters.PdfConverter;
import com.dnd.processor.output.JsonWriter;
import com.dnd.processor.pipeline.FileToMarkdownConverter;
import com.dnd.processor.pipeline.MarkdownToComponentParser;
import com.dnd.processor.pipeline.ParseResult;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Main CLI entry point for the Module Processor.
 *
 * Usage:
 *   java -jar module-processor.jar --input <file> [--phase convert|parse|all] [--output <dir>]
 */
@Command(
        name = "module-processor",
        mixinStandardHelpOptions = true,
        version = "1.0",
        description = "Converts D&D module files to structured JSON training data."
)
public class Main implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, description = "Input file path (supports glob wildcards)")
    private String inputFile;

    @Option(names = {"--inputRegex"}, description = "Regex pattern for input files, e.g. \"data/B[1-4].*\\.pdf\"")
    private String inputRegex;

    @Option(names = {"-p", "--phase"}, defaultValue = "all",
            description = "Processing phase: convert, parse, all, sidebars, split, render, classify, layout, margin-detection (default: all)")
    private String phase;

    @Option(names = {"-o", "--output"}, description = "Output directory (default: same directory as input)")
    private String outputDir;

    @Option(names = {"--debug"}, description = "Enable debug output (e.g. band images for the layout phase)")
    private boolean debug;

    /** Resolved per-file module config; set in processFile() before any phase runs. */
    private com.dnd.processor.config.DnD_BasicSet moduleConfig = new com.dnd.processor.config.DnD_BasicSet();


    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        try {
            if (inputFile == null && inputRegex == null) {
                System.out.println("ERROR: One of --input or --inputRegex is required.");
                return 1;
            }
            List<Path> inputPaths = resolveInputPaths();
            if (inputPaths.isEmpty()) {
                String spec = inputFile != null ? inputFile : inputRegex;
                System.out.println("ERROR: No files matched: " + spec);
                return 1;
            }

            if (phase.equalsIgnoreCase("margin-detection")) {
                return runMarginDetectionAll(inputPaths);
            }

            for (Path inputPath : inputPaths) {
                int result = processFile(inputPath);
                if (result != 0) return result;
            }
            return 0;

        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    /** Resolves --input or --inputRegex to a sorted list of paths. */
    private List<Path> resolveInputPaths() throws Exception {
        if (inputRegex == null) {
            return resolveGlob(inputFile);
        }
        return resolveRegex(inputRegex);
    }

    private List<Path> resolveGlob(String pattern) throws Exception {
        if (!pattern.contains("*") && !pattern.contains("?")) {
            return List.of(Paths.get(pattern).toAbsolutePath());
        }
        Path patternPath = Paths.get(pattern);
        Path dir = patternPath.getParent() != null
                ? patternPath.getParent().toAbsolutePath()
                : Paths.get("").toAbsolutePath();
        String glob = patternPath.getFileName().toString();

        List<Path> matches = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, glob)) {
            stream.forEach(matches::add);
        }
        matches.sort(Comparator.naturalOrder());
        return matches;
    }

    private List<Path> resolveRegex(String pattern) throws Exception {
        // Split on the last forward slash only — backslash is a regex escape, not a path separator
        int lastSep = pattern.lastIndexOf('/');
        Path dir;
        String regex;
        if (lastSep >= 0) {
            dir = Paths.get(pattern.substring(0, lastSep)).toAbsolutePath();
            regex = pattern.substring(lastSep + 1);
        } else {
            dir = Paths.get("").toAbsolutePath();
            regex = pattern;
        }
        java.util.regex.Pattern compiled = java.util.regex.Pattern.compile(regex);

        List<Path> matches = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (compiled.matcher(p.getFileName().toString()).matches()) {
                    matches.add(p);
                }
            }
        }
        matches.sort(Comparator.naturalOrder());
        return matches;
    }

    private int processFile(Path inputPath) {
        try {
            if (!Files.exists(inputPath)) {
                System.out.println("ERROR: Input file not found: " + inputPath);
                return 1;
            }

            Path outputDirectory;
            if (outputDir != null && !outputDir.isBlank()) {
                outputDirectory = Paths.get(outputDir).toAbsolutePath();
            } else {
                outputDirectory = inputPath.getParent();
            }
            Files.createDirectories(outputDirectory);

            String sourceFileName = inputPath.getFileName().toString();
            moduleConfig = resolveConfig(sourceFileName);

            switch (phase.toLowerCase()) {
                case "convert"  -> runConvert(inputPath, outputDirectory);
                case "parse"    -> runParse(inputPath, outputDirectory, sourceFileName);
                case "all"      -> runAll(inputPath, outputDirectory, sourceFileName);
                case "sidebars" -> runSidebars(inputPath, outputDirectory, sourceFileName);
                case "split"    -> runSplit(inputPath, outputDirectory, sourceFileName);
                case "render"   -> runRender(inputPath, outputDirectory, sourceFileName);
                case "edges"    -> runEdges(inputPath, outputDirectory, sourceFileName);
                case "bands"    -> runBands(inputPath, outputDirectory, sourceFileName);
                case "classify"         -> runClassify(inputPath, sourceFileName);
                case "verify-columns"   -> runVerifyColumns(inputPath, sourceFileName);
                case "table-detection"  -> runTableDetection(inputPath, outputDirectory, sourceFileName);
                case "layout"   -> runLayout(inputPath, outputDirectory, sourceFileName);
                default -> {
                    System.out.println("ERROR: Unknown phase '" + phase + "'. Use: convert, parse, all, sidebars, split, render, edges, bands, classify, verify-columns, layout, margin-detection, or table-detection");
                    return 1;
                }
            }

            return 0;

        } catch (IllegalArgumentException e) {
            System.out.println("ERROR: " + e.getMessage());
            return 1;
        } catch (Exception e) {
            System.out.println("ERROR: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    // ── Phase runners ─────────────────────────────────────────────────────────

    private int runMarginDetectionAll(List<Path> inputPaths) {
        com.dnd.processor.converters.PDFPreprocessor preprocessor =
                new com.dnd.processor.converters.PDFPreprocessor();
        float dpi = 150;

        float[] globalMin = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] globalMax = {0, 0, 0, 0};
        float[] globalSum = {0, 0, 0, 0};
        int globalIncluded = 0, globalExcluded = 0;

        for (Path p : inputPaths) {
            try {
                List<com.dnd.processor.converters.PageMarginInfo> results =
                        preprocessor.analyzeMargins(p, dpi);
                for (com.dnd.processor.converters.PageMarginInfo info : results) {
                    if (info.excluded()) {
                        globalExcluded++;
                    } else {
                        globalIncluded++;
                        globalMin[0] = Math.min(globalMin[0], info.topIn());
                        globalMax[0] = Math.max(globalMax[0], info.topIn());
                        globalSum[0] += info.topIn();
                        globalMin[1] = Math.min(globalMin[1], info.bottomIn());
                        globalMax[1] = Math.max(globalMax[1], info.bottomIn());
                        globalSum[1] += info.bottomIn();
                        globalMin[2] = Math.min(globalMin[2], info.leftIn());
                        globalMax[2] = Math.max(globalMax[2], info.leftIn());
                        globalSum[2] += info.leftIn();
                        globalMin[3] = Math.min(globalMin[3], info.rightIn());
                        globalMax[3] = Math.max(globalMax[3], info.rightIn());
                        globalSum[3] += info.rightIn();
                    }
                }
            } catch (Exception e) {
                System.out.println("ERROR processing " + p.getFileName() + ": " + e.getMessage());
            }
        }

        if (inputPaths.size() > 1) {
            System.out.printf("%n=== Aggregate margin summary (%d files, %d pages included, %d excluded, inches at %.0f DPI) ===%n",
                    inputPaths.size(), globalIncluded, globalExcluded, dpi);
            com.dnd.processor.converters.PDFPreprocessor.printMarginSummary(
                    "all files", globalIncluded, globalExcluded, dpi, globalMin, globalMax, globalSum);
        }
        return 0;
    }

    private void runTableDetection(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'table-detection' requires a .pdf input file, got: " + sourceFileName);
        }
        String baseName = stripExtension(sourceFileName);
        Path tableDir   = outputDirectory.resolve(baseName + "-tables");
        System.out.println("Table detection: scanning " + sourceFileName + " ...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.detectTables(inputPath, tableDir, 150);
    }

    private void runLayout(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'layout' requires a .pdf input file, got: " + sourceFileName);
        }
        runLayoutPhase(inputPath, outputDirectory);
    }

    /** Runs the layout phase and returns the path to the generated layout PDF. */
    private Path runLayoutPhase(Path inputPath, Path outputDirectory) throws Exception {
        String sourceFileName = inputPath.getFileName().toString();
        String baseName = stripExtension(sourceFileName);
        Path outPath = outputDirectory.resolve(baseName + "-layout.pdf");

        System.out.println("Layout: splitting " + sourceFileName + " by detected page layout...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.processLayout(inputPath, outPath, 150, debug);
        System.out.println("Layout complete. Output written to: " + outPath);
        return outPath;
    }

    private void runClassify(Path inputPath, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'classify' requires a .pdf input file, got: " + sourceFileName);
        }
        System.out.println("Classify: detecting page layouts in " + sourceFileName + "...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.classifyPages(inputPath, 150);
    }

    private void runVerifyColumns(Path inputPath, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'verify-columns' requires a .pdf input file, got: " + sourceFileName);
        }
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.verifyColumns(inputPath, 150);
    }

    private void runBands(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'bands' requires a .pdf input file, got: " + sourceFileName);
        }

        String baseName = stripExtension(sourceFileName);
        Path imageDir = outputDirectory.resolve(baseName + "-bands");

        System.out.println("Bands: analysing white-space bands in " + sourceFileName + "...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.analyzeBands(inputPath, imageDir, 150);
        System.out.println("Bands complete. Images written to: " + imageDir);
    }

    private void runEdges(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'edges' requires a .pdf input file, got: " + sourceFileName);
        }

        String baseName = stripExtension(sourceFileName);
        Path imageDir = outputDirectory.resolve(baseName + "-edges");

        System.out.println("Edges: running Canny edge detection on " + sourceFileName + "...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.detectEdges(inputPath, imageDir, 150, 30, 90);
        System.out.println("Edges complete. Images written to: " + imageDir);
    }

    private void runRender(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'render' requires a .pdf input file, got: " + sourceFileName);
        }

        String baseName = stripExtension(sourceFileName);
        Path imageDir = outputDirectory.resolve(baseName + "-pages");

        System.out.println("Render: rasterising " + sourceFileName + " at 150 DPI...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.renderPages(inputPath, imageDir, 150);
        System.out.println("Render complete. Images written to: " + imageDir);
    }

    private void runSplit(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'split' requires a .pdf input file, got: " + sourceFileName);
        }

        String baseName = stripExtension(sourceFileName);
        Path outPath = outputDirectory.resolve(baseName + "-split.pdf");

        System.out.println("Split: preprocessing " + sourceFileName + " (two-column pages → two pages)...");
        PDFPreprocessor preprocessor = new PDFPreprocessor(moduleConfig);
        preprocessor.split(inputPath, outPath);
        System.out.println("Split complete. Output written to: " + outPath);
    }

    private void runConvert(Path inputPath, Path outputDirectory) throws Exception {
        // For raw PDFs, run the layout phase first to produce a pre-split layout PDF,
        // then extract text from that.  Already-processed layout PDFs and other file
        // types go straight to the converter.
        if (inputPath.getFileName().toString().toLowerCase().endsWith(".pdf")
                && !inputPath.getFileName().toString().toLowerCase().endsWith("-layout.pdf")) {
            inputPath = runLayoutPhase(inputPath, outputDirectory);
        }
        System.out.println("Convert: extracting text from " + inputPath.getFileName() + " ...");
        FileToMarkdownConverter converter = new FileToMarkdownConverter();
        Path mdPath = converter.convert(inputPath, outputDirectory);
        System.out.println("Convert complete. Markdown written to: " + mdPath);
    }

    private void runSidebars(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!sourceFileName.toLowerCase().endsWith(".pdf")) {
            throw new IllegalArgumentException("Phase 'sidebars' requires a .pdf input file, got: " + sourceFileName);
        }

        System.out.println("Sidebar extraction: scanning " + sourceFileName + " for sidebar regions...");
        PdfConverter converter = new PdfConverter();
        String sidebars = converter.convertSidebarsOnly(inputPath);

        String baseName = stripExtension(sourceFileName);
        Path outPath = outputDirectory.resolve(baseName + "-sidebars.md");
        Files.writeString(outPath, sidebars, java.nio.charset.StandardCharsets.UTF_8);

        System.out.println("Sidebar extraction complete. Output written to: " + outPath);
    }

    private void runParse(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        if (!inputPath.getFileName().toString().toLowerCase().endsWith(".md")) {
            throw new IllegalArgumentException("Phase 'parse' requires a .md input file, got: " + sourceFileName);
        }

        System.out.println("Phase 2: Parsing " + sourceFileName + " into components...");
        String markdown = Files.readString(inputPath, StandardCharsets.UTF_8);

        MarkdownToComponentParser parser = new MarkdownToComponentParser();
        ParseResult result = parser.parse(markdown, sourceFileName);

        System.out.println("  Detected ruleset: " + result.ruleset().name() + " (" + result.ruleset().id() + ")");
        System.out.println("  Classified " + result.components().size() + " components.");

        String baseName = stripExtension(sourceFileName);
        Path jsonPath = outputDirectory.resolve(baseName + ".json");

        JsonWriter writer = new JsonWriter();
        writer.write(result.components(), result.ruleset(), sourceFileName, jsonPath);

        System.out.println("Phase 2 complete. JSON written to: " + jsonPath);
    }

    private void runAll(Path inputPath, Path outputDirectory, String sourceFileName) throws Exception {
        System.out.println("Running full pipeline for: " + sourceFileName);

        // Phase 1a: layout preprocessing for raw PDFs
        if (sourceFileName.toLowerCase().endsWith(".pdf")
                && !sourceFileName.toLowerCase().endsWith("-layout.pdf")) {
            inputPath = runLayoutPhase(inputPath, outputDirectory);
        }

        // Phase 1b: text extraction
        System.out.println("Phase 1: Converting to Markdown...");
        FileToMarkdownConverter converter = new FileToMarkdownConverter();
        Path mdPath = converter.convert(inputPath, outputDirectory);
        System.out.println("Phase 1 complete. Markdown written to: " + mdPath);

        // Phase 2
        System.out.println("Phase 2: Parsing Markdown into components...");
        String markdown = Files.readString(mdPath, StandardCharsets.UTF_8);

        MarkdownToComponentParser parser = new MarkdownToComponentParser();
        ParseResult result = parser.parse(markdown, sourceFileName);

        System.out.println("  Detected ruleset: " + result.ruleset().name() + " (" + result.ruleset().id() + ")");
        System.out.println("  Classified " + result.components().size() + " components.");

        String baseName = stripExtension(sourceFileName);
        Path jsonPath = outputDirectory.resolve(baseName + ".json");

        JsonWriter writer = new JsonWriter();
        writer.write(result.components(), result.ruleset(), sourceFileName, jsonPath);

        System.out.println("Phase 2 complete. JSON written to: " + jsonPath);
        System.out.println("Pipeline complete.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns the appropriate module config for the given filename.
     * Pattern matching is done against the base filename only (no path).
     */
    private com.dnd.processor.config.DnD_BasicSet resolveConfig(String fileName) {
        // B1 through B4 (but not B10, B11, etc.)
        if (fileName.matches("(?i)B[1-4](?!\\d).*\\.pdf")) {
            System.out.println("Config: DnD_BasicSet_B1_4");
            return new com.dnd.processor.config.DnD_BasicSet_B1_4();
        }
        System.out.println("Config: DnD_BasicSet (default)");
        return new com.dnd.processor.config.DnD_BasicSet();
    }

    private String stripExtension(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) return fileName.substring(0, dotIndex);
        return fileName;
    }
}
