package de.vzg.reposis.tools.mycore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.xml.namespace.QName;
import javax.xml.stream.*;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.*;
import javax.xml.transform.stax.StAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PicaMyCoReConversionService {

    private static final Logger log = LoggerFactory.getLogger(PicaMyCoReConversionService.class);
    private static final String PICA_XML_NS = "info:srw/schema/5/picaXML-v1.0";
    private static final QName RECORD_ELEMENT = new QName(PICA_XML_NS, "record");
    private static final QName DATAFIELD_ELEMENT = new QName(PICA_XML_NS, "datafield");
    private static final QName SUBFIELD_ELEMENT = new QName(PICA_XML_NS, "subfield");
    private static final QName TAG_ATTRIBUTE = new QName("tag");
    private static final QName CODE_ATTRIBUTE = new QName("code");
    private static final String PPN_TAG = "003@";
    private static final String PPN_CODE = "0";
    private static final String XSLT_PATH = "/xsl/pica2mods.xsl"; // Default classpath location
    private static final String XSLT_PARAM_OBJECT_ID = "ObjectID"; // Assumed parameter name

    public void convertPicaXmlToMyCoRe(Path inputPath, Path outputDir, Path idMapperPath, String idBase) throws IOException, XMLStreamException, TransformerException {
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

        // 4. Prepare XML Input Factory and XSLT Transformer
        XMLInputFactory inputFactory = XMLInputFactory.newInstance();
        // Support XML namespaces
        inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        // Do not resolve external DTDs for security
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);

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
        // Set the custom URI resolver for classpath lookups
        transformerFactory.setURIResolver(new ClasspathUriResolver());

        // Load XSLT from classpath
        Source xsltSource = loadXsltFromClasspath(XSLT_PATH);
        Transformer transformer = transformerFactory.newTransformer(xsltSource);

        // 5. Process Input PICA XML using StAX
        XMLEventReader reader = null;
        int recordCount = 0;
        int newIdsGenerated = 0;

        try (InputStream is = new BufferedInputStream(Files.newInputStream(inputPath))) {
            reader = inputFactory.createXMLEventReader(is, StandardCharsets.UTF_8.name());

            // Find the start of the first record
            while (reader.hasNext() && !isStartElement(reader.peek(), RECORD_ELEMENT)) {
                reader.nextEvent();
            }

            // Process each record
            while (reader.hasNext() && isStartElement(reader.peek(), RECORD_ELEMENT)) {
                recordCount++;

                // We need to consume the source to extract PPN *and* use it for transformation.
                // Reading the record into a temporary buffer (StringWriter) allows both.
                StringWriter recordXmlWriter = new StringWriter();
                // Need an OutputFactory to create the writer
                XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
                XMLEventWriter recordEventWriter = outputFactory.createXMLEventWriter(recordXmlWriter);

                // Extract PPN and write events directly from the main reader to the StringWriter buffer.
                // This method will consume the events for the current record from 'reader'.
                String ppn = extractPpnAndWriteEvents(reader, recordEventWriter);

                if (ppn == null) {
                    log.warn("Record #{} skipped: Could not find PPN ({} ${}). Record XML might be incomplete in buffer.", recordCount, PPN_TAG, PPN_CODE);
                    // 'reader' has already been advanced by extractPpnAndWriteEvents past the problematic record.
                    continue; // Skip this record
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

                // Prepare output file path
                Path outputPath = outputDir.resolve(mycoreId + ".xml");

                // Perform XSLT Transformation
                log.debug("Transforming record for MyCoRe ID {} to {}", mycoreId, outputPath);
                transformer.setParameter(XSLT_PARAM_OBJECT_ID, mycoreId);
                // Use the captured XML string as the source for transformation
                Source xmlSource = new StreamSource(new StringReader(recordXmlWriter.toString()));
                Result outputResult = new StreamResult(new OutputStreamWriter(new BufferedOutputStream(Files.newOutputStream(outputPath)), StandardCharsets.UTF_8));

                transformer.transform(xmlSource, outputResult);

                // Transformer parameter should be cleared/reset if the instance is reused heavily,
                // but creating a new one per record or clearing is safer.
                transformer.clearParameters();

                // Advance reader to the start of the next record or end of document
                // The StAXSource constructor already advanced the main reader 'reader'
                // We just need to ensure we are positioned correctly for the next loop check.
                // If the next event is not a record start, the outer loop condition will handle it.
            }

        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException e) {
                    log.warn("Error closing XML reader: {}", e.getMessage());
                }
            }
        }

        // 6. Save ID Mapper if changed
        if (mapperChanged) {
            saveIdMapper(idMapper, idMapperPath);
        }

        log.info("Conversion finished. Processed {} records.", recordCount);
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

    private boolean isStartElement(XMLEvent event, QName name) {
        return event != null && event.isStartElement() && event.asStartElement().getName().equals(name);
    }

    private String extractPpnAndWriteEvents(XMLEventReader reader, XMLEventWriter writer) throws XMLStreamException {
        String ppn = null;
        int depth = 0; // Track depth within the record element
        boolean inPpnDatafield = false;
        boolean inPpnSubfield = false;

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            writer.add(event); // Write every event to the buffer

            if (event.isStartElement()) {
                depth++;
                StartElement startElement = event.asStartElement();
                // Check if we are entering the PPN datafield (003@) at the correct depth (level 2 inside record)
                if (depth == 2 && isStartElement(event, DATAFIELD_ELEMENT)) {
                    Attribute tagAttr = startElement.getAttributeByName(TAG_ATTRIBUTE);
                    if (tagAttr != null && PPN_TAG.equals(tagAttr.getValue())) {
                        inPpnDatafield = true;
                    }
                }
                // Check if we are entering the PPN subfield ($0) inside the PPN datafield
                else if (depth == 3 && inPpnDatafield && isStartElement(event, SUBFIELD_ELEMENT)) {
                    Attribute codeAttr = startElement.getAttributeByName(CODE_ATTRIBUTE);
                    if (codeAttr != null && PPN_CODE.equals(codeAttr.getValue())) {
                        inPpnSubfield = true;
                    }
                }
            } else if (event.isCharacters() && inPpnSubfield) {
                // Found the PPN value
                ppn = event.asCharacters().getData();
                // Reset flags immediately after capturing PPN to avoid capturing subsequent text nodes
                inPpnSubfield = false;
            } else if (event.isEndElement()) {
                 // Check if we are leaving the PPN subfield
                if (depth == 3 && inPpnSubfield) { // Should have been reset by Characters, but good for safety
                    inPpnSubfield = false;
                }
                 // Check if we are leaving the PPN datafield
                else if (depth == 2 && inPpnDatafield) {
                    inPpnDatafield = false;
                }
                depth--;
                // If we reached the end of the record element, stop processing this record
                if (depth == 0 && event.asEndElement().getName().equals(RECORD_ELEMENT)) {
                    break;
                }
            } else if (event.isEndDocument()) {
                // Should not happen if called per record, but safety break
                break;
            }
        }
        writer.flush(); // Ensure buffer is written
        return ppn;
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
