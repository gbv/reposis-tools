package de.vzg.reposis.tools.mycore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.namespace.QName;
import javax.xml.stream.*;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves URIs for XSLT includes/imports against the classpath.
 */
public class ClasspathUriResolver implements URIResolver {

    private static final Logger log = LoggerFactory.getLogger(ClasspathUriResolver.class);
    private static final String XSL_BASE_PATH = "/xsl/"; // Base path for XSL files on classpath
    private static final String RESOURCE_SCHEME = "resource:";
    private static final String UNAPI_HOST = "unapi.k10plus.de";
    private static final Pattern PPN_PATTERN = Pattern.compile("gvk:ppn:(\\d+)");

    // Constants for PICA XML parsing (copied for self-containment)
    private static final String PICA_XML_NS = "info:srw/schema/5/picaXML-v1.0";
    private static final QName RECORD_ELEMENT = new QName(PICA_XML_NS, "record");
    private static final QName DATAFIELD_ELEMENT = new QName(PICA_XML_NS, "datafield");
    private static final QName SUBFIELD_ELEMENT = new QName(PICA_XML_NS, "subfield");
    private static final QName TAG_ATTRIBUTE = new QName("tag");
    private static final QName CODE_ATTRIBUTE = new QName("code");
    private static final String PPN_TAG = "003@";
    private static final String PPN_CODE = "0";

    private final Path inputPicaXmlPath;
    private final XMLInputFactory xmlInputFactory;

    /**
     * Default constructor for classpath-only resolution.
     */
    public ClasspathUriResolver() {
        this(null);
    }

    /**
     * Constructor allowing resolution of PPNs from a specific PICA XML file.
     * @param inputPicaXmlPath Path to the main PICA XML input file. Can be null.
     */
    public ClasspathUriResolver(Path inputPicaXmlPath) {
        this.inputPicaXmlPath = inputPicaXmlPath;
        // Initialize XMLInputFactory once for reuse
        this.xmlInputFactory = XMLInputFactory.newInstance();
        // Configure factory for safety and correctness
        this.xmlInputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        this.xmlInputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        this.xmlInputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    }


    @Override
    public Source resolve(String href, String base) throws TransformerException {
        log.debug("Attempting to resolve URI: href='{}', base='{}'", href, base);

        // 1. Check for UNAPI PPN request
        try {
            if (href != null && href.startsWith("https://" + UNAPI_HOST)) {
                URL url = new URL(href);
                if (UNAPI_HOST.equalsIgnoreCase(url.getHost())) {
                    String query = url.getQuery();
                    String format = null;
                    String id = null;
                    if (query != null) {
                        String[] params = query.split("&");
                        for (String param : params) {
                            String[] pair = param.split("=", 2);
                            if (pair.length == 2) {
                                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                                String value = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                                if ("format".equalsIgnoreCase(key)) {
                                    format = value;
                                } else if ("id".equalsIgnoreCase(key)) {
                                    id = value;
                                }
                            }
                        }
                    }

                    if ("picaxml".equalsIgnoreCase(format) && id != null) {
                        Matcher ppnMatcher = PPN_PATTERN.matcher(id);
                        if (ppnMatcher.find()) {
                            String ppn = ppnMatcher.group(1);
                            log.info("Identified UNAPI request for PPN: {}", ppn);
                            if (this.inputPicaXmlPath != null) {
                                String recordXml = findPicaRecordByPpn(ppn);
                                if (recordXml != null) {
                                    log.info("Found PICA record for PPN {} in file {}", ppn, this.inputPicaXmlPath);
                                    // Return the found record XML as a StreamSource
                                    return new StreamSource(new StringReader(recordXml), href); // Use href as systemId
                                } else {
                                    log.warn("Could not find PICA record for PPN {} in file {}", ppn, this.inputPicaXmlPath);
                                    // Return empty source or throw? Returning null falls back.
                                    // Let's return an empty record to avoid breaking transformation? Or null?
                                    // Returning null seems safer to indicate resource not found.
                                    return null;
                                }
                            } else {
                                log.warn("Received UNAPI request for PPN {}, but no input PICA XML path was configured.", ppn);
                                return null; // Cannot fulfill request
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { // Catch MalformedURLException, IOException, XMLStreamException
            log.error("Error processing potential UNAPI request '{}': {}", href, e.getMessage(), e);
            // Fall through to standard resolution
        }

        // 2. Fallback to Classpath/Resource resolution
        String resolvePath;

        // Check for custom 'resource:' scheme
        if (href != null && href.startsWith(RESOURCE_SCHEME)) {
            String resourcePath = href.substring(RESOURCE_SCHEME.length());
            // Ensure it starts with a slash for absolute classpath lookup
            resolvePath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
            log.debug("Resolved 'resource:' scheme URI '{}' to classpath path '{}'", href, resolvePath);
        } else {
            // Handle standard XSL includes/imports, prepending /xsl/
            String effectiveHref = href;
            // Ensure href doesn't already start with the base path to avoid duplication
            if (effectiveHref.startsWith(XSL_BASE_PATH)) {
                log.trace("href '{}' already starts with XSL base path '{}', using as is.", effectiveHref, XSL_BASE_PATH);
            } else if (effectiveHref.startsWith("/")) {
                // If href is absolute, prepend base path without the leading slash of href
                effectiveHref = XSL_BASE_PATH + effectiveHref.substring(1);
            } else {
                // If href is relative, just prepend the base path
                effectiveHref = XSL_BASE_PATH + effectiveHref;
            }
            log.debug("Effective href after prepending XSL base path: {}", effectiveHref);

            try {
                // If base is present and represents a classpath URI, try resolving relative to it.
                // Otherwise, treat effectiveHref as an absolute path within the classpath.
                if (base != null && !base.isEmpty()) {
                URI baseUri = new URI(base);
                // Simple check if base looks like a classpath resource path
                // It should already contain the XSL_BASE_PATH if resolved by this resolver previously
                if (baseUri.getScheme() == null || "classpath".equalsIgnoreCase(baseUri.getScheme())) {
                    // Resolve effectiveHref relative to the base path
                    // Ensure base path ends with / for correct relative resolution
                    String baseForResolve = baseUri.getPath().endsWith("/") ? baseUri.getPath() : baseUri.getPath() + "/";
                    URI resolved = new URI(null, null, baseForResolve, null).resolve(effectiveHref.startsWith("/") ? effectiveHref.substring(1) : effectiveHref);
                    resolvePath = resolved.getPath();
                    // Ensure the path starts with / for classpath loading
                    if (!resolvePath.startsWith("/")) {
                        resolvePath = "/" + resolvePath;
                    }
                    log.debug("Resolved relative path: {}", resolvePath);
                } else {
                    // Base is not something we can easily resolve against in classpath, treat effectiveHref as absolute
                    resolvePath = effectiveHref.startsWith("/") ? effectiveHref : "/" + effectiveHref;
                    log.debug("Base URI '{}' not classpath-relative, treating effectiveHref as absolute: {}", base, resolvePath);
                }
            } else {
                // No base, treat effectiveHref as absolute path in classpath
                resolvePath = effectiveHref.startsWith("/") ? effectiveHref : "/" + effectiveHref;
                log.debug("No base URI provided, treating effectiveHref as absolute: {}", resolvePath);
            }
        } catch (URISyntaxException e) {
            log.error("Error parsing base URI '{}' or effective href '{}'", base, effectiveHref, e);
            throw new TransformerException("Error resolving URI", e);
        }
        } // End of else block for non-resource scheme handling

        // Attempt to load the resource from the classpath using the final resolved path
        log.debug("Attempting to load resource from classpath: {}", resolvePath);
        InputStream inputStream = getClass().getResourceAsStream(resolvePath);

        if (inputStream != null) {
            log.info("Successfully resolved '{}' from classpath path '{}'", href, resolvePath);
            return new StreamSource(inputStream, resolvePath); // Use resolvePath as systemId
        } else {
            log.warn("Could not resolve '{}' (tried classpath path '{}'). Delegating to default resolver.", href, resolvePath);
            // Returning null delegates to the default JAXP URIResolver mechanism
            // which might resolve file paths, http URLs etc.
            return null;
        }
    }

    /**
     * Searches the configured input PICA XML file for a record matching the given PPN.
     *
     * @param targetPpn The PPN to search for.
     * @return The XML string of the matching record, or null if not found or an error occurs.
     */
    private String findPicaRecordByPpn(String targetPpn) {
        if (this.inputPicaXmlPath == null || !Files.exists(this.inputPicaXmlPath)) {
            log.error("Input PICA XML path is not set or file does not exist: {}", this.inputPicaXmlPath);
            return null;
        }

        XMLEventReader reader = null;
        XMLStreamWriter writer = null;
        StringWriter recordWriter = null;
        boolean foundRecord = false;

        try (InputStream is = new BufferedInputStream(Files.newInputStream(this.inputPicaXmlPath))) {
            reader = this.xmlInputFactory.createXMLEventReader(is, StandardCharsets.UTF_8.name());

            while (reader.hasNext()) {
                // Find the start of the next record
                if (!findNextStartElement(reader, RECORD_ELEMENT)) {
                    break; // No more records
                }

                // Found a record start, now check its PPN and capture XML if it matches
                recordWriter = new StringWriter();
                XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
                writer = outputFactory.createXMLStreamWriter(recordWriter);

                // Need to peek at the start event to pass it to the extraction method
                XMLEvent recordStartEvent = reader.peek();
                if (recordStartEvent == null || !recordStartEvent.isStartElement()) {
                    // Should not happen if findNextStartElement returned true
                    log.warn("Unexpected state after finding record start.");
                    reader.nextEvent(); // Consume the unexpected event
                    continue;
                }

                // Extract PPN and write the *entire current record* to the writer
                String currentPpn = extractPpnAndWriteCompleteRecord(reader, writer, recordStartEvent.asStartElement());

                writer.close(); // Close writer to finalize the string

                if (targetPpn.equals(currentPpn)) {
                    log.debug("PPN match found: {}", targetPpn);
                    foundRecord = true;
                    break; // Exit loop, recordWriter contains the XML
                } else {
                    // PPN doesn't match, discard the captured XML and continue searching
                    log.trace("Record PPN {} does not match target {}", currentPpn, targetPpn);
                    recordWriter = null; // Allow GC
                    writer = null;
                }
                // The reader position is automatically advanced by extractPpnAndWriteCompleteRecord
            }

        } catch (IOException | XMLStreamException e) {
            log.error("Error searching for PPN {} in {}: {}", targetPpn, this.inputPicaXmlPath, e.getMessage(), e);
            return null; // Return null on error
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (XMLStreamException e) {
                    log.warn("Error closing XML reader: {}", e.getMessage());
                }
            }
             // XMLStreamWriter is closed in the loop or if an exception occurs before its creation/closing
        }

        return foundRecord ? recordWriter.toString() : null;
    }


    /**
     * Helper method to find the next start element with a specific name.
     * (Copied from PicaMyCoReConversionService for self-containment)
     */
    private boolean findNextStartElement(XMLEventReader reader, QName elementName) throws XMLStreamException {
        while (reader.hasNext()) {
            XMLEvent event = reader.peek();
            if (event.isStartElement() && event.asStartElement().getName().equals(elementName)) {
                return true;
            }
            reader.nextEvent(); // Consume the event if it's not the one we're looking for
            if (!reader.hasNext()) return false;
        }
        return false;
    }

    /**
     * Reads events for a single record, extracts the PPN, and writes the full record XML.
     * Assumes the reader is positioned *before* the record's START_ELEMENT,
     * and the start element event itself is passed. Consumes events until the record's END_ELEMENT.
     *
     * @param reader The event reader.
     * @param writer The stream writer to output the record's XML.
     * @param recordStartElement The START_ELEMENT event of the record.
     * @return The extracted PPN value, or null if not found.
     * @throws XMLStreamException If an XML processing error occurs.
     */
    private String extractPpnAndWriteCompleteRecord(XMLEventReader reader, XMLStreamWriter writer, StartElement recordStartElement) throws XMLStreamException {
        String ppn = null;
        int depth = 0; // Start at 0, the record start element increases it to 1
        boolean inPpnDatafield = false;
        boolean inPpnSubfield = false;

        // Process the record start element passed to the method
        XMLEvent event = recordStartElement;
        reader.nextEvent(); // Consume the start event from the reader

        while (true) { // Loop processes the start event and subsequent events until record end
            // Write event to XMLStreamWriter
            switch (event.getEventType()) {
                case XMLEvent.START_ELEMENT:
                    depth++;
                    StartElement startElement = event.asStartElement();
                    QName name = startElement.getName();

                    // Write Start Element (simplified namespace handling for fragment)
                    writer.writeStartElement(name.getPrefix(), name.getLocalPart(), name.getNamespaceURI());

                    // Write Namespace Declarations
                    java.util.Iterator nsIter = startElement.getNamespaces();
                    while (nsIter.hasNext()) {
                        javax.xml.stream.events.Namespace ns = (javax.xml.stream.events.Namespace) nsIter.next();
                        writer.writeNamespace(ns.getPrefix(), ns.getNamespaceURI());
                    }
                     // Write default namespace if present on record element
                    if (depth == 1 && name.equals(RECORD_ELEMENT)) {
                        String defaultNs = name.getNamespaceURI(null); // Check if default NS is declared
                        if(defaultNs != null && !defaultNs.isEmpty()){
                             writer.writeDefaultNamespace(defaultNs);
                        }
                    }


                    // Write Attributes
                    java.util.Iterator attrIter = startElement.getAttributes();
                    while (attrIter.hasNext()) {
                        Attribute attr = (Attribute) attrIter.next();
                        QName attrName = attr.getName();
                        writer.writeAttribute(attrName.getPrefix(), attrName.getNamespaceURI(), attrName.getLocalPart(), attr.getValue());
                    }

                    // PPN Logic
                    if (depth == 2 && name.equals(DATAFIELD_ELEMENT)) { // Depth relative to record
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

                     // PPN Logic state update
                     if (depth == 3 && inPpnSubfield && endName.equals(SUBFIELD_ELEMENT)) {
                         inPpnSubfield = false;
                     } else if (depth == 2 && inPpnDatafield && endName.equals(DATAFIELD_ELEMENT)) {
                         inPpnDatafield = false;
                     }
                     depth--;

                     // Check if this is the end of the record element
                     if (depth == 0 && endName.equals(RECORD_ELEMENT)) {
                         writer.flush();
                         return ppn; // Record finished, return found PPN (or null)
                     }
                     break;

                case XMLEvent.CHARACTERS:
                    String text = event.asCharacters().getData();
                    writer.writeCharacters(text);
                    if (inPpnSubfield) {
                        ppn = (ppn == null) ? text.trim() : ppn + text.trim(); // Trim whitespace
                    }
                    break;

                case XMLEvent.CDATA:
                    String cdataText = event.asCharacters().getData();
                    writer.writeCData(cdataText);
                     if (inPpnSubfield) {
                         ppn = (ppn == null) ? cdataText.trim() : ppn + cdataText.trim(); // Trim whitespace
                     }
                    break;

                // Write other significant events like comments, PIs if needed
                case XMLEvent.COMMENT:
                     writer.writeComment(((javax.xml.stream.events.Comment) event).getText());
                     break;
                case XMLEvent.PROCESSING_INSTRUCTION:
                     writer.writeProcessingInstruction(((javax.xml.stream.events.ProcessingInstruction) event).getTarget(), ((javax.xml.stream.events.ProcessingInstruction) event).getData());
                     break;

                // Ignore document start/end, DTD, etc. within the record fragment
                default:
                    break;
            }

            // Break loop if reader ends unexpectedly (shouldn't happen if depth tracking is correct)
            if (!reader.hasNext()) {
                 log.warn("End of input reached unexpectedly while processing record for PPN {}", ppn);
                 break;
            }
            // Consume next event for the next iteration
            event = reader.nextEvent();
        }
        // Should only reach here if the loop terminated unexpectedly
        log.error("Record processing ended unexpectedly before finding the record's end element.");
        writer.flush();
        return ppn; // Return whatever PPN was found, even if record is incomplete
    }
}
