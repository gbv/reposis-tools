package de.vzg.reposis.tools;

import de.vzg.reposis.tools.pica.PicaField;
import de.vzg.reposis.tools.pica.PicaRecord;
import de.vzg.reposis.tools.pica.PicaSubfield;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import javax.xml.stream.*;
import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ShellComponent
public class ToolsShell {

    // Regex to parse PICA+ field line: TAG[/OCCURRENCE] VALUE
    // TAG: 4 chars (e.g., 003@, 021A)
    // OCCURRENCE: Optional 2 digits (e.g., /00, /01)
    // VALUE: Remaining part, potentially containing subfields
    private static final Pattern FIELD_PATTERN = Pattern.compile("^([a-zA-Z0-9@]{4})(?:/(\\d{2}))?\\s+(.*)$");
    private static final char SUBFIELD_SEPARATOR = '\u001F'; // ASCII 31 (US - Unit Separator)
    private static final char LINE_TERMINATOR = '\u001E'; // ASCII 30 (RS - Record Separator)
    private static final String PICA_XML_NS = "info:srw/schema/5/picaXML-v1.0";


    @ShellMethod(key = "convert-pica", value = "Converts PICA+ Importformat file (lines end with \\u001E, subfields with \\u001F) to PICA XML.")
    public void convertPica(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input PICA+ Importformat file.") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the output PICA XML file.") String output) {

        Path inputPath = Paths.get(input);
        Path outputPath = Paths.get(output);

        if (!Files.exists(inputPath) || !Files.isReadable(inputPath)) {
            System.err.println("Error: Input file not found or not readable: " + input);
            return;
        }

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

                // Handle the specific line separator at the beginning of the line
                if (line.startsWith(String.valueOf(LINE_TERMINATOR))) {
                    line = line.substring(1);
                } else if (!line.trim().isEmpty()) {
                    // Only warn if a non-empty, non-blank line doesn't start with the separator
                    // Blank lines separating records won't have the separator.
                    System.err.println("Warning: Line " + lineNumber + " does not start with expected separator (\\u001E). Processing anyway: " + line);
                }

                if (line.trim().isEmpty()) {
                    // Blank line indicates end of a record
                    if (inRecord) {
                        if (!currentRecord.getFields().isEmpty()) {
                            records.add(currentRecord);
                        }
                        currentRecord = new PicaRecord();
                        inRecord = false;
                    }
                } else {
                    inRecord = true;
                    Matcher matcher = FIELD_PATTERN.matcher(line);
                    if (matcher.matches()) {
                        String tag = matcher.group(1);
                        String occurrence = matcher.group(2); // Might be null if no occurrence specified
                        String valuePart = matcher.group(3);

                        PicaField field = new PicaField(tag, occurrence);

                        // Split value part into subfields using SUBFIELD_SEPARATOR
                        // Example valuePart: "aSubfield A value\u001F9Subfield 9 value"
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
                                // Add the extracted subfield
                                field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                            } else if (!part.isEmpty()){
                                // This case might occur if there are consecutive separators, e.g., "\u001F\u001F"
                                // or if the value part ends with a separator.
                                System.err.println("Warning: Line " + lineNumber + ": Encountered unexpected empty subfield part after splitting in value: '" + valuePart + "'");
                            }
                            // If part is completely empty (e.g., from splitting ""), do nothing.
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
        } catch (IOException e) {
            System.err.println("Error reading input file: " + e.getMessage());
            return;
        }

        System.out.println("Successfully parsed " + records.size() + " records.");

        // Write PICA XML Output (first to string, then format)
        System.out.println("Generating PICA XML...");
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        // Requires factory.setProperty("javax.xml.stream.isRepairingNamespaces", true); for automatic prefixing if needed, but we set default NS.
        StringWriter stringWriter = new StringWriter();
        XMLStreamWriter writer = null;
        try {
            writer = factory.createXMLStreamWriter(stringWriter);

            writer.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0");
            writer.writeStartElement("collection"); // No prefix needed due to default namespace
            writer.writeDefaultNamespace(PICA_XML_NS);

            for (PicaRecord record : records) {
                writer.writeStartElement("record");
                for (PicaField field : record.getFields()) {
                    writer.writeStartElement("datafield");
                    writer.writeAttribute("tag", field.getTag());
                    if (field.getOccurrence() != null) {
                        writer.writeAttribute("occurrence", field.getOccurrence());
                    } else {
                         // PICA XML schema might require occurrence, default to "01" or "00"? Let's default to "01" if null
                         // Check PICA XML spec. Assuming "01" if not present is common. Let's use "01".
                         // Update: PICA XML spec often omits occurrence if it's the first/only one. Let's omit it if null.
                    }

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
            writer.writeEndElement(); // collection
            writer.writeEndDocument();
            writer.flush();
            writer.close(); // Close writer to finalize stringWriter

            // Now format the XML string and write to file
            System.out.println("Formatting and writing PICA XML to: " + outputPath);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            // Prevent XXE attacks by explicitly disabling external DTDs/entities
            try {
                 transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            } catch (TransformerConfigurationException e) {
                 System.err.println("Warning: Could not set secure processing feature for TransformerFactory: " + e.getMessage());
                 // Log or handle appropriately if security is critical
            }

            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            // Standard JDK indent amount is usually 2 spaces, adjust if needed:
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            // transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes"); // Keep the declaration

            Source xmlInput = new StreamSource(new StringReader(stringWriter.toString()));
            Result xmlOutput = new StreamResult(new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputPath)), StandardCharsets.UTF_8));

            transformer.transform(xmlInput, xmlOutput);

            System.out.println("Successfully wrote formatted PICA XML.");

        } catch (XMLStreamException e) {
            System.err.println("Error generating XML structure: " + e.getMessage());
        } catch (TransformerConfigurationException e) {
             System.err.println("Error configuring XML transformer: " + e.getMessage());
        } catch (TransformerException e) {
            System.err.println("Error transforming/writing formatted XML: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error writing output file: " + e.getMessage());
        } finally {
             if(writer != null) {
                 try {
                     // Ensure writer is closed even if exceptions occur before explicit close
                     writer.close();
                 } catch (XMLStreamException e) {
                     // Ignore closing errors if already handling another exception
                 }
             }
        }
    }
}
