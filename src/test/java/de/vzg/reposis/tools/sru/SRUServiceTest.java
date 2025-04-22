package de.vzg.reposis.tools.sru;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SRUServiceTest {

    @Autowired
    SRUService sruService;

    @Test
    void resolvePicaByISBN() {
        // Test ISBN 9789896890681 should resolve to 663897351
        var elements = sruService.resolvePicaByISBN("9789896890681");

        Assertions.assertTrue(elements.stream()
            .anyMatch(record -> record.getChildren("datafield", SRUService.PICA_NAMESPACE)
                .stream()
                .anyMatch(df -> {
                    if (df.getAttributeValue("tag").equals("003@")) {
                        return df.getChildren()
                            .stream()
                            .anyMatch(sf -> sf.getAttributeValue("code").equals("0")
                                && sf.getTextNormalize().equals("663897351"));
                    }
                    return false;
                })));

    }

    @Test
    void resolvePicaByISSN() {
        // Test ISSN 1078-6279 should resolve to 271596732 and 182561879
        var elements = sruService.resolvePicaByISSN("1078-6279");

        Assertions.assertTrue(elements.stream()
            .allMatch(record -> record.getChildren("datafield", SRUService.PICA_NAMESPACE)
                .stream()
                .anyMatch(df -> {
                    if (df.getAttributeValue("tag").equals("003@")) {
                        return df.getChildren()
                            .stream()
                            .anyMatch(sf -> sf.getAttributeValue("code").equals("0")
                                && (sf.getTextNormalize().equals("271596732")
                                    || sf.getTextNormalize().equals("182561879")));
                    }
                    return false;
                })));
    }
}
