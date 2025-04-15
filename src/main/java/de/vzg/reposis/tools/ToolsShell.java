package de.vzg.reposis.tools;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.transform.TransformerException;

import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

import de.vzg.reposis.tools.mycore.PicaMyCoReConversionService;
import de.vzg.reposis.tools.pica.PicaConversionService;

@ShellComponent
public class ToolsShell {

    private final PicaConversionService picaConversionService;
    private final PicaMyCoReConversionService picaMyCoReConversionService; // Added

    // Constructor injection for the services
    public ToolsShell(PicaConversionService picaConversionService,
                      PicaMyCoReConversionService picaMyCoReConversionService) { // Added
        this.picaConversionService = picaConversionService;
        this.picaMyCoReConversionService = picaMyCoReConversionService; // Added
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
        } catch (TransformerException e) { // Removed XMLStreamException catch
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
            @ShellOption(value = {"--id-mapper"}, help = "Path to the file containing PPN to MyCoRe ID mappings (Properties format). Will be created/updated.") String idMapperPathStr,
            @ShellOption(value = {"--id-base"}, help = "Template for generating new MyCoRe object IDs (e.g., artus_mods_00000000).") String idBase
    ) {
        Path inputPath = Paths.get(input);
        Path outputDirPath = Paths.get(output);
        Path idMapperPath = Paths.get(idMapperPathStr);

        try {
            picaMyCoReConversionService.convertPicaXmlToMyCoRe(inputPath, outputDirPath, idMapperPath, idBase);
            System.out.println("PICA XML to MyCoRe conversion completed successfully.");
        } catch (FileNotFoundException e) {
            System.err.println("Error: Input file not found: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error during file I/O: " + e.getMessage());
        } catch (org.jdom2.JDOMException e) { // Added catch for JDOMException
            System.err.println("Error parsing XML with JDOM: " + e.getMessage());
            e.printStackTrace(); // Include stack trace for JDOM errors
        } catch (TransformerException e) { // Removed XMLStreamException catch
            System.err.println("Error during XSLT transformation: " + e.getMessage());
            e.printStackTrace(); // Include stack trace for XSLT errors
        } catch (IllegalArgumentException e) {
            System.err.println("Error: Invalid argument provided: " + e.getMessage());
        } catch (Exception e) { // Catch unexpected errors
            System.err.println("An unexpected error occurred during PICA XML to MyCoRe conversion: " + e.getMessage());
            e.printStackTrace(); // Print stack trace for unexpected errors
        }
    }
}
