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

import org.jdom2.Document;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
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
        // Set the custom URI resolver, passing the input PICA XML path for PPN lookups
        transformerFactory.setURIResolver(new ClasspathUriResolver(inputPath));

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
            while (reader.hasNext()) {
                // Find the next record start element, skipping whitespace/comments etc.
                if (!findNextStartElement(reader, RECORD_ELEMENT)) {
                    log.debug("No more record elements found or end of document reached.");
                    break; // Exit loop if no more records or end of document
                }
                // Now we are positioned at the start of a record element
                recordCount++;

                // We need to consume the source to extract PPN *and* use it for transformation.
                // Reading the record into a temporary buffer (StringWriter) allows both.
                StringWriter recordXmlWriter = new StringWriter();
                XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
                // Use XMLStreamWriter for better namespace control
                XMLStreamWriter streamWriter = outputFactory.createXMLStreamWriter(recordXmlWriter);

                // Extract PPN and write events directly from the main reader to the StringWriter buffer
                // using the XMLStreamWriter. This method consumes events for the current record.
                String ppn = extractPpnAndWriteEvents(reader, streamWriter); // Pass streamWriter
                streamWriter.close(); // Close writer to finalize the string buffer

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
                    XMLOutputter xmlOutputter = new XMLOutputter(Format.getPrettyFormat());
                    xmlOutputter.output(mycoreDocument, fileWriter);
                }

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

    /**
     * Advances the reader until the next start element with the specified QName is found,
     * skipping intermediate events like whitespace or comments.
     *
     * @param reader The reader to advance.
     * @param elementName The QName of the start element to find.
     * @return true if the start element was found, false otherwise (e.g., end of document).
     * @throws XMLStreamException If an error occurs during reading.
     */
    private boolean findNextStartElement(XMLEventReader reader, QName elementName) throws XMLStreamException {
        while (reader.hasNext()) {
            XMLEvent event = reader.peek();
            if (event.isStartElement() && event.asStartElement().getName().equals(elementName)) {
                return true;
            }
            // Consume the event if it's not the one we're looking for
            reader.nextEvent();
            // Check for end of document explicitly inside loop if needed, though hasNext() should cover it.
            if (!reader.hasNext()) return false;
        }
        return false;
    }

    /**
     * Reads events for a single record from the XMLEventReader, writes them to the XMLStreamWriter
     * (preserving namespaces), and extracts the PPN value (field 003@, subfield 0).
     * Assumes the reader is positioned at the start of the record element.
     * Consumes events until the end of the record element is reached.
     *
     * @param reader The event reader, positioned at the start of a record.
     * @param writer The stream writer to output the record's XML fragment.
     * @return The extracted PPN value, or null if not found.
     * @throws XMLStreamException If an XML processing error occurs.
     */
    private String extractPpnAndWriteEvents(XMLEventReader reader, XMLStreamWriter writer) throws XMLStreamException {
        String ppn = null;
        int depth = 0;
        boolean inRecord = false;
        boolean inPpnDatafield = false;
        boolean inPpnSubfield = false;

        while (reader.hasNext()) {
            XMLEvent event = reader.peek(); // Peek to check without consuming yet

            // Stop if we peek past the end of the record element (depth becomes negative or element mismatch)
            if (inRecord && depth <= 0 && event.isEndElement() && !event.asEndElement().getName().equals(RECORD_ELEMENT)) {
                 log.warn("Unexpected state: Peeked end element {} while expecting end of record.", event.asEndElement().getName());
                 break; // Avoid consuming past the record boundary
            }
            if (inRecord && depth == 0 && !event.isEndElement()) {
                 log.warn("Unexpected state: Peeked event {} after record start but before record end.", event);
                 break; // Should be the end element here
            }


            // Now consume the event
            event = reader.nextEvent();

            // Write event to XMLStreamWriter
            switch (event.getEventType()) {
                case XMLEvent.START_ELEMENT:
                    depth++;
                    StartElement startElement = event.asStartElement();
                    QName name = startElement.getName();

                    // Write Start Element with Namespace Handling
                    String prefix = name.getPrefix();
                    String nsURI = name.getNamespaceURI();
                    String localPart = name.getLocalPart();

                    if (prefix != null && !prefix.isEmpty()) {
                        writer.writeStartElement(prefix, localPart, nsURI);
                    } else if (nsURI != null && !nsURI.isEmpty()) {
                        // Handle default namespace
                        writer.setDefaultNamespace(nsURI); // Ensure default is set for this scope
                        writer.writeStartElement(localPart); // Write element in default NS
                        // Explicitly declare default NS only on the root element of the fragment?
                        if (depth == 1 && name.equals(RECORD_ELEMENT)) {
                             writer.writeDefaultNamespace(nsURI);
                             inRecord = true; // Mark that we are inside the record
                        }
                    } else {
                        // No prefix, no namespace URI
                        writer.writeStartElement(localPart);
                    }


                    // Write Namespace Declarations defined on this element
                    java.util.Iterator nsIter = startElement.getNamespaces();
                    while (nsIter.hasNext()) {
                        javax.xml.stream.events.Namespace ns = (javax.xml.stream.events.Namespace) nsIter.next();
                        if (ns.isDefaultNamespaceDeclaration()) {
                            // Already handled above for the element itself if needed
                            // Re-declaring might be redundant/handled by writer, but can be explicit:
                             if (depth > 1 || !name.equals(RECORD_ELEMENT)) // Avoid double declaration on root
                                writer.writeDefaultNamespace(ns.getNamespaceURI());
                        } else {
                            writer.writeNamespace(ns.getPrefix(), ns.getNamespaceURI());
                        }
                    }

                    // Write Attributes
                    java.util.Iterator attrIter = startElement.getAttributes();
                    while (attrIter.hasNext()) {
                        Attribute attr = (Attribute) attrIter.next();
                        QName attrName = attr.getName();
                        String attrPrefix = attrName.getPrefix();
                        String attrNsURI = attrName.getNamespaceURI();
                        String attrLocalPart = attrName.getLocalPart();
                        String attrValue = attr.getValue();

                        if (attrPrefix != null && !attrPrefix.isEmpty()) {
                            writer.writeAttribute(attrPrefix, attrNsURI, attrLocalPart, attrValue);
                        } else if (attrNsURI != null && !attrNsURI.isEmpty()) {
                            // Attribute has namespace but no prefix (shouldn't happen for valid XML?)
                            writer.writeAttribute(attrNsURI, attrLocalPart, attrValue);
                        } else {
                            // No prefix, no namespace URI
                            writer.writeAttribute(attrLocalPart, attrValue);
                        }
                    }

                    // PPN Logic - Check element *after* writing it
                    if (depth == 2 && name.equals(DATAFIELD_ELEMENT)) {
                        Attribute tagAttr = startElement.getAttributeByName(TAG_ATTRIBUTE);
                        if (tagAttr != null && PPN_TAG.equals(tagAttr.getValue())) {
                            inPpnDatafield = true;
                        }
                    } else if (depth == 3 && inPpnDatafield && name.equals(SUBFIELD_ELEMENT)) {
                        Attribute codeAttr = startElement.getAttributeByName(CODE_ATTRIBUTE);
                        if (codeAttr != null && PPN_CODE.equals(codeAttr.getValue())) {
                            inPpnSubfield = true;
                        }
                    }
                    break;

                case XMLEvent.END_ELEMENT:
                     writer.writeEndElement();
                     QName endName = event.asEndElement().getName();

                     // PPN Logic - Update state *after* writing end element
                     if (depth == 3 && inPpnSubfield && endName.equals(SUBFIELD_ELEMENT)) {
                         inPpnSubfield = false; // Reset here
                     } else if (depth == 2 && inPpnDatafield && endName.equals(DATAFIELD_ELEMENT)) {
                         inPpnDatafield = false;
                     }
                     depth--;

                     // Check if this is the end of the record element
                     if (depth == 0 && endName.equals(RECORD_ELEMENT)) {
                         writer.flush(); // Flush before returning
                         return ppn; // Exit method successfully
                     }
                     break;

                case XMLEvent.CHARACTERS:
                    String text = event.asCharacters().getData();
                    writer.writeCharacters(text);
                    // PPN Logic - Capture PPN *after* writing characters
                    if (inPpnSubfield) {
                        // Append in case PPN is split across multiple character events (unlikely but possible)
                        ppn = (ppn == null) ? text : ppn + text;
                    }
                    break;

                case XMLEvent.COMMENT:
                    break;

                case XMLEvent.CDATA:
                    writer.writeCData(event.asCharacters().getData());
                     if (inPpnSubfield) { // Also capture PPN from CDATA?
                         String cdataText = event.asCharacters().getData();
                         ppn = (ppn == null) ? cdataText : ppn + cdataText;
                     }
                    break;

                // Handle other event types if necessary
                case XMLEvent.START_DOCUMENT: // Ignore in fragment
                case XMLEvent.END_DOCUMENT:   // Ignore in fragment
                case XMLEvent.PROCESSING_INSTRUCTION:
                case XMLEvent.DTD:
                case XMLEvent.ENTITY_REFERENCE:
                case XMLEvent.ATTRIBUTE: // Handled within START_ELEMENT
                case XMLEvent.NAMESPACE: // Handled within START_ELEMENT
                    // Ignore or handle as needed
                    break;

                default:
                    log.trace("Ignoring unhandled event type: {}", event.getEventType());
                    break;
            }
        }

        // Should have exited via END_ELEMENT of record if successful
        log.warn("Record processing finished unexpectedly (end of input reached before record end?). PPN found: {}", ppn);
        writer.flush(); // Final flush
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
