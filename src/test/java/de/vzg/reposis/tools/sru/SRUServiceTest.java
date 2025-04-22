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
        // Test ISBN 978-989-689-068-1 (with hyphens) should resolve to 663897351
        String testIsbn = "978-989-689-068-1";
        var elements = sruService.resolvePicaByISBN(testIsbn);
        String expectedPpn = "663897351";

        Assertions.assertTrue(elements.stream()
            .map(PicaUtils::extractPpnFromRecord) // Extract PPN using the utility method
            .anyMatch(expectedPpn::equals), // Check if any extracted PPN matches the expected one
            "Expected PPN " + expectedPpn + " not found in SRU results for ISBN " + testIsbn);

    }

    @Test
    void resolvePicaByISSN() {
        // Test ISSN 1078-6279 (with hyphen) should resolve to 271596732 and 182561879
        String testIssn = "1078-6279";
        var elements = sruService.resolvePicaByISSN(testIssn);
        List<String> expectedPpns = List.of("271596732", "182561879");

        // Extract all PPNs from the results
        List<String> actualPpns = elements.stream()
            .map(PicaUtils::extractPpnFromRecord)
            .filter(java.util.Objects::nonNull) // Filter out records where PPN couldn't be extracted
            .toList();

        // Assert that all expected PPNs are present in the actual PPNs
        Assertions.assertTrue(actualPpns.containsAll(expectedPpns),
            "Expected PPNs " + expectedPpns + " not found or incomplete in SRU results for ISSN " + testIssn + ". Found: " + actualPpns);

        // Optional: Assert that no unexpected PPNs were found (if the result set should be exact)
        Assertions.assertEquals(expectedPpns.size(), actualPpns.size(),
            "Found unexpected PPNs for ISSN " + testIssn + ". Expected: " + expectedPpns + ", Found: " + actualPpns);
    }
}
