package de.vzg.reposis.tools.mycore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.stereotype.Service;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PicaMyCoReConversionService {

    private static final Logger log = LoggerFactory.getLogger(PicaMyCoReConversionService.class);
    private static final Namespace PICA_XML_NS = Namespace.getNamespace("pica", "info:srw/schema/5/picaXML-v1.0");
    private static final String PPN_TAG = "003@";
    private static final String PPN_CODE = "0";
    private static final String XSLT_PATH = "/xsl/pica2mods.xsl"; // Default classpath location
    private static final String XSLT_PARAM_OBJECT_ID = "ObjectID"; // Assumed parameter name

    // XPath expressions for JDOM
    private static final XPathFactory XPATH_FACTORY = XPathFactory.instance();
    private static final XPathExpression<Element> PICA_RECORDS_XPATH = XPATH_FACTORY.compile(
            "//pica:record", Filters.element(), null, PICA_XML_NS);
    private static final XPathExpression<Element> PPN_XPATH = XPATH_FACTORY.compile(
            "pica:datafield[@tag='" + PPN_TAG + "']/pica:subfield[@code='" + PPN_CODE + "']", Filters.element(), null, PICA_XML_NS);


    public void convertPicaXmlToMyCoRe(Path inputPath, Path outputDir, Path idMapperPath, String idBase) throws IOException, TransformerException, JDOMException {
        log.info("Starting PICA XML to MyCoRe conversion...");
        log.info("Input PICA XML: {}", inputPath);
        log.info("Output Directory: {}", outputDir);
        log.info("ID Mapper File: {}", idMapperPath);
        log.info("MyCoRe ID Base: {}", idBase);

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
        Source xsltSource = loadXsltFromClasspath(XSLT_PATH);
        Transformer transformer = transformerFactory.newTransformer(xsltSource);
        XMLOutputter xmlOutputter = new XMLOutputter(Format.getRawFormat()); // For converting record Element to String

        // 7. Process Records from the Map
        int recordCount = 0;
        int newIdsGenerated = 0;

        for (Map.Entry<String, Element> entry : ppnToRecordMap.entrySet()) {
            recordCount++;
            String ppn = entry.getKey();
            Element recordElement = entry.getValue();

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

            // Prepare output file path
            Path outputPath = outputDir.resolve(mycoreId + ".xml");

            // Perform XSLT Transformation
            log.debug("Transforming record for PPN {} (MyCoRe ID {}) to {}", ppn, mycoreId, outputPath);
            transformer.setParameter(XSLT_PARAM_OBJECT_ID, mycoreId);

            // Convert the JDOM Element to an XML String Source
            String recordXmlString = xmlOutputter.outputString(recordElement);
            Source xmlSource = new StreamSource(new StringReader(recordXmlString));

            // Capture XSLT result to a StringWriter
            StringWriter modsOutputWriter = new StringWriter();
                Result modsResult = new StreamResult(modsOutputWriter);
                transformer.transform(xmlSource, modsResult);
                String modsXml = modsOutputWriter.toString();

                // Wrap the MODS XML in a MyCoRe object frame
                // Using "published" as the default status, this could be made configurable
                Document mycoreDocument = MODSUtil.wrapInMyCoReFrame(modsXml, mycoreId, "published");

                // Write the complete MyCoRe object to the output file
                try (OutputStreamWriter fileWriter = new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputPath)), StandardCharsets.UTF_8)) {
                    XMLOutputter mycoreXmlOutputter = new XMLOutputter(Format.getPrettyFormat());
                    mycoreXmlOutputter.output(mycoreDocument, fileWriter);
                }

                // Transformer parameter should be cleared/reset if the instance is reused heavily,
                // but creating a new one per record or clearing is safer.
                transformer.clearParameters();

        } // End of loop over map entries

        // 8. Save ID Mapper if changed
        if (mapperChanged) {
            saveIdMapper(idMapper, idMapperPath);
        }

        log.info("Conversion finished. Processed {} unique PPN records.", recordCount);
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
            if (rawPpn != null && !rawPpn.isEmpty()) {
                // Normalize PPN: Remove potential trailing check digit (X or 0-9)
                if (rawPpn.matches(".*[X\\d]$")) {
                    return rawPpn.substring(0, rawPpn.length() - 1);
                }
                return rawPpn; // Return as is if no apparent check digit
            }
        }
        return null;
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
