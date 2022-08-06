import picocli.CommandLine;

import java.util.Scanner;

@CommandLine.Command(name = "kahlaCLI", mixinStandardHelpOptions = true, version = "0.1",
        description = "Converts Picasa tags to DigiKam tags.")
public class KahlaCLI implements Runnable {

  @CommandLine.Option(names = {"-id"}, required = true,
          description = "The directory containing Picasa-tagged images.")
  private String imageDir;

  @CommandLine.Option(names = {"-dd"}, required = true,
          description = "The directory containing DigiKam's digikam4.db file.")
  private String dbDir;

  @CommandLine.Option(names = {"-r"},
          description = "Flag for whether to recursively process images in any folders nested within the image directory.")
  private boolean doRecursive;

  public static void main(String[] args) {
    int exitCode = new CommandLine(new KahlaCLI()).execute(args);
    System.exit(exitCode);
  }

  @Override
  public void run() {
    System.out.println("Images will be processed in the directory: " + imageDir);
    if (doRecursive) { System.out.println("Folders will be recursively traversed."); }
    System.out.println("Your digikam4.db file is located in the directory: " + dbDir);
    System.out.println("Is this correct? (Enter 'n' to cancel. Enter anything else to proceed.)");

    Scanner kb = new Scanner(System.in);
    String input = kb.next();
    if (input.equals("n")) {
      System.out.println("Aborting.");
    } else {
      System.out.println("here's where we would fire up the processing cannon");
      KahlaCore.core(dbDir, imageDir);
    }
  }

}
