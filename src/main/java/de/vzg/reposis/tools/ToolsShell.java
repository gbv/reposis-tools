package de.vzg.reposis.tools;

import de.vzg.reposis.tools.pica.PicaField;
import de.vzg.reposis.tools.pica.PicaRecord;
import de.vzg.reposis.tools.pica.PicaSubfield;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
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
                // Handle the specific line terminator for this format
                if (line.endsWith(String.valueOf(LINE_TERMINATOR))) {
                    line = line.substring(0, line.length() - 1);
                } else if (!line.isEmpty()) {
                    // Only warn if the line isn't empty and doesn't have the terminator
                     System.err.println("Warning: Line " + lineNumber + " does not end with expected terminator (\\u001E). Processing anyway.");
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
                        String[] subfieldParts = valuePart.split(String.valueOf(SUBFIELD_SEPARATOR));

                        for (String part : subfieldParts) {
                            if (part.length() >= 2 && part.charAt(0) == '$') {
                                char subfieldCode = part.charAt(1);
                                String subfieldValue = part.substring(2);
                                field.addSubfield(new PicaSubfield(subfieldCode, subfieldValue));
                            } else if (!part.isEmpty()) {
                                // Handle case where the first part before any '$' might be considered a default subfield (e.g., '$a')
                                // Or treat it as value without subfield code if no '$' is present at all.
                                // For simplicity, let's assume standard PICA where value starts with $ or is empty.
                                // If the first part doesn't start with '$', it might be data without a subfield marker
                                // or part of the previous subfield if the split was incorrect.
                                // Let's add it as a subfield with a placeholder code ' ' if it's the only part.
                                if (subfieldParts.length == 1 && !part.isEmpty()) {
                                     field.addSubfield(new PicaSubfield(' ', part)); // Or handle as error/warning
                                     System.err.println("Warning: Line " + lineNumber + ": Field value part does not start with '$': '" + part + "'");
                                } else if (part.length() >=1 && part.charAt(0) != '$') {
                                     // This case happens if the value itself contains the subfield separator char, which is unlikely but possible.
                                     // Or if the first part has no code.
                                     System.err.println("Warning: Line " + lineNumber + ": Unexpected subfield part format: '" + part + "'");
                                }
                            }
                        }
                        currentRecord.addField(field);
                    } else {
                        System.err.println("Warning: Line " + lineNumber + " could not be parsed as a PICA field: " + line);
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

        // Write PICA XML Output
        System.out.println("Writing PICA XML to: " + outputPath);
        XMLOutputFactory factory = XMLOutputFactory.newInstance();
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(outputPath))) {
            XMLStreamWriter writer = factory.createXMLStreamWriter(out, StandardCharsets.UTF_8.name());

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
            writer.flush();
            writer.close();
            System.out.println("Successfully wrote PICA XML.");

        } catch (IOException e) {
            System.err.println("Error writing output file: " + e.getMessage());
        } catch (XMLStreamException e) {
            System.err.println("Error writing XML: " + e.getMessage());
        }
    }
}
