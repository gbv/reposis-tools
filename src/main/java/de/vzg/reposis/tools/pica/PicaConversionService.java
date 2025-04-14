package de.vzg.reposis.tools.pica;

import org.springframework.stereotype.Service;

import javax.xml.stream.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PicaConversionService {

    // Regex to parse PICA+ field line: TAG[/OCCURRENCE] VALUE
    // TAG: 4 chars (e.g., 003@, 021A)
    // OCCURRENCE: Optional 2 digits (e.g., /00, /01)
    // VALUE: Remaining part, potentially containing subfields
    private static final Pattern FIELD_PATTERN = Pattern.compile("^([a-zA-Z0-9@]{4})(?:/(\\d{2}))?\\s+(.*)$");
    private static final char SUBFIELD_SEPARATOR = '\u001F'; // ASCII 31 (US - Unit Separator)
    private static final char LINE_TERMINATOR = '\u001E'; // ASCII 30 (RS - Record Separator)
    private static final String PICA_XML_NS = "info:srw/schema/5/picaXML-v1.0";

    public void convertPicaFile(Path inputPath, Path outputPath) throws IOException, XMLStreamException, TransformerException {
        if (!Files.exists(inputPath) || !Files.isReadable(inputPath)) {
            throw new FileNotFoundException("Input file not found or not readable: " + inputPath);
        }

        List<PicaRecord> records = parsePicaRecords(inputPath);
        System.out.println("Successfully parsed " + records.size() + " records.");

        writePicaXml(records, outputPath);
        System.out.println("Successfully wrote formatted PICA XML to: " + outputPath);
    }

    /**
     * Counts line breaks (\n, \r, \r\n) in a string.
     * This helps approximate original line numbers when the input was read differently.
     * Does not count LSEP, PS, NEL etc. as breaks here.
     * @param text The string to check.
     * @return The number of line breaks found.
     */
    private int countLineBreaks(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lineBreaks = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lineBreaks++;
            } else if (c == '\r') {
                // Check if next char is \n (CRLF)
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    // Count CRLF as one break, skip the LF
                    lineBreaks++;
                    i++;
                } else {
                    // Count CR as one break
                    lineBreaks++;
                }
            }
        }
        return lineBreaks;
    }


    private List<PicaRecord> parsePicaRecords(Path inputPath) throws IOException {
        List<PicaRecord> records = new ArrayList<>();
        PicaRecord currentRecord = null; // Initialize as null, create when first field or 003@ is found

        System.out.println("Starting PICA+ parsing from: " + inputPath);

        // Read the entire file content
        String content = Files.readString(inputPath, StandardCharsets.UTF_8);

        // Split the content based ONLY on the LINE_TERMINATOR
        // The -1 limit ensures trailing empty strings are kept if needed, though we trim later.
        String[] fieldLines = content.split(String.valueOf(LINE_TERMINATOR), -1);

        int effectiveFieldNumber = 0; // To track the logical field number for warnings
        int currentOriginalLineNumber = 1; // Tracks the starting line number of the current segment

        for (int i = 0; i < fieldLines.length; i++) {
            String segment = fieldLines[i];
            int segmentStartLineNumber = currentOriginalLineNumber; // Line number where this segment starts

            // Calculate line breaks within this segment *before* trimming
            int breaksInSegment = countLineBreaks(segment);

            String trimmedLine = segment.trim(); // Trim whitespace from the potential field line

            // Skip empty lines resulting from split (e.g., file starting/ending with \u001E)
            // or lines that become empty after trimming.
            if (trimmedLine.isEmpty()) {
                // Update line number even for skipped empty segments
                currentOriginalLineNumber += breaksInSegment;
                if (i < fieldLines.length - 1) { // Add 1 for the delimiter \u001E unless it's the last segment
                    currentOriginalLineNumber++; // Assuming \u001E itself doesn't contain \n or \r
                }
                continue; // Skip processing this empty segment
            }

            // Increment field number only for non-empty, non-comment lines processed
            effectiveFieldNumber++;

            // Skip comment/metadata lines starting with ##
            if (trimmedLine.startsWith("##")) {
                System.out.println("Info: Skipping metadata/comment line ~" + segmentStartLineNumber + " (Field " + effectiveFieldNumber + "): " + trimmedLine);
                // Update line number count after skipping
                currentOriginalLineNumber += breaksInSegment;
                if (i < fieldLines.length - 1) {
                    currentOriginalLineNumber++;
                }
                continue;
            }

            // Now, parse the trimmed line which should represent a single PICA field
            Matcher matcher = FIELD_PATTERN.matcher(trimmedLine);
            if (matcher.matches()) {
                String tag = matcher.group(1);
                String occurrence = matcher.group(2); // Might be null
                String valuePart = matcher.group(3); // Includes all chars until end, incl. LSEP etc.

                // Check for new record start (e.g., based on 003@ tag)
                if ("003@".equals(tag)) {
                    // If there's a previous record with fields, add it to the list
                    if (currentRecord != null && !currentRecord.getFields().isEmpty()) {
                        records.add(currentRecord);
                    }
                    // Start a new record
                    currentRecord = new PicaRecord();
                } else if (currentRecord == null) {
                    // If we encounter a non-003@ field and have no current record,
                    // create one, but maybe warn that the file didn't start as expected.
                    System.err.println("Warning: Line ~" + segmentStartLineNumber + " (Field " + effectiveFieldNumber + ", Tag: " + tag + ") encountered before the first record-starting field (e.g., 003@). Starting a new record implicitly.");
                    currentRecord = new PicaRecord();
                }

                // If currentRecord is still null here, something is wrong (should have been created)
                // This check prevents NullPointerException if the logic above fails.
                if (currentRecord == null) {
                     System.err.println("Error: Internal state error - currentRecord is null at Line ~" + segmentStartLineNumber + " (Field " + effectiveFieldNumber + "). Skipping field.");
                     // Update line number count before skipping
                     currentOriginalLineNumber += breaksInSegment;
                     if (i < fieldLines.length - 1) {
                         currentOriginalLineNumber++;
                     }
                     continue; // Skip this field
                }

                PicaField field = new PicaField(tag, occurrence);

                // Split value part into subfields using SUBFIELD_SEPARATOR
                // The split preserves LSEP etc. within the resulting parts
                String[] subfieldParts = valuePart.split(String.valueOf(SUBFIELD_SEPARATOR));

                for (String part : subfieldParts) {
                    // Expecting format like "aValue" or "9Value" after splitting
                    if (part.length() >= 1) { // Need at least one character for the code
                        char subfieldCode = part.charAt(0);
                        String subfieldValue = "";
                        if (part.length() > 1) {
                            // Get the rest of the string as value
                            subfieldValue = part.substring(1);
                        }
                        // Add the extracted subfield (subfieldValue now contains original chars like LSEP)
                        field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                    } else if (!part.isEmpty()) {
                        // This case might occur if there are consecutive separators, e.g., "\u001F\u001F"
                        // or if the value part ends with a separator.
                        System.err.println("Warning: Line ~" + segmentStartLineNumber + " (Field " + effectiveFieldNumber + "): Encountered unexpected empty subfield part after splitting in value: '" + valuePart + "'");
                    }
                    // If part is completely empty (e.g., from splitting ""), do nothing.
                }
                currentRecord.addField(field);
            } else {
                // Use the approximate line number in the warning here, as that's what failed matching
                System.err.println("Warning: Line ~" + segmentStartLineNumber + " (Field " + effectiveFieldNumber + ") could not be parsed as a PICA field: '" + trimmedLine + "'");
            }

            // Update line number count for the next segment
            currentOriginalLineNumber += breaksInSegment;
            if (i < fieldLines.length - 1) { // Add 1 for the delimiter \u001E
                currentOriginalLineNumber++;
            }
        } // End of loop over fieldLines

        // Add the last record if it exists and has fields
        if (currentRecord != null && !currentRecord.getFields().isEmpty()) {
            records.add(currentRecord);
        }

        return records;
    }

    // writePicaXml remains the same, but benefits from correct parsing
    private void writePicaXml(List<PicaRecord> records, Path outputPath) throws IOException, XMLStreamException, TransformerException {
        System.out.println("Generating PICA XML...");
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        StringWriter stringWriter = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = factory.createXMLStreamWriter(stringWriter);

            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.writeStartElement("collection");
            writer.writeDefaultNamespace(PICA_XML_NS);

            for (PicaRecord record : records) {
                writer.writeStartElement("record");
                for (PicaField field : record.getFields()) {
                    writer.writeStartElement("datafield");
                    writer.writeAttribute("tag", field.getTag());
                    if (field.getOccurrence() != null) {
                        writer.writeAttribute("occurrence", field.getOccurrence());
                    }
                    // Occurrence is optional in PICA XML if it's the first/only one.

                    for (PicaSubfield subfield : field.getSubfields()) {
                        writer.writeStartElement("subfield");
                        writer.writeAttribute("code", String.valueOf(subfield.getCode()));
                        writer.writeCharacters(subfield.getValue());
                        writer.writeEndElement(); // subfield
                    }
                    writer.writeEndElement(); // datafield
                }
                writer.writeEndElement(); // record
            }

            writer.writeEndElement(); // collection
            writer.writeEndDocument();
            writer.flush();
            writer.close(); // Close writer to finalize stringWriter

            // Format the XML string and write to file
            System.out.println("Formatting and writing PICA XML to: " + outputPath);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            try {
                transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerConfigurationException e) {
                System.err.println("Warning: Could not set secure processing feature for TransformerFactory: " + e.getMessage());
            }

            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());

            Source xmlInput = new StreamSource(new StringReader(stringWriter.toString()));
            Result xmlOutput = new StreamResult(new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputPath)), StandardCharsets.UTF_8));

            transformer.transform(xmlInput, xmlOutput);

        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (XMLStreamException e) {
                    // Ignore closing errors if already handling another exception
                }
            }
        }
    }
}
