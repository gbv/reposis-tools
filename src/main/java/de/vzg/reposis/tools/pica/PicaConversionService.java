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
// import java.util.regex.Matcher; // Removed
// import java.util.regex.Pattern; // Removed

@Service
public class PicaConversionService {

    // Format description for PICA+ field line: TAG[/OCCURRENCE] VALUE
    // TAG: 4 chars (e.g., 003@, 021A)
    // OCCURRENCE: Optional 2 digits preceded by '/' (e.g., /00, /01)
    // VALUE: Remaining part after the first space, potentially containing subfields
    // Example line after stripping leading \u001E: "003@ 12345X" or "021A/01 aEin Buch\u001FbZum Lesen"
    // Note: The space after TAG or OCCURRENCE is the separator to the VALUE part.
    // private static final Pattern FIELD_PATTERN = Pattern.compile("^([a-zA-Z0-9@]{4})(?:/(\\d{2}))?\\s+(.*)$"); // Removed
    private static final char SUBFIELD_SEPARATOR = '\u001F'; // ASCII 31 (US - Unit Separator)
    private static final char FIELD_INTRODUCER = '\u001E'; // ASCII 30 (RS - Record Separator) - Introduces a field line
    private static final String RECORD_SEPARATOR = "\u001D\n"; // ASCII 29 (GS - Group Separator) + Newline - Separates records
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

    private List<PicaRecord> parsePicaRecords(Path inputPath) throws IOException {
        List<PicaRecord> records = new ArrayList<>();
        PicaRecord currentRecord = null; // Initialize as null, create when first field or 003@ is found

        System.out.println("Starting PICA Importformat parsing from: " + inputPath);

        // Read the entire file content
        String content = Files.readString(inputPath, StandardCharsets.UTF_8);

        // Split the content into records based on RECORD_SEPARATOR (\u001D\n)
        String[] recordBlocks = content.split(RECORD_SEPARATOR);

        int recordNumber = 0;
        for (String recordBlock : recordBlocks) {
            recordNumber++;
            // String trimmedRecordBlock = recordBlock.trim(); // Trim whitespace around the record block - REMOVED

            // Skip empty blocks resulting from split (e.g., file starting/ending with separator)
            // Use isBlank() to check if the original block was empty or just whitespace
            if (recordBlock.isBlank()) {
                System.out.println("Info: Skipping empty or blank record block (Record #" + recordNumber + ")");
                continue;
            }

            // Start a new PicaRecord for this block
            currentRecord = new PicaRecord();
            // int lineNumberInRecord = 0; // REMOVED

            // Split the record block into lines based on newline character
            // Use the original recordBlock here
            String[] lines = recordBlock.split("\n");

            for (String line : lines) {
                // lineNumberInRecord++; // REMOVED

                // DO NOT trim the line yet, as trim() removes the FIELD_INTRODUCER (\u001E)
                if (line == null || line.isEmpty()) { // Check for null or truly empty lines first
                    continue;
                }

                // Check if the raw line starts with the FIELD_INTRODUCER (\u001E)
                if (line.charAt(0) == FIELD_INTRODUCER) {
                    // Remove the introducer character first
                    String fieldData = line.substring(1);
                    // DO NOT trim whitespace before matching the pattern
                    // String fieldData = fieldDataWithPotentialWhitespace.trim(); // REMOVED

                    // Skip if the line only contained the introducer (and potentially whitespace, now handled by regex)
                    // The regex FIELD_PATTERN requires non-whitespace after the tag/occurrence,
                    // so an empty fieldData or one with only whitespace won't match.
                    // We might still want to skip completely blank lines *after* the introducer explicitly?
                    // Let's rely on the regex match failure for now.
                    // if (fieldData.isEmpty()) { // This check is less useful without trim()
                    //      System.out.println("Info: Skipping line with only Field Introducer (Record #" + recordNumber + ")"); // Line number removed
                    //      continue;
                    // }

                    // Now, parse the remaining part which should be the PICA field using string manipulation
                    String tag = null;
                    String occurrence = null;
                    String valuePart = null;
                    boolean parseSuccess = false;

                    if (fieldData.length() >= 4) { // Need at least 4 chars for the tag
                        String potentialTag = fieldData.substring(0, 4);
                        String restOfField = fieldData.substring(4);

                        // Check for occurrence (e.g., "/01")
                        if (restOfField.startsWith("/") && restOfField.length() >= 4 && Character.isDigit(restOfField.charAt(1)) && Character.isDigit(restOfField.charAt(2))) {
                            // Check for space after occurrence
                            if (restOfField.length() > 3 && Character.isWhitespace(restOfField.charAt(3))) {
                                tag = potentialTag;
                                occurrence = restOfField.substring(1, 3);
                                // Value part starts after the space
                                valuePart = restOfField.substring(4);
                                parseSuccess = true;
                            }
                            // else: Invalid format (no space after occurrence)
                        } else if (!restOfField.isEmpty() && Character.isWhitespace(restOfField.charAt(0))) {
                            // No occurrence, check for space after tag
                            tag = potentialTag;
                            occurrence = null; // Explicitly null
                            // Value part starts after the space
                            valuePart = restOfField.substring(1);
                            parseSuccess = true;
                        }
                        // else: Invalid format (no space after tag/occurrence or malformed occurrence)
                    }

                    if (parseSuccess) {
                        PicaField field = new PicaField(tag, occurrence);

                        // Split value part into subfields using SUBFIELD_SEPARATOR (\u001F)
                        // Ensure valuePart is not null before splitting
                        if (valuePart == null) {
                             System.err.println("Warning: Record #" + recordNumber + ": Parsed field successfully but valuePart is unexpectedly null. FieldData: '" + fieldData + "'"); // Line number removed
                             valuePart = ""; // Avoid NullPointerException, treat as empty value
                        }
                        // We do NOT trim subfield parts here, as leading/trailing spaces might be significant within values
                        String[] subfieldParts = valuePart.split(String.valueOf(SUBFIELD_SEPARATOR));

                        for (String part : subfieldParts) {
                            // Expecting format like "$aValue" or "$9Value" after splitting
                            // The first character is the subfield code separator '$' which is part of the valuePart matched by regex group 3
                            // Correction: The documentation says \u001F is the separator, not '$'.
                            // The regex captures everything after TAG[/OCC]\s+ as group 3.
                            // This group 3 starts directly with the first subfield code if \u001F is used correctly.
                            // Example: \u001E021A $aValue\u001F$bOther -> valuePart = "$aValue\u001F$bOther" -> WRONG ASSUMPTION
                            // Example according to spec: \u001E021A aValue\u001FbOther -> valuePart = "aValue\u001FbOther"
                            // Example from user: 047I  aCet article... -> valuePart = " aCet article..." (Note leading space)

                            // Let's re-evaluate the split and subfield extraction
                            // If valuePart = " aValue\u001FbOther"
                            // split by \u001F gives [" aValue", "bOther"]

                            if (part.length() >= 1) { // Need at least one character for the code
                                char subfieldCode = part.charAt(0);
                                String subfieldValue = "";
                                if (part.length() > 1) {
                                    // Get the rest of the string as value
                                    subfieldValue = part.substring(1);
                                }
                                // Add the extracted subfield. Value might contain leading/trailing spaces from original data.
                                field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                            } else if (!part.isEmpty()) {
                                // This case might occur if there are consecutive separators, e.g., "\u001F\u001F"
                                // or if the value part ends with a separator.
                                System.err.println("Warning: Record #" + recordNumber + ": Encountered unexpected empty subfield part after splitting in value: '" + valuePart + "'"); // Line number removed
                            }
                            // If part is completely empty (e.g., from splitting ""), do nothing.
                        }
                        currentRecord.addField(field);
                    } else {
                        // Parsing with string manipulation failed
                        System.err.println("Warning: Record #" + recordNumber + " started with Field Introducer but could not be parsed using string manipulation: '" + fieldData + "'"); // Line number removed
                    }
                } else {
                    // Line does not start with the expected Field Introducer.
                    // Check for blank lines or comments without trimming the original line.
                    // String trimmedLine = line.trim(); // REMOVED
                    if (line.isBlank()) { // Use isBlank() to ignore lines with only whitespace
                        // Was just whitespace, ignore.
                        continue;
                    }
                    // Skip comment lines starting with #, allowing for leading whitespace
                    if (line.stripLeading().startsWith("#")) { // Use stripLeading() before checking for comment
                        System.out.println("Info: Skipping comment (Record #" + recordNumber + "): " + line); // Line number removed, Log original line
                        continue;
                    }
                    // If it's not blank, not a comment, and didn't start with \u001E, it's an error.
                    System.err.println("Warning: Record #" + recordNumber + " does not start with the PICA Field Introducer (\\u001E): '" + line + "'"); // Line number removed, Log original line
                }
            } // End of loop over lines in record

            // Add the parsed record if it contains any fields
            if (!currentRecord.getFields().isEmpty()) {
                records.add(currentRecord);
            } else {
                 System.out.println("Info: Record #" + recordNumber + " resulted in an empty PicaRecord (no valid fields found). Skipping.");
            }
        } // End of loop over recordBlocks

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
