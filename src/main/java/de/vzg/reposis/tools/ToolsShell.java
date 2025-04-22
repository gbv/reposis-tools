package de.vzg.reposis.tools;

// Removed ClasspathUriResolver import as it's no longer used directly in this class
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
import org.mycore.pica2mods.xsl.Pica2ModsManager;
import org.mycore.pica2mods.xsl.Pica2ModsXSLTURIResolver;
import org.mycore.pica2mods.xsl.model.Pica2ModsConfig;
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

            // 4. Prepare pica2mods components and XSLT Transformer
            Pica2ModsConfig pica2ModsConfig = new Pica2ModsConfig();
            // Use a placeholder or make configurable if needed. Required by Pica2ModsXSLTURIResolver.
            pica2ModsConfig.setUnapiUrl("https://unapi.k10plus.de/");
            // Use a placeholder or make configurable if needed. Required by some XSLTs.
            pica2ModsConfig.setMycoreUrl("http://localhost:8080/");
            pica2ModsConfig.setCatalogs(new java.util.HashMap<>()); // Initialize catalogs map

            Pica2ModsManager pica2ModsManager = new Pica2ModsManager(pica2ModsConfig);

            TransformerFactory transformerFactory = TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null);
            transformerFactory.setURIResolver(new Pica2ModsXSLTURIResolver(pica2ModsManager));
            transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);

            String xsltPath = "/xsl/" + stylesheet;
            InputStream xsltStream = ToolsShell.class.getResourceAsStream(xsltPath);
            if (xsltStream == null) {
                throw new TransformerConfigurationException("XSLT file not found on classpath: " + xsltPath);
            }
            Source xslSource = new StreamSource(xsltStream, xsltPath); // Set System ID

            Transformer transformer = transformerFactory.newTransformer(xslSource);
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            // Set parameters required by standard pica2mods stylesheets
            transformer.setParameter("WebApplicationBaseURL", pica2ModsConfig.getMycoreUrl());
            // Add other parameters if needed by your specific stylesheet, e.g., RestrictedAccess

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
                    // Normalize ISBN and create prefixed key
                    String normalizedIsbn = isbn.replaceAll("[^0-9]", "");
                    String isbnKey = "isbn:" + normalizedIsbn;

                    // Check if ISBN is already mapped using the prefixed key
                    if (idMapper.containsKey(isbnKey)) {
                        log.info("ISBN {} (Key: {}) already mapped to {}. Skipping.", isbn, isbnKey, idMapper.getProperty(isbnKey));
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

                    // Select the best record (prioritizing 002@ $0 starting with 'Aa')
                    Element selectedRecord = selectBestPicaRecord(picaRecords, isbn, "Aa"); // Use unified selection
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
                    String ppnKey = "ppn:" + ppn;

                    // Determine MyCoRe ID
                    String mycoreId;
                    if (idMapper.containsKey(ppnKey)) {
                        mycoreId = idMapper.getProperty(ppnKey);
                        log.info("PPN {} (Key: {}) already mapped to {}. Using existing ID.", ppn, ppnKey, mycoreId);
                        // Add mapping for the current ISBN as well, using its prefixed key
                        if (!idMapper.containsKey(isbnKey)) {
                            idMapper.setProperty(isbnKey, mycoreId);
                            mapperChanged = true;
                            log.info("Added mapping for ISBN {} (Key: {}) -> {}", isbn, isbnKey, mycoreId);
                        }
                    } else {
                        mycoreId = idGenerator.generateNextId();
                        idMapper.setProperty(ppnKey, mycoreId); // Map PPN with prefix
                        idMapper.setProperty(isbnKey, mycoreId); // Map ISBN with prefix
                        mapperChanged = true;
                        newIdsGenerated++;
                        log.info("Generated new MyCoRe ID {} for PPN {} (Key: {}) / ISBN {} (Key: {})", mycoreId, ppn, ppnKey, isbn, isbnKey);
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


    @ShellMethod(key = "convert-issn-list", value = "Fetches PICA records for a list of ISSNs via SRU and converts them to MyCoRe objects.")
    public void convertIssnList(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input file containing ISSNs (one per line).") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the directory for outputting MyCoRe objects.") String output,
            @ShellOption(value = {"--id-mapper"}, help = "Path to the file containing ISSN/PPN to MyCoRe ID mappings (Properties format). Will be created/updated.") String idMapperPathStr,
            @ShellOption(value = {"--id-base"}, help = "Template for generating new MyCoRe object IDs (e.g., reposis_mods_00000000).") String idBase,
            @ShellOption(value = {"-s", "--stylesheet"}, help = "Path to the XSLT stylesheet in the classpath for transformation (e.g., pica2mods_artus.xsl).") String stylesheet
    ) {
        Path inputPath = Paths.get(input);
        Path outputDirPath = Paths.get(output);
        Path idMapperPath = Paths.get(idMapperPathStr);

        log.info("Starting ISSN list to MyCoRe conversion...");
        log.info("Input ISSN list: {}", inputPath);
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

            // 4. Prepare pica2mods components and XSLT Transformer
            Pica2ModsConfig pica2ModsConfig = new Pica2ModsConfig();
            // Use a placeholder or make configurable if needed. Required by Pica2ModsXSLTURIResolver.
            pica2ModsConfig.setUnapiUrl("https://unapi.k10plus.de/");
            // Use a placeholder or make configurable if needed. Required by some XSLTs.
            pica2ModsConfig.setMycoreUrl("http://localhost:8080/");
            pica2ModsConfig.setCatalogs(new java.util.HashMap<>()); // Initialize catalogs map

            Pica2ModsManager pica2ModsManager = new Pica2ModsManager(pica2ModsConfig);

            TransformerFactory transformerFactory = TransformerFactory.newInstance("net.sf.saxon.TransformerFactoryImpl", null);
            transformerFactory.setURIResolver(new Pica2ModsXSLTURIResolver(pica2ModsManager));
            transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);

            String xsltPath = "/xsl/" + stylesheet;
            InputStream xsltStream = ToolsShell.class.getResourceAsStream(xsltPath);
            if (xsltStream == null) {
                throw new TransformerConfigurationException("XSLT file not found on classpath: " + xsltPath);
            }
            Source xslSource = new StreamSource(xsltStream, xsltPath); // Set System ID

            Transformer transformer = transformerFactory.newTransformer(xslSource);
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            // Set parameters required by standard pica2mods stylesheets
            transformer.setParameter("WebApplicationBaseURL", pica2ModsConfig.getMycoreUrl());
            // Add other parameters if needed by your specific stylesheet, e.g., RestrictedAccess

            XMLOutputter xmlRecordOutputter = new XMLOutputter(Format.getRawFormat()); // For converting record Element to String for XSLT input
            XMLOutputter finalOutputter = new XMLOutputter(Format.getPrettyFormat()); // For final file output

            // 5. Read ISSNs and process
            List<String> issns = Files.readAllLines(inputPath, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")) // Ignore empty lines and comments
                    .collect(Collectors.toList());

            log.info("Found {} ISSNs to process.", issns.size());
            int processedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;
            int newIdsGenerated = 0;

            for (String issn : issns) {
                processedCount++;
                log.info("Processing ISSN {}/{}: {}", processedCount, issns.size(), issn);

                try {
                    // Normalize ISSN and create prefixed key
                    String normalizedIssn = issn.replaceAll("[^0-9]", "");
                    String issnKey = "issn:" + normalizedIssn;

                    // Check if ISSN is already mapped using the prefixed key
                    if (idMapper.containsKey(issnKey)) {
                        log.info("ISSN {} (Key: {}) already mapped to {}. Skipping.", issn, issnKey, idMapper.getProperty(issnKey));
                        skippedCount++;
                        continue;
                    }

                    // Fetch PICA records via SRU
                    List<Element> picaRecords = sruService.resolvePicaByISSN(issn); // Changed from resolvePicaByISBN
                    if (picaRecords.isEmpty()) {
                        log.warn("No PICA records found for ISSN {}. Skipping.", issn);
                        skippedCount++;
                        continue;
                    }

                    // Select the best record (prioritizing 002@ $0 starting with 'Abv')
                    Element selectedRecord = selectBestPicaRecord(picaRecords, issn, "Abv"); // Use unified selection
                    if (selectedRecord == null) {
                        log.warn("Could not select a suitable PICA record for ISSN {}. Skipping.", issn);
                        skippedCount++;
                        continue; // Should not happen if picaRecords is not empty, but safety check
                    }

                    // Extract PPN
                    String ppn = PicaUtils.extractPpnFromRecord(selectedRecord);
                    if (ppn == null) {
                        log.warn("Could not extract PPN from selected record for ISSN {}. Skipping.", issn);
                        XMLOutputter debugOutputter = new XMLOutputter(Format.getCompactFormat());
                        log.debug("Record without PPN: {}", debugOutputter.outputString(selectedRecord));
                        skippedCount++;
                        continue;
                    }
                    log.debug("Extracted PPN {} for ISSN {}", ppn, issn);
                    String ppnKey = "ppn:" + ppn;

                    // Determine MyCoRe ID
                    String mycoreId;
                    if (idMapper.containsKey(ppnKey)) {
                        mycoreId = idMapper.getProperty(ppnKey);
                        log.info("PPN {} (Key: {}) already mapped to {}. Using existing ID.", ppn, ppnKey, mycoreId);
                        // Add mapping for the current ISSN as well, using its prefixed key
                        if (!idMapper.containsKey(issnKey)) {
                            idMapper.setProperty(issnKey, mycoreId);
                            mapperChanged = true;
                            log.info("Added mapping for ISSN {} (Key: {}) -> {}", issn, issnKey, mycoreId);
                        }
                    } else {
                        mycoreId = idGenerator.generateNextId();
                        idMapper.setProperty(ppnKey, mycoreId); // Map PPN with prefix
                        idMapper.setProperty(issnKey, mycoreId); // Map ISSN with prefix
                        mapperChanged = true;
                        newIdsGenerated++;
                        log.info("Generated new MyCoRe ID {} for PPN {} (Key: {}) / ISSN {} (Key: {})", mycoreId, ppn, ppnKey, issn, issnKey);
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
                    log.error("Error processing ISSN {}: {}", issn, e.getMessage(), e);
                    errorCount++;
                }
            } // End ISSN loop

            // 6. Save ID Mapper if changed
            if (mapperChanged) {
                saveIdMapper(idMapper, idMapperPath);
            }

            log.info("ISSN list processing finished.");
            log.info("Summary: {} total ISSNs, {} skipped, {} errors.", issns.size(), skippedCount, errorCount);
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
     * Selects the best PICA record from a list based on a priority prefix in field 002@ $0.
     * If multiple records match the prefix, the first matching record is returned.
     * If no records match, the first record in the list is returned.
     * Returns the selected Element reference directly (no clone).
     *
     * @param records         The list of PICA record elements.
     * @param identifier      The identifier (ISBN/ISSN) being processed, for logging purposes.
     * @param priorityPrefix The prefix ("Aa" or "Abv") to prioritize in 002@ $0.
     * @return The selected Element, or null if the input list is null or empty.
     */
    private Element selectBestPicaRecord(List<Element> records, String identifier, String priorityPrefix) {
        if (records == null || records.isEmpty()) {
            return null;
        }

        for (Element record : records) {
            if (checkPicaField002AStartsWith(record, priorityPrefix)) {
                log.debug("Found prioritized record (002@ $0 starts with '{}') for identifier {}", priorityPrefix, identifier);
                return record; // Return the first matching record directly
            }
        }

        // No record matched the priority rule, return the first record found
        log.debug("No record matched priority rule (002@ $0 starts with '{}') for identifier {}. Selecting first record.", priorityPrefix, identifier);
        return records.getFirst(); // Return the first record directly
    }

    /**
     * Checks if the PICA record has a field 002@ with subfield $0 starting with the given prefix.
     *
     * @param record The PICA record element.
     * @param prefix The prefix to check for (e.g., "Aa", "Abv").
     * @return true if the subfield exists and starts with the prefix, false otherwise.
     */
    private boolean checkPicaField002AStartsWith(Element record, String prefix) {
        if (record == null || prefix == null) {
            return false;
        }
        Element subfield0 = PICA_002A_SUBFIELD_0_XPATH.evaluateFirst(record);
        if (subfield0 != null) {
            String value = subfield0.getTextTrim();
            return value != null && value.startsWith(prefix);
        }
        return false;
    }

    // Removed setupTransformer and loadXsltFromClasspath methods as they are no longer used here.
    // The logic is now integrated into convertIsbnList and convertIssnList using Pica2ModsXSLTURIResolver.

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
            idMapper.store(os, "Identifier (isbn:..., issn:..., ppn:...) to MyCoRe ID Mapping");
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

            // Initialize counter: Start at the maximum of the base number or the highest existing number.
            // This ensures that if base is '...000' and no higher ID exists, the counter starts at 0,
            // and the first generated ID (using incrementAndGet) will be '...001'.
            this.counter = new AtomicLong(Math.max(initialBaseNumber, maxExistingNumber));
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
