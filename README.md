# Reposis Tools

This project provides command-line tools for processing and converting library data formats, specifically focusing on PICA and MyCoRe.

## Features

*   Convert PICA+ Importformat files to PICA XML.
*   Convert PICA XML files into individual MyCoRe object XML files, including MODS metadata generation via XSLT and handling of related items.

## Building the Project

You can build the project using Apache Maven. The Maven Wrapper (`mvnw`) is included.

```bash
./mvnw clean package
```

This will compile the code, run tests, and create an executable JAR file in the `target/` directory.

## Running the Application

After building, you can run the application using the generated JAR:

```bash
java -jar target/tools-*.jar
```

Alternatively, you can run it directly via the Spring Boot Maven plugin:

```bash
./mvnw spring-boot:run
```

This will start the Spring Shell interface, where you can execute the available commands. Type `help` to see a list of commands or `help <command-name>` for details on a specific command.

## Available Commands

### 1. `convert-import-picaxml`

Converts a file in PICA+ Importformat (plain text) to a structured PICA XML file.

**Usage:**

```bash
convert-import-picaxml --input <path/to/input.txt> --output <path/to/output.xml>
```

**Options:**

*   `-i`, `--input`: (Required) Path to the input PICA Importformat file.
*   `-o`, `--output`: (Required) Path where the output PICA XML file will be saved.

### 2. `convert-pica-mycore`

Converts a PICA XML file (containing one or more records) into individual MyCoRe object XML files. It uses XSLT for MODS transformation, generates unique MyCoRe IDs, manages mappings between PPNs and MyCoRe IDs, and links related items.

**Usage:**

```bash
convert-pica-mycore --input <path/to/input.xml> --output <path/to/output/dir> --id-mapper <path/to/idmap.properties> --id-base <prefix_00000000>
```

**Options:**

*   `-i`, `--input`: (Required) Path to the input PICA XML file.
*   `-o`, `--output`: (Required) Path to the directory where the generated MyCoRe object XML files will be saved.
*   `--id-mapper`: (Required) Path to a properties file used to store and retrieve mappings between PICA PPNs and MyCoRe IDs. If the file exists, it will be loaded; otherwise, it will be created. It is updated if new IDs are generated.
*   `--id-base`: (Required) A template string used for generating new MyCoRe IDs. It must end with a sequence of digits, which determines the padding for the counter (e.g., `my_archive_mods_00000001`). The tool finds the highest existing ID matching this pattern in the mapper file and starts generating new IDs from the next number.
