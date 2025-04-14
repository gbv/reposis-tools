package de.vzg.reposis.tools.mycore;

import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
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
    // Updated pattern to capture only digits, ignoring optional check digit [X0-9] at the end
    private static final Pattern PPN_PATTERN = Pattern.compile("gvk:ppn:(\\d+)[X\\d]?$");

    private final Map<String, Element> ppnToRecordMap;
    private final XMLOutputter xmlOutputter; // For converting Element to String

    /**
     * Default constructor for classpath-only resolution.
     */
    public ClasspathUriResolver() {
        this(null); // No map provided
    }

    /**
     * Constructor allowing resolution of PPNs from a pre-parsed map.
     * @param ppnToRecordMap Map of PPN strings to JDOM PICA record Elements. Can be null.
     */
    public ClasspathUriResolver(Map<String, Element> ppnToRecordMap) {
        this.ppnToRecordMap = ppnToRecordMap;
        // Initialize XMLOutputter for converting found elements back to XML source
        this.xmlOutputter = new XMLOutputter(Format.getRawFormat()); // Use raw/compact for source
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
                            if (this.ppnToRecordMap != null) {
                                Element recordElement = this.ppnToRecordMap.get(ppn);
                                if (recordElement != null) {
                                    log.info("Found PICA record for PPN {} in pre-parsed map.", ppn);
                                    // Convert the JDOM Element to an XML string
                                    String recordXml = xmlOutputter.outputString(recordElement);
                                    // Return the found record XML as a StreamSource
                                    return new StreamSource(new StringReader(recordXml), href); // Use href as systemId
                                } else {
                                    log.warn("Could not find PICA record for PPN {} in pre-parsed map.", ppn);
                                    // Returning null seems safer to indicate resource not found.
                                    return null;
                                }
                            } else {
                                log.warn("Received UNAPI request for PPN {}, but no PPN-to-Record map was configured.", ppn);
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
    // Removed findPicaRecordByPpn, findNextStartElement, extractPpnAndWriteCompleteRecord as they are no longer needed
}
