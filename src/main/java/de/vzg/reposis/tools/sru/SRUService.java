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
            return new ArrayList<>(document.getRootElement().getChild("records", ZS_NAMESPACE)
                .getChildren("record", ZS_NAMESPACE))
                .stream()
                .map(r -> r.getChild("recordData", ZS_NAMESPACE))
                .map(r -> r.getChild("record", PICA_NAMESPACE))
                .map(Element::detach)
                .toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<Element> resolvePicaByISSN(String issn) {
        try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {
            final HttpGet httpget = new HttpGet(SRU_PICA_BY_ISSN_QUERY.replace("$ISSN$", issn));
            return httpclient.execute(httpget, SRUService::extractElementsFromPicaResult);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<Element> resolvePicaByISBN(String isbn) {
        try (final CloseableHttpClient httpclient = HttpClients.createDefault()) {
            final HttpGet httpget = new HttpGet(SRU_PICA_BY_ISBN_QUERY.replace("$ISBN$", isbn));
            return httpclient.execute(httpget, SRUService::extractElementsFromPicaResult);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
