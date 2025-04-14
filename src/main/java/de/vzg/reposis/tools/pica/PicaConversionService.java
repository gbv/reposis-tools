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

    private List<PicaRecord> parsePicaRecords(Path inputPath) throws IOException {
        List<PicaRecord> records = new ArrayList<>();
        PicaRecord currentRecord = new PicaRecord();
        boolean inRecord = false;

        System.out.println("Starting PICA+ parsing from: " + inputPath);

        try (BufferedReader reader = Files.newBufferedReader(inputPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip comment/metadata lines starting with ##
                if (line.startsWith("##")) {
                    System.out.println("Info: Skipping metadata/comment line " + lineNumber + ": " + line);
                    continue; // Move to the next line
                }

                String originalLineForWarning = line; // Keep original for potential warning messages
                boolean separatorFound = false;

                // Handle the specific line separator at the beginning of the line
                if (line.startsWith(String.valueOf(LINE_TERMINATOR))) {
                    line = line.substring(1);
                    separatorFound = true;
                }

                // Trim the line *after* potentially removing the separator
                line = line.trim();

                // Check for blank lines *after* trimming
                if (line.isEmpty()) {
                    // Blank line indicates end of a record
                    if (inRecord) {
                        if (!currentRecord.getFields().isEmpty()) {
                            records.add(currentRecord);
                        }
                        currentRecord = new PicaRecord();
                        inRecord = false;
                    } else if (!separatorFound && !originalLineForWarning.trim().isEmpty()) {
                        System.err.println("Warning: Line " + lineNumber + " does not start with expected separator (\\u001E) and contains only whitespace. Original: '" + originalLineForWarning + "'");
                    }
                } else {
                    if (!separatorFound) {
                        System.err.println("Warning: Line " + lineNumber + " does not start with expected separator (\\u001E). Processing anyway: '" + originalLineForWarning + "'");
                    }

                    inRecord = true;
                    Matcher matcher = FIELD_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String tag = matcher.group(1);
                        String occurrence = matcher.group(2);
                        String valuePart = matcher.group(3);

                        PicaField field = new PicaField(tag, occurrence);
                        String[] subfieldParts = valuePart.split(String.valueOf(SUBFIELD_SEPARATOR));

                        for (String part : subfieldParts) {
                            if (part.length() >= 1) {
                                char subfieldCode = part.charAt(0);
                                String subfieldValue = (part.length() > 1) ? part.substring(1) : "";
                                field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                            } else if (!part.isEmpty()) {
                                System.err.println("Warning: Line " + lineNumber + ": Encountered unexpected empty subfield part after splitting in value: '" + valuePart + "'");
                            }
                        }
                        currentRecord.addField(field);
                    } else {
                        System.err.println("Warning: Line " + lineNumber + " could not be parsed as a PICA field: '" + line + "'");
                    }
                }
            }
            // Add the last record if the file doesn't end with a blank line
            if (inRecord && !currentRecord.getFields().isEmpty()) {
                records.add(currentRecord);
            }
        }
        return records;
    }

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
