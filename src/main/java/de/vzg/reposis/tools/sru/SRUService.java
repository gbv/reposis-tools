package de.vzg.reposis.tools.sru;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.input.SAXBuilder;
import org.springframework.stereotype.Service;

@Service
public class SRUService {

    private static final String ZS_NAMESPACE_URL = "http://www.loc.gov/zing/srw/";

    public static final Namespace ZS_NAMESPACE = Namespace.getNamespace(ZS_NAMESPACE_URL);

    private static final String SRU_PICA_BY_ISSN_QUERY =
        "https://sru.k10plus.de/opac-de-627?version=1.1&operation=searchRetrieve&query=pica.iss%3D$ISSN$&maximumRecords=10&recordSchema=picaxml";

    private static final String SRU_PICA_BY_ISBN_QUERY =
        "https://sru.k10plus.de/opac-de-627?version=1.1&operation=searchRetrieve&query=pica.isb%3D$ISBN$&maximumRecords=10&recordSchema=picaxml";

    private static final String PICA_NAMESPACE_URL = "info:srw/schema/5/picaXML-v1.0";
    public static final Namespace PICA_NAMESPACE = Namespace.getNamespace(PICA_NAMESPACE_URL);

    private static List<Element> extractElementsFromPicaResult(ClassicHttpResponse resp) {
        try (InputStream is = resp.getEntity().getContent()) {
            Document document = new SAXBuilder().build(is);
            Element rootElement = document.getRootElement();
            Element recordsElement = rootElement.getChild("records", ZS_NAMESPACE);

            // Check if the <records> element exists
            if (recordsElement == null) {
                // No <records> element, likely zero hits or an error response structure
                // Check for diagnostics which might indicate zero hits explicitly
                Element diagnostics = rootElement.getChild("diagnostics", ZS_NAMESPACE);
                if (diagnostics != null) {
                    // Log diagnostics if needed, but return empty list for zero hits
                    // Example diagnostic for zero hits: info:srw/diagnostic/1/7 (NoRecordsMatch)
                    // For now, just assume any diagnostics means no processable records here
                    return List.of(); // Return empty list
                }
                // If no <records> and no <diagnostics>, it might be an unexpected response format
                // Log a warning? For now, return empty list.
                // log.warn("SRU response did not contain <records> or <diagnostics> element.");
                return List.of();
            }

            // <records> element exists, proceed to extract individual records
            List<Element> recordElements = recordsElement.getChildren("record", ZS_NAMESPACE);
            if (recordElements.isEmpty()) {
                // <records> element is empty, zero hits
                return List.of(); // Return empty list
            }

            // Extract and detach the actual PICA <record> elements
            return recordElements.stream()
                .map(r -> r.getChild("recordData", ZS_NAMESPACE))
                .filter(java.util.Objects::nonNull) // Ensure <recordData> exists
                .map(r -> r.getChild("record", PICA_NAMESPACE))
                .filter(java.util.Objects::nonNull) // Ensure PICA <record> exists
                .map(Element::detach)
                .toList();
        } catch (Exception e) {
            // Log the exception and return an empty list or rethrow as appropriate
            // For now, rethrowing as RuntimeException to maintain previous behavior on actual errors
            throw new RuntimeException("Error parsing SRU response: " + e.getMessage(), e);
        }
    }

    public List<Element> resolvePicaByISSN(String issn) {
        // Normalize ISSN: remove non-numeric characters
        String normalizedIssn = issn.replaceAll("[^0-9]", "");
        try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {
            final HttpGet httpget = new HttpGet(SRU_PICA_BY_ISSN_QUERY.replace("$ISSN$", normalizedIssn));
            return httpclient.execute(httpget, SRUService::extractElementsFromPicaResult);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Element> resolvePicaByISBN(String isbn) {
        // Normalize ISBN: remove non-numeric characters
        String normalizedIsbn = isbn.replaceAll("[^0-9]", "");
        try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {
            final HttpGet httpget = new HttpGet(SRU_PICA_BY_ISBN_QUERY.replace("$ISBN$", normalizedIsbn));
            return httpclient.execute(httpget, SRUService::extractElementsFromPicaResult);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
