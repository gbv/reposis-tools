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

    @Override
    public Source resolve(String href, String base) throws TransformerException {
        log.debug("Attempting to resolve URI via classpath: href='{}', base='{}'", href, base);

        String resolvePath;
        try {
            // If base is present and represents a classpath URI, try resolving relative to it.
            // Otherwise, treat href as an absolute path within the classpath.
            if (base != null && !base.isEmpty()) {
                URI baseUri = new URI(base);
                // Simple check if base looks like a classpath resource path
                if (baseUri.getScheme() == null || "classpath".equalsIgnoreCase(baseUri.getScheme())) {
                    // Resolve href relative to the base path (removing any leading '/')
                    URI resolved = baseUri.resolve(href.startsWith("/") ? href.substring(1) : href);
                    resolvePath = resolved.getPath();
                    // Ensure the path starts with / for classpath loading if it's absolute
                    if (!resolvePath.startsWith("/")) {
                        resolvePath = "/" + resolvePath;
                    }
                    log.debug("Resolved relative path: {}", resolvePath);
                } else {
                    // Base is not something we can easily resolve against in classpath, treat href as absolute
                    resolvePath = href.startsWith("/") ? href : "/" + href;
                    log.debug("Base URI '{}' not classpath-relative, treating href as absolute: {}", base, resolvePath);
                }
            } else {
                // No base, treat href as absolute path in classpath
                resolvePath = href.startsWith("/") ? href : "/" + href;
                log.debug("No base URI provided, treating href as absolute: {}", resolvePath);
            }
        } catch (URISyntaxException e) {
            log.error("Error parsing base URI '{}' or href '{}'", base, href, e);
            throw new TransformerException("Error resolving URI", e);
        }

        // Attempt to load the resource from the classpath
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
