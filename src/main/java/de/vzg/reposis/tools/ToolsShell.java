package de.vzg.reposis.tools;

import de.vzg.reposis.tools.mycore.ClasspathUriResolver;
import de.vzg.reposis.tools.mycore.MyCoReObjectService;
import de.vzg.reposis.tools.mycore.PicaMyCoReConversionService;
import de.vzg.reposis.tools.pica.PicaConversionService;
import de.vzg.reposis.tools.pica.PicaUtils;
import de.vzg.reposis.tools.sru.SRUService;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@ShellComponent
public class ToolsShell {

    private static final Logger log = LoggerFactory.getLogger(ToolsShell.class);

    private final PicaConversionService picaConversionService;
    private final PicaMyCoReConversionService picaMyCoReConversionService;
    private final SRUService sruService; // Added
    private final MyCoReObjectService myCoReObjectService; // Added

    // XPath for selecting 002@ $0
    private static final Namespace PICA_XML_NS = Namespace.getNamespace("pica", "info:srw/schema/5/picaXML-v1.0");
    private static final XPathExpression<Element> PICA_002A_SUBFIELD_0_XPATH = XPathFactory.instance().compile(
            "pica:datafield[@tag='002@']/pica:subfield[@code='0']", Filters.element(), null, PICA_XML_NS);


    // Constructor injection for the services
    @Autowired // Ensure Spring injects all required beans
    public ToolsShell(PicaConversionService picaConversionService,
                      PicaMyCoReConversionService picaMyCoReConversionService,
                      SRUService sruService, // Added
                      MyCoReObjectService myCoReObjectService) { // Added
        this.picaConversionService = picaConversionService;
        this.picaMyCoReConversionService = picaMyCoReConversionService;
        this.sruService = sruService; // Added
        this.myCoReObjectService = myCoReObjectService; // Added
    }

    @ShellMethod(key = "convert-import-picaxml", value = "Converts PICA Importformat file to PICA XML.")
    public void convertPica(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input PICA Importformat file.") String input,
        @ShellOption(value = { "-o", "--output" }, help = "Path to the output PICA XML file.") String output) {

        Path inputPath = Paths.get(input);
        Path outputPath = Paths.get(output);

        try {
            picaConversionService.convertPicaFile(inputPath, outputPath);
            System.out.println("PICA conversion completed successfully.");
        } catch (IOException e) {
            System.err.println("Error during file I/O: " + e.getMessage());
            // Consider more specific error handling or logging
        } catch (TransformerException e) { // Removed XMLStreamException catch
            System.err.println("Error transforming/writing formatted XML: " + e.getMessage());
        } catch (Exception e) { // Catch unexpected errors
            System.err.println("An unexpected error occurred during PICA conversion: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for unexpected errors
        }
    }


    @ShellMethod(key = "convert-pica-mycore", value = "Converts PICA XML to MyCoRe objects.")
    public void convertPicaXMLToMyCoReObjects(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input PICA XML file.") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the directory for outputting MyCoRe objects.") String output,
            @ShellOption(value = {"--id-mapper"}, help = "Path to the file containing ISBN, ISSN, URN to MyCoRe ID mappings (Properties format). Will be created/updated.") String idMapperPathStr,
            @ShellOption(value = {"--id-base"}, help = "Template for generating new MyCoRe object IDs (e.g., artus_mods_00000000).") String idBase,
            @ShellOption(value = { "-s", "--stylesheet" },
                    help = "Path to the XSLT stylesheet in the classpath for transformation.") String stylesheet
    ) {
        Path inputPath = Paths.get(input);
        Path outputDirPath = Paths.get(output);
        Path idMapperPath = Paths.get(idMapperPathStr);

        try {
            picaMyCoReConversionService.convertPicaXmlToMyCoRe(inputPath, outputDirPath, idMapperPath, idBase, stylesheet);
            System.out.println("PICA XML to MyCoRe conversion completed successfully.");
        } catch (FileNotFoundException e) {
            System.err.println("Error: Input file not found: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error during file I/O: " + e.getMessage());
        } catch (org.jdom2.JDOMException e) { // Added catch for JDOMException
            System.err.println("Error parsing XML with JDOM: " + e.getMessage());
            e.printStackTrace(); // Include stack trace for JDOM errors
        } catch (TransformerException e) { // Removed XMLStreamException catch
            System.err.println("Error during XSLT transformation: " + e.getMessage());
            e.printStackTrace(); // Include stack trace for XSLT errors
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid argument provided: " + e.getMessage());
        } catch (Exception e) { // Catch unexpected errors
            System.err.println("An unexpected error occurred during PICA XML to MyCoRe conversion: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for unexpected errors
        }
    }

    @ShellMethod(key = "convert-isbn-list", value = "Fetches PICA records for a list of ISBNs via SRU and converts them to MyCoRe objects.")
    public void convertIsbnList(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input file containing ISBNs (one per line).") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the directory for outputting MyCoRe objects.") String output,
            @ShellOption(value = {"--id-mapper"}, help = "Path to the file containing ISBN/PPN to MyCoRe ID mappings (Properties format). Will be created/updated.") String idMapperPathStr,
            @ShellOption(value = {"--id-base"}, help = "Template for generating new MyCoRe object IDs (e.g., reposis_mods_00000000).") String idBase,
            @ShellOption(value = {"-s", "--stylesheet"}, help = "Path to the XSLT stylesheet in the classpath for transformation (e.g., pica2mods_artus.xsl).") String stylesheet
    ) {
        Path inputPath = Paths.get(input);
        Path outputDirPath = Paths.get(output);
        Path idMapperPath = Paths.get(idMapperPathStr);

        log.info("Starting ISBN list to MyCoRe conversion...");
        log.info("Input ISBN list: {}", inputPath);
        log.info("Output Directory: {}", outputDirPath);
        log.info("ID Mapper File: {}", idMapperPath);
        log.info("MyCoRe ID Base: {}", idBase);
        log.info("XSLT Stylesheet: {}", stylesheet);

        try {
            // 1. Load or initialize ID Mapper
            Properties idMapper = loadIdMapper(idMapperPath);
            boolean mapperChanged = false;

            // 2. Prepare ID Generation
            IdGenerator idGenerator = new IdGenerator(idBase, idMapper);

            // 3. Ensure output directory exists
            Files.createDirectories(outputDirPath);

            // 4. Prepare XSLT Transformer
            Transformer transformer = setupTransformer(stylesheet);
            XMLOutputter xmlRecordOutputter = new XMLOutputter(Format.getRawFormat()); // For converting record Element to String for XSLT input
            XMLOutputter finalOutputter = new XMLOutputter(Format.getPrettyFormat()); // For final file output

            // 5. Read ISBNs and process
            List<String> isbns = Files.readAllLines(inputPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")) // Ignore empty lines and comments
                    .collect(Collectors.toList());

            log.info("Found {} ISBNs to process.", isbns.size());
            int processedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;
            int newIdsGenerated = 0;

            for (String isbn : isbns) {
                processedCount++;
                log.info("Processing ISBN {}/{}: {}", processedCount, isbns.size(), isbn);

                try {
                    // Check if ISBN is already mapped
                    if (idMapper.containsKey(isbn)) {
                        log.info("ISBN {} already mapped to {}. Skipping.", isbn, idMapper.getProperty(isbn));
                        skippedCount++;
                        continue;
                    }

                    // Fetch PICA records via SRU
                    List<Element> picaRecords = sruService.resolvePicaByISBN(isbn);
                    if (picaRecords.isEmpty()) {
                        log.warn("No PICA records found for ISBN {}. Skipping.", isbn);
                        skippedCount++;
                        continue;
                    }

                    // Select the best record
                    Element selectedRecord = selectBestPicaRecord(picaRecords, isbn);
                    if (selectedRecord == null) {
                        log.warn("Could not select a suitable PICA record for ISBN {}. Skipping.", isbn);
                        skippedCount++;
                        continue; // Should not happen if picaRecords is not empty, but safety check
                    }

                    // Extract PPN
                    String ppn = PicaUtils.extractPpnFromRecord(selectedRecord);
                    if (ppn == null) {
                        log.warn("Could not extract PPN from selected record for ISBN {}. Skipping.", isbn);
                        XMLOutputter debugOutputter = new XMLOutputter(Format.getCompactFormat());
                        log.debug("Record without PPN: {}", debugOutputter.outputString(selectedRecord));
                        skippedCount++;
                        continue;
                    }
                    log.debug("Extracted PPN {} for ISBN {}", ppn, isbn);

                    // Determine MyCoRe ID
                    String mycoreId;
                    if (idMapper.containsKey(ppn)) {
                        mycoreId = idMapper.getProperty(ppn);
                        log.info("PPN {} already mapped to {}. Using existing ID.", ppn, mycoreId);
                        // Add mapping for the current ISBN as well
                        if (!idMapper.containsKey(isbn)) {
                            idMapper.setProperty(isbn, mycoreId);
                            mapperChanged = true;
                            log.info("Added mapping for ISBN {} -> {}", isbn, mycoreId);
                        }
                    } else {
                        mycoreId = idGenerator.generateNextId();
                        idMapper.setProperty(ppn, mycoreId);
                        idMapper.setProperty(isbn, mycoreId); // Map ISBN as well
                        mapperChanged = true;
                        newIdsGenerated++;
                        log.info("Generated new MyCoRe ID {} for PPN {} / ISBN {}", mycoreId, ppn, isbn);
                    }

                    // Perform XSLT Transformation
                    log.debug("Transforming record for PPN {} (MyCoRe ID {})", ppn, mycoreId);
                    transformer.setParameter("ObjectID", mycoreId); // Set ObjectID parameter for XSLT

                    String recordXmlString = xmlRecordOutputter.outputString(selectedRecord);
                    Source xmlSource = new StreamSource(new StringReader(recordXmlString));
                    StringWriter modsOutputWriter = new StringWriter();
                    Result modsResult = new StreamResult(modsOutputWriter);

                    transformer.transform(xmlSource, modsResult);
                    String modsXml = modsOutputWriter.toString();
                    transformer.clearParameters(); // Clear parameters for next use

                    // Wrap in MyCoRe frame
                    Document mycoreDocument = myCoReObjectService.wrapInMyCoReFrame(modsXml, mycoreId, "published"); // Assuming "published" status

                    // Write the final MyCoRe object file
                    Path outputFilePath = outputDirPath.resolve(mycoreId + ".xml");
                    try (OutputStreamWriter fileWriter = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputFilePath)), StandardCharsets.UTF_8)) {
                        finalOutputter.output(mycoreDocument, fileWriter);
                        log.debug("Successfully wrote MyCoRe object to {}", outputFilePath);
                    }

                } catch (Exception e) {
                    log.error("Error processing ISBN {}: {}", isbn, e.getMessage(), e);
                    errorCount++;
                }
            } // End ISBN loop

            // 6. Save ID Mapper if changed
            if (mapperChanged) {
                saveIdMapper(idMapper, idMapperPath);
            }

            log.info("ISBN list processing finished.");
            log.info("Summary: {} total ISBNs, {} skipped, {} errors.", isbns.size(), skippedCount, errorCount);
            if (mapperChanged) {
                log.info("Generated {} new MyCoRe IDs and updated mapper file '{}'.", newIdsGenerated, idMapperPath);
            }

        } catch (IOException e) {
            log.error("File I/O error: {}", e.getMessage(), e);
            System.err.println("Error during file I/O: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("Invalid argument: {}", e.getMessage(), e);
            System.err.println("Error: Invalid argument provided: " + e.getMessage());
        } catch (TransformerException e) {
            log.error("XSLT transformation error: {}", e.getMessage(), e);
            System.err.println("Error during XSLT transformation: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            log.error("An unexpected error occurred: {}", e.getMessage(), e);
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- Helper Methods ---

    /**
     * Selects the best PICA record from a list for an ISBN, prioritizing records where 002@ $0 starts with 'Aa'.
     * If multiple match, takes the first matching. If none match, takes the first record in the list.
     * Returns a detached clone of the selected record.
     */
    private Element selectBestPicaRecord(List<Element> records, String isbn) {
        if (records == null || records.isEmpty()) {
            return null;
        }

        Element bestMatch = null;
        for (Element record : records) {
            if (checkPicaField002AStartsWithAa(record)) {
                log.debug("Found prioritized record (002@ $0 starts with 'Aa') for ISBN {}", isbn);
                bestMatch = record;
                break; // Take the first one matching the criteria
            }
        }

        if (bestMatch != null) {
            return bestMatch.clone(); // Return a detached clone
        } else {
            // No record matched the priority rule, return the first record found
            log.debug("No record matched priority rule (002@ $0 starts with 'Aa') for ISBN {}. Selecting first record.", isbn);
            return records.get(0).clone(); // Return a detached clone
        }
    }

    /**
     * Checks if the PICA record has a field 002@ with subfield $0 starting with "Aa".
     */
    private boolean checkPicaField002AStartsWithAa(Element record) {
        if (record == null) {
            return false;
        }
        Element subfield0 = PICA_002A_SUBFIELD_0_XPATH.evaluateFirst(record);
        if (subfield0 != null) {
            String value = subfield0.getTextTrim();
            return value != null && value.startsWith("Aa");
        }
        return false;
    }


    private Transformer setupTransformer(String stylesheet) throws TransformerConfigurationException {
        String saxonFactoryClass = "net.sf.saxon.TransformerFactoryImpl";
        TransformerFactory transformerFactory;
        try {
            transformerFactory = TransformerFactory.newInstance(saxonFactoryClass, null);
            log.info("Using Saxon-HE TransformerFactory: {}", saxonFactoryClass);
        } catch (TransformerFactoryConfigurationError e) {
            log.warn("Could not instantiate specific Saxon-HE TransformerFactory ('{}'). Falling back to default JAXP lookup. Error: {}", saxonFactoryClass, e.getMessage());
            transformerFactory = TransformerFactory.newInstance();
        }

        try {
            transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (TransformerConfigurationException e) {
            log.warn("Could not set secure processing feature for TransformerFactory: {}", e.getMessage());
        }

        // Use ClasspathUriResolver for resolving xsl:include, xsl:import etc. within the classpath
        // Note: This basic resolver doesn't handle dynamic 'document()' calls to fetch external PICA records.
        transformerFactory.setURIResolver(new ClasspathUriResolver(Map.of())); // Pass empty map as we don't preload PPNs here

        Source xsltSource = loadXsltFromClasspath("/xsl/" + stylesheet);
        return transformerFactory.newTransformer(xsltSource);
    }


    private Source loadXsltFromClasspath(String xsltPath) throws TransformerConfigurationException {
        log.debug("Loading XSLT from classpath: {}", xsltPath);
        InputStream xsltStream = ToolsShell.class.getResourceAsStream(xsltPath);
        if (xsltStream == null) {
            log.error("XSLT file not found on classpath: {}", xsltPath);
            throw new TransformerConfigurationException("XSLT file not found on classpath: " + xsltPath);
        }
        return new StreamSource(xsltStream);
    }

    private Properties loadIdMapper(Path idMapperPath) throws IOException {
        Properties props = new Properties();
        if (Files.exists(idMapperPath)) {
            log.debug("Loading existing ID mapper from: {}", idMapperPath);
            try (InputStream is = new BufferedInputStream(Files.newInputStream(idMapperPath))) {
                props.load(is);
            }
            log.info("Loaded {} mappings from {}", props.size(), idMapperPath);
        } else {
            log.info("ID mapper file not found at {}, will create if needed.", idMapperPath);
        }
        return props;
    }

    private void saveIdMapper(Properties idMapper, Path idMapperPath) throws IOException {
        log.info("Saving {} mappings to ID mapper file: {}", idMapper.size(), idMapperPath);
        // Ensure parent directory exists
        if (idMapperPath.getParent() != null) {
            Files.createDirectories(idMapperPath.getParent());
        }
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(idMapperPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            idMapper.store(os, "Identifier (ISBN/PPN) to MyCoRe ID Mapping");
        }
        log.debug("ID mapper saved successfully.");
    }

    /**
     * Helper class to manage MyCoRe ID generation based on a template and existing IDs.
     * Copied from PicaMyCoReConversionService - consider making this a top-level utility.
     */
    private static class IdGenerator {
        private final String prefix;
        private final String format;
        private final AtomicLong counter;
        private final Pattern idPattern;

        IdGenerator(String idBase, Properties idMapper) {
            Matcher matcher = Pattern.compile("^(.*?)(\\d+)$").matcher(idBase);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid idBase format. Expected format like 'prefix_NNN'. Got: " + idBase);
            }
            this.prefix = matcher.group(1);
            String numberPart = matcher.group(2);
            int numberLength = numberPart.length();
            this.format = "%0" + numberLength + "d";

            this.idPattern = Pattern.compile("^" + Pattern.quote(this.prefix) + "(\\d{" + numberLength + "})$");

            long initialBaseNumber = Long.parseLong(numberPart);
            // Find max number from values (MyCoRe IDs) in the mapper
            long maxExistingNumber = findMaxExistingNumber(idMapper);

            this.counter = new AtomicLong(Math.max(initialBaseNumber -1 , maxExistingNumber)); // Start counter before the next ID
            log.info("ID Generator initialized. Prefix='{}', Format='{}', Next ID number={}", prefix, format, counter.get() + 1);
        }

        private long findMaxExistingNumber(Properties idMapper) {
            return idMapper.stringPropertyNames().stream()
                    .map(idMapper::getProperty) // Get MyCoRe IDs (the values)
                    .distinct() // Avoid parsing the same ID multiple times if mapped from different keys
                    .map(idPattern::matcher)
                    .filter(Matcher::matches)
                    .map(m -> m.group(1))
                    .mapToLong(Long::parseLong)
                    .max()
                    .orElse(-1L); // Default if no matching IDs found
        }

        String generateNextId() {
            long nextNumber = counter.incrementAndGet();
            return prefix + String.format(format, nextNumber);
        }
    }
}
