package de.vzg.reposis.tools.pica;

import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.Filters;
import org.jdom2.xpath.XPathExpression;
import org.jdom2.xpath.XPathFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for PICA related operations.
 */
public final class PicaUtils {

    private static final Logger log = LoggerFactory.getLogger(PicaUtils.class);

    private static final Namespace PICA_XML_NS = Namespace.getNamespace("pica", "info:srw/schema/5/picaXML-v1.0");
    private static final String PPN_TAG = "003@";
    private static final String PPN_CODE = "0";

    // XPath expression for JDOM
    private static final XPathFactory XPATH_FACTORY = XPathFactory.instance();
    private static final XPathExpression<Element> PPN_XPATH = XPATH_FACTORY.compile(
            "pica:datafield[@tag='" + PPN_TAG + "']/pica:subfield[@code='" + PPN_CODE + "']", Filters.element(), null, PICA_XML_NS);

    /**
     * Private constructor to prevent instantiation.
     */
    private PicaUtils() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Extracts the PPN (003@ $0) from a JDOM PICA record element.
     *
     * @param recordElement The JDOM element for the <record>.
     * @return The PPN string, or null if not found or empty.
     */
    public static String extractPpnFromRecord(Element recordElement) {
        if (recordElement == null) {
            log.warn("Cannot extract PPN from null record element.");
            return null;
        }
        Element ppnElement = PPN_XPATH.evaluateFirst(recordElement);
        if (ppnElement != null) {
            String rawPpn = ppnElement.getTextTrim();
            // Return the raw PPN directly without normalization/check digit removal
            if (rawPpn != null && !rawPpn.isEmpty()) {
                log.trace("Extracted PPN: {}", rawPpn);
                return rawPpn;
            } else {
                log.trace("PPN element found but text is null or empty.");
            }
        } else {
            log.trace("PPN element (Tag: {}, Code: {}) not found in record.", PPN_TAG, PPN_CODE);
        }
        return null; // Return null if element not found or text is empty
    }
}
