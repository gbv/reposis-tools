package de.vzg.reposis.tools.sru;

import de.vzg.reposis.tools.pica.PicaUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@SpringBootTest
class SRUServiceTest {

    @Autowired
    SRUService sruService;

    @Test
    void resolvePicaByISBN() {
        // Test ISBN 9789896890681 should resolve to 663897351
        var elements = sruService.resolvePicaByISBN("9789896890681");
        String expectedPpn = "663897351";

        Assertions.assertTrue(elements.stream()
            .map(PicaUtils::extractPpnFromRecord) // Extract PPN using the utility method
            .anyMatch(expectedPpn::equals), // Check if any extracted PPN matches the expected one
            "Expected PPN " + expectedPpn + " not found in SRU results for ISBN 9789896890681");

    }

    @Test
    void resolvePicaByISSN() {
        // Test ISSN 1078-6279 should resolve to 271596732 and 182561879
        var elements = sruService.resolvePicaByISSN("1078-6279");
        List<String> expectedPpns = List.of("271596732", "182561879");

        // Extract all PPNs from the results
        List<String> actualPpns = elements.stream()
            .map(PicaUtils::extractPpnFromRecord)
            .filter(java.util.Objects::nonNull) // Filter out records where PPN couldn't be extracted
            .toList();

        // Assert that all expected PPNs are present in the actual PPNs
        Assertions.assertTrue(actualPpns.containsAll(expectedPpns),
            "Expected PPNs " + expectedPpns + " not found or incomplete in SRU results for ISSN 1078-6279. Found: " + actualPpns);

        // Optional: Assert that no unexpected PPNs were found (if the result set should be exact)
        Assertions.assertEquals(expectedPpns.size(), actualPpns.size(),
            "Found unexpected PPNs for ISSN 1078-6279. Expected: " + expectedPpns + ", Found: " + actualPpns);
    }
}
