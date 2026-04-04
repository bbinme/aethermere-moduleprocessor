package com.dnd.processor.converters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Converts a plain text file to a string by reading it as UTF-8.
 */
public class TxtConverter {

    /**
     * Reads the given text file and returns its contents as-is.
     *
     * @param inputPath path to the text file
     * @return file contents as a string
     */
    public String convert(Path inputPath) throws IOException {
        return Files.readString(inputPath, StandardCharsets.UTF_8);
    }
}
