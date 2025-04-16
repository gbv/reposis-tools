package de.vzg.reposis.tools.mycore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.jdom2.Attribute;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PicaMyCoReConversionService {

    private static final Logger log = LoggerFactory.getLogger(PicaMyCoReConversionService.class);
    private final MyCoReObjectService myCoReObjectService; // Inject the new service

    private static final Namespace PICA_XML_NS = Namespace.getNamespace("pica", "info:srw/schema/5/picaXML-v1.0");
    private static final String PPN_TAG = "003@";
    private static final String PPN_CODE = "0";
    private static final String XSLT_PARAM_OBJECT_ID = "ObjectID"; // Assumed parameter name
    private static final Namespace TEMP_NS = Namespace.getNamespace("temp", "urn:temp-linking");
    private static final Namespace XLINK_NS = Namespace.getNamespace("xlink", "http://www.w3.org/1999/xlink");
    private static final Namespace MODS_NS = Namespace.getNamespace("mods", "http://www.loc.gov/mods/v3");

    private static final int LOG_INTERVAL = 1000; // Log progress every 1000 records

    // XPath expressions for JDOM
    private static final XPathFactory XPATH_FACTORY = XPathFactory.instance();
    private static final XPathExpression<Element> PICA_RECORDS_XPATH = XPATH_FACTORY.compile(
            "//pica:record", Filters.element(), null, PICA_XML_NS);
    private static final XPathExpression<Element> PPN_XPATH = XPATH_FACTORY.compile(
            "pica:datafield[@tag='" + PPN_TAG + "']/pica:subfield[@code='" + PPN_CODE + "']", Filters.element(), null, PICA_XML_NS);
    // XPath to find relatedItems needing linking in Pass 2
    private static final XPathExpression<Element> RELATED_ITEM_LINK_XPATH = XPATH_FACTORY.compile(
            "//mods:mods/mods:relatedItem[@temp:relatedPPN or @temp:relatedISBN or @temp:relatedISSN]", Filters.element(), null, MODS_NS, TEMP_NS);
    // XPath to find the mods:mods element within a MyCoRe object document
    private static final XPathExpression<Element> MYCORE_MODS_XPATH = XPATH_FACTORY.compile(
            "/mycoreobject/metadata/def.modsContainer/modsContainer/mods:mods", Filters.element(), null, MODS_NS);

    @Autowired
    public PicaMyCoReConversionService(MyCoReObjectService myCoReObjectService) {
        this.myCoReObjectService = myCoReObjectService;
    }

    public void convertPicaXmlToMyCoRe(Path inputPath, Path outputDir, Path idMapperPath, String idBase,
        String stylesheet) throws IOException, TransformerException, JDOMException {
        log.info("Starting PICA XML to MyCoRe conversion (Two-Pass)...");
        log.info("Input PICA XML: {}", inputPath);
        log.info("Output Directory: {}", outputDir);
        log.info("ID Mapper File: {}", idMapperPath);
        log.info("MyCoRe ID Base: {}", idBase);
        log.info("XSLT Stylesheet: {}", stylesheet);

        // 1. Load or initialize ID Mapper
        Properties idMapper = loadIdMapper(idMapperPath);
        boolean mapperChanged = false;

        // 2. Prepare ID Generation
        IdGenerator idGenerator = new IdGenerator(idBase, idMapper);

        // 3. Ensure output directory exists
        Files.createDirectories(outputDir);

        // 4. Parse the entire input PICA XML into a JDOM Document
        log.info("Parsing input PICA XML file: {}", inputPath);
        SAXBuilder saxBuilder = new SAXBuilder();
        // Configure SAXBuilder for security if needed (e.g., disable external entities)
        // saxBuilder.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // saxBuilder.setFeature("http://xml.org/sax/features/external-general-entities", false);
        // saxBuilder.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document picaDocument;
        try (InputStream is = new BufferedInputStream(Files.newInputStream(inputPath))) {
            picaDocument = saxBuilder.build(is);
        }
        log.info("Successfully parsed input PICA XML.");

        // 5. Extract records and build PPN -> Record Element Map
        Map<String, Element> ppnToRecordMap = new HashMap<>();
        List<Element> records = PICA_RECORDS_XPATH.evaluate(picaDocument);
        log.info("Found {} PICA records in the input file.", records.size());
        for (Element record : records) {
            String ppn = extractPpnFromRecord(record);
            if (ppn != null) {
                if (ppnToRecordMap.containsKey(ppn)) {
                    log.warn("Duplicate PPN found: {}. Keeping the first occurrence.", ppn);
                } else {
                    // Clone the element to store it safely detached from the original document
                    ppnToRecordMap.put(ppn, record.clone());
                    log.trace("Stored record for PPN: {}", ppn);
                }
            } else {
                log.warn("Record found without a valid PPN (Tag: {}, Code: {}). Skipping.", PPN_TAG, PPN_CODE);
                // Log the skipped record XML for debugging
                 XMLOutputter skippedRecordOutputter = new XMLOutputter(Format.getCompactFormat());
                 log.debug("Skipped record XML: {}", skippedRecordOutputter.outputString(record));
            }
        }
        log.info("Created map with {} unique PPNs.", ppnToRecordMap.size());


        // 6. Prepare XSLT Transformer
        // Explicitly request the Saxon-HE TransformerFactory implementation
        String saxonFactoryClass = "net.sf.saxon.TransformerFactoryImpl";
        TransformerFactory transformerFactory;
        try {
            // Try to get the Saxon-HE factory
            transformerFactory = TransformerFactory.newInstance(saxonFactoryClass, null);
            log.info("Using Saxon-HE TransformerFactory: {}", saxonFactoryClass);
        } catch (TransformerFactoryConfigurationError e) {
            log.warn("Could not instantiate specific Saxon-HE TransformerFactory ('{}'). Falling back to default JAXP lookup. Error: {}", saxonFactoryClass, e.getMessage());
            // Fallback to default mechanism if Saxon is not found
            transformerFactory = TransformerFactory.newInstance();
        }

        // Secure processing
        try {
            transformerFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (TransformerConfigurationException e) {
            log.warn("Could not set secure processing feature for TransformerFactory: {}", e.getMessage());
        }
        // Set the custom URI resolver, passing the PPN-to-Record map
        transformerFactory.setURIResolver(new ClasspathUriResolver(ppnToRecordMap));

        // Load XSLT from classpath
        Source xsltSource = loadXsltFromClasspath("/xsl/" + stylesheet);
        Transformer transformer = transformerFactory.newTransformer(xsltSource);
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getRawFormat()); // For converting record Element to String for XSLT input

        // 7. Pass 1: Generate initial MyCoRe objects (in memory)
        log.info("Starting Pass 1: Generating initial MyCoRe objects...");
        Map<String, Document> generatedObjects = new HashMap<>(); // Store generated objects by MyCoRe ID
        int recordCount = 0;
        int newIdsGenerated = 0;

        for (Map.Entry<String, Element> entry : ppnToRecordMap.entrySet()) {
            recordCount++;
            String ppn = entry.getKey();
            Element recordElement = entry.getValue(); // This is the cloned PICA record element

            // Log progress periodically
            if (recordCount % LOG_INTERVAL == 0) {
                log.info("Pass 1: Processed {} records...", recordCount);
            }

            // Get or generate MyCoRe ID
            String mycoreId;
            if (idMapper.containsKey(ppn)) {
                mycoreId = idMapper.getProperty(ppn);
                log.debug("Record #{}: Found existing mapping PPN {} -> MyCoRe ID {}", recordCount, ppn, mycoreId);
            } else {
                mycoreId = idGenerator.generateNextId();
                idMapper.setProperty(ppn, mycoreId);
                mapperChanged = true;
                newIdsGenerated++;
                log.info("Record #{}: Generated new mapping PPN {} -> MyCoRe ID {}", recordCount, ppn, mycoreId);
            }

            // Perform XSLT Transformation
            log.debug("Pass 1: Transforming record for PPN {} (MyCoRe ID {})", ppn, mycoreId);
            transformer.setParameter(XSLT_PARAM_OBJECT_ID, mycoreId);

            // Convert the JDOM Element to an XML String Source
            String recordXmlString = xmlOutputter.outputString(recordElement);
            Source xmlSource = new StreamSource(new StringReader(recordXmlString));

            // Capture XSLT result to a StringWriter
            StringWriter modsOutputWriter = new StringWriter();
                Result modsResult = new StreamResult(modsOutputWriter);
                transformer.transform(xmlSource, modsResult);
                String modsXml = modsOutputWriter.toString();

                // Wrap the MODS XML in a MyCoRe object frame using the injected service
                // Using "published" as the default status, this could be made configurable
                Document mycoreDocument = myCoReObjectService.wrapInMyCoReFrame(modsXml, mycoreId, "published");

                // Store the generated document in the map instead of writing to file
                generatedObjects.put(mycoreId, mycoreDocument);
                log.trace("Pass 1: Stored initial object for {}", mycoreId);

                // Transformer parameter should be cleared/reset if the instance is reused heavily,
                // but creating a new one per record or clearing is safer.
                transformer.clearParameters();

        } // End of Pass 1 loop
        log.info("Pass 1 finished. Generated {} initial objects.", generatedObjects.size());

        // 8. Pass 2: Link related items and write final files
        log.info("Starting Pass 2: Linking related items and writing final files...");
        int linkedItemsCount = 0;
        XMLOutputter finalOutputter = new XMLOutputter(Format.getPrettyFormat()); // For final file output

        for (Map.Entry<String, Document> objEntry : generatedObjects.entrySet()) {
            String currentMyCoReId = objEntry.getKey();
            Document currentDocument = objEntry.getValue();
            boolean objectModifiedInPass2 = false;

            // Find relatedItems with the temporary PPN attribute
            List<Element> itemsToLink = RELATED_ITEM_LINK_XPATH.evaluate(currentDocument);

            for (Element relatedItem : itemsToLink) {
                String matchingAttribute = Stream.of("relatedPPN", "relatedISBN", "relatedISSN")
                        .filter(attr -> relatedItem.getAttribute(attr, TEMP_NS) != null)
                        .findFirst()
                        .orElse(null);

                if (matchingAttribute == null) continue;
                // Get the temporary attribute (e.g., relatedPPN)
                Attribute tempAttr = relatedItem.getAttribute(matchingAttribute, TEMP_NS);
                relatedItem.removeNamespaceDeclaration(TEMP_NS);
                String related = tempAttr.getValue();
                relatedItem.removeAttribute(tempAttr); // Remove temporary attribute


                // Find the MyCoRe ID for the related PPN
                String relatedMyCoReId = idMapper.getProperty(related);

                if (relatedMyCoReId != null) {
                    // Add xlink:href
                    relatedItem.setAttribute("href", relatedMyCoReId, XLINK_NS);
                    log.trace("Pass 2: Added xlink:href='{}' for related PPN {}", relatedMyCoReId, related);

                    // Find the related document in our generated map
                    Document relatedDocument = generatedObjects.get(relatedMyCoReId);
                    if (relatedDocument != null) {
                        // Extract the mods:mods element from the related document
                        Element relatedModsElement = MYCORE_MODS_XPATH.evaluateFirst(relatedDocument);
                        if (relatedModsElement != null) {
                            // Clone and add the related mods:mods to the current relatedItem
                            relatedItem.addContent(relatedModsElement.clone());
                            log.trace("Pass 2: Embedded related mods from {} into {}", relatedMyCoReId, currentMyCoReId);
                            linkedItemsCount++;
                            objectModifiedInPass2 = true;
                        }
                    }

                }
            } // End loop over itemsToLink

            // Write the final document (potentially modified in Pass 2) to the output directory
            Path outputPath = outputDir.resolve(currentMyCoReId + ".xml");
            try (OutputStreamWriter fileWriter = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputPath)), StandardCharsets.UTF_8)) {
                finalOutputter.output(currentDocument, fileWriter);
                if(objectModifiedInPass2) {
                    log.debug("Pass 2: Wrote final linked object to {}", outputPath);
                } else {
                    log.trace("Pass 2: Wrote final object (no links added) to {}", outputPath);
                }
            }
        } // End of Pass 2 loop

        // 9. Save ID Mapper if changed
        if (mapperChanged) {
            saveIdMapper(idMapper, idMapperPath);
        }

        log.info("Pass 2 finished. Linked {} related items.", linkedItemsCount);
        log.info("Conversion finished. Processed {} unique PPN records.", recordCount);
        // Add back the final log message about new IDs if mapper changed
        if (mapperChanged) {
            log.info("Generated {} new MyCoRe IDs and updated mapper file '{}'.", newIdsGenerated, idMapperPath);
        }
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
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(idMapperPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
            idMapper.store(os, "PICA PPN to MyCoRe ID Mapping");
        }
        log.debug("ID mapper saved successfully.");
    }

    private Source loadXsltFromClasspath(String xsltPath) throws TransformerConfigurationException {
        log.debug("Loading XSLT from classpath: {}", xsltPath);
        InputStream xsltStream = PicaMyCoReConversionService.class.getResourceAsStream(xsltPath);
        if (xsltStream == null) {
            log.error("XSLT file not found on classpath: {}", xsltPath);
            throw new TransformerConfigurationException("XSLT file not found on classpath: " + xsltPath);
        }
        return new StreamSource(xsltStream);
    }

    /**
     * Extracts the PPN (003@ $0) from a JDOM PICA record element.
     *
     * @param recordElement The JDOM element for the <record>.
     * @return The PPN string, or null if not found.
     */
    private String extractPpnFromRecord(Element recordElement) {
        Element ppnElement = PPN_XPATH.evaluateFirst(recordElement);
        if (ppnElement != null) {
            String rawPpn = ppnElement.getTextTrim();
            // Return the raw PPN directly without normalization/check digit removal
            if (rawPpn != null && !rawPpn.isEmpty()) {
                return rawPpn;
            }
        }
        return null; // Return null if element not found or text is empty
    }


     /**
      * Helper class to manage MyCoRe ID generation based on a template and existing IDs.
      */
    private static class IdGenerator {
        private final String prefix;
        private final String format;
        private final AtomicLong counter;
        private final Pattern idPattern;

        IdGenerator(String idBase, Properties idMapper) {
            // Regex to extract prefix and number format (e.g., "artus_mods_00000000")
            Matcher matcher = Pattern.compile("^(.*?)(\\d+)$").matcher(idBase);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Invalid idBase format. Expected format like 'prefix_NNN'. Got: " + idBase);
            }
            this.prefix = matcher.group(1);
            String numberPart = matcher.group(2);
            int numberLength = numberPart.length();
            this.format = "%0" + numberLength + "d"; // e.g., "%08d"

            // Pattern to extract number from existing IDs with the same prefix
            this.idPattern = Pattern.compile("^" + Pattern.quote(this.prefix) + "(\\d{" + numberLength + "})$");

            long initialBaseNumber = Long.parseLong(numberPart);
            long maxExistingNumber = findMaxExistingNumber(idMapper);

            this.counter = new AtomicLong(Math.max(initialBaseNumber, maxExistingNumber));
            log.info("ID Generator initialized. Prefix='{}', Format='{}', Next ID number={}", prefix, format, counter.get() + 1);
        }

        private long findMaxExistingNumber(Properties idMapper) {
            return idMapper.stringPropertyNames().stream() // Stream PPNs
                    .map(idMapper::getProperty) // Map to MyCoRe IDs
                    .map(idPattern::matcher) // Match against the ID pattern
                    .filter(Matcher::matches) // Keep only matching IDs
                    .map(m -> m.group(1)) // Extract the number part string
                    .mapToLong(Long::parseLong) // Parse to long
                    .max() // Find the maximum
                    .orElse(-1L); // Default if no matching IDs found
        }

        String generateNextId() {
            long nextNumber = counter.incrementAndGet(); // Increment first, then get
            return prefix + String.format(format, nextNumber);
        }
    }
}
