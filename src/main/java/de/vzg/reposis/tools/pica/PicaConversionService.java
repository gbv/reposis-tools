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
    // Example line after stripping leading \u001E: "003@ $012345X" or "021A/01 $aEin Buch$hzum Lesen"
    private static final Pattern FIELD_PATTERN = Pattern.compile("^([a-zA-Z0-9@]{4})(?:/(\\d{2}))?\\s+(.*)$");
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
            String trimmedRecordBlock = recordBlock.trim(); // Trim whitespace around the record block

            // Skip empty blocks resulting from split (e.g., file starting/ending with separator)
            if (trimmedRecordBlock.isEmpty()) {
                System.out.println("Info: Skipping empty record block (Record #" + recordNumber + ")");
                continue;
            }

            // Start a new PicaRecord for this block
            currentRecord = new PicaRecord();
            int lineNumberInRecord = 0;

            // Split the record block into lines based on newline character
            String[] lines = trimmedRecordBlock.split("\n");

            for (String line : lines) {
                lineNumberInRecord++;
                String trimmedLine = line.trim(); // Trim leading/trailing whitespace from the line

                // Skip empty lines within a record block
                if (trimmedLine.isEmpty()) {
                    continue;
                }

                // Skip comment lines starting with #
                if (trimmedLine.startsWith("#")) {
                    System.out.println("Info: Skipping comment (Record #" + recordNumber + ", Line " + lineNumberInRecord + "): " + trimmedLine);
                    continue;
                }

                // Check if the line starts with the FIELD_INTRODUCER (\u001E)
                if (trimmedLine.length() > 0 && trimmedLine.charAt(0) == FIELD_INTRODUCER) {
                    // Remove the introducer character before parsing
                    String fieldData = trimmedLine.substring(1);

                    // Now, parse the remaining part which should be the PICA field
                    Matcher matcher = FIELD_PATTERN.matcher(fieldData);
                    if (matcher.matches()) {
                        String tag = matcher.group(1);
                        String occurrence = matcher.group(2); // Might be null
                        String valuePart = matcher.group(3); // Includes subfields separated by \u001F

                        PicaField field = new PicaField(tag, occurrence);

                        // Split value part into subfields using SUBFIELD_SEPARATOR (\u001F)
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
                                field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                            } else if (!part.isEmpty()) {
                                // This case might occur if there are consecutive separators, e.g., "\u001F\u001F"
                                // or if the value part ends with a separator.
                                System.err.println("Warning: Record #" + recordNumber + ", Line " + lineNumberInRecord + ": Encountered unexpected empty subfield part after splitting in value: '" + valuePart + "'");
                            }
                            // If part is completely empty (e.g., from splitting ""), do nothing.
                        }
                        currentRecord.addField(field);
                    } else {
                        // The line started with \u001E but didn't match the field pattern
                        System.err.println("Warning: Record #" + recordNumber + ", Line " + lineNumberInRecord + " started with Field Introducer but could not be parsed as a PICA field: '" + fieldData + "'");
                    }
                } else {
                    // Line does not start with the expected Field Introducer
                    System.err.println("Warning: Record #" + recordNumber + ", Line " + lineNumberInRecord + " does not start with the PICA Field Introducer (\\u001E): '" + trimmedLine + "'");
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
