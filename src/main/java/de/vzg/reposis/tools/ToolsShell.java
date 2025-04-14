package de.vzg.reposis.tools;

import de.vzg.reposis.tools.pica.PicaConversionService;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import javax.xml.stream.XMLStreamException;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

@ShellComponent
public class ToolsShell {

    private final PicaConversionService picaConversionService;

    // Constructor injection for the service
    public ToolsShell(PicaConversionService picaConversionService) {
        this.picaConversionService = picaConversionService;
    }

    @ShellMethod(key = "convert-import-picaxml", value = "Converts PICA Importformat file to PICA XML.")
    public void convertPica(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input PICA Importformat file.") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the output PICA XML file.") String output) {

        Path inputPath = Paths.get(input);
        Path outputPath = Paths.get(output);

        try {
            picaConversionService.convertPicaFile(inputPath, outputPath);
            System.out.println("PICA conversion completed successfully.");
        } catch (IOException e) {
            System.err.println("Error during file I/O: " + e.getMessage());
            // Consider more specific error handling or logging
        } catch (XMLStreamException e) {
            System.err.println("Error generating XML structure: " + e.getMessage());
        } catch (TransformerException e) {
            System.err.println("Error transforming/writing formatted XML: " + e.getMessage());
        } catch (Exception e) { // Catch unexpected errors
            System.err.println("An unexpected error occurred during PICA conversion: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for unexpected errors
        }
    }


    @ShellMethod(key = "convert-pica-mycore", value = "Converts PICA XML to MyCoRe objects.")
    public void convertPicaXMLToMyCoReObjects(
            @ShellOption(value = {"-i", "--input"}, help = "Path to the input PICA XML file.") String input,
            @ShellOption(value = {"-o", "--output"}, help = "Path to the directory for outputting MyCoRe objects.") String output,
            @ShellOption(value = {"--id-mapper"}, help = "A file which contains the mapping of PICA production number (003@0 to MyCoRe IDs).") String idMapper,
            @ShellOption(value = {"--id-base"}, help = "A base ID for the MyCoRe objects. E.g. artus_mods_00000000") String idBase
    ) {

    }
}
