package de.vzg.reposis.tools.mycore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.transform.stream.StreamSource;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Resolves URIs for XSLT includes/imports against the classpath.
 */
public class ClasspathUriResolver implements URIResolver {

    private static final Logger log = LoggerFactory.getLogger(ClasspathUriResolver.class);
    private static final String XSL_BASE_PATH = "/xsl/"; // Base path for XSL files on classpath
    private static final String RESOURCE_SCHEME = "resource:";

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        log.debug("Attempting to resolve URI: href='{}', base='{}'", href, base);

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
}
