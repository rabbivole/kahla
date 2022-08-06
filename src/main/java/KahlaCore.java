import java.io.File;
import java.io.FileNotFoundException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;

public class KahlaCore {
  private static Connection conn;

  private static final String JDBC_AFFIX = "jdbc:sqlite:";
  private static final String DB_NAME = "digikam4.db";


  public static void core(String dbDir, String initialImageDir) {
    dbConnect(dbDir);
    process(initialImageDir);
  }

  private static void process(String currentDir) {
    String[] folders = currentDir.split("/");
    System.out.println("Processing folder "+folders[folders.length-1]+".");

    // First look for a picasa.ini in this directory. If there isn't one, we move on to the next
    // folder.
    Scanner iniFile = null;
    try {
      File f = new File(currentDir+"/picasa.ini");
      iniFile = new Scanner(f);
    } catch (FileNotFoundException e) {
      System.out.println("No picasa.ini found in this directory. Continuing.");
    }

    // If we do have an ini file, tag all the images in this directory.
    if (iniFile != null) {
      processImages(iniFile);
    }
  }

  private static void processImages(Scanner iniFile) {
    int filesTagged = 0; // For verbosely reporting everything we did as a sanity check.
    int filesSkipped = 0;
    // The ini file is a plaintext series of lines. An image name in brackets will be followed by
    // various attributes on their own lines. The one we care about is 'keywords=' followed by a
    // comma-delimited list of tags. I am *reasonably* sure 'keywords' always comes first, but not
    // completely certain. To be safe, we'll assume it might not.

    while (iniFile.hasNext()) {
      // Grab the next line.
      String nextLine = iniFile.nextLine();
      // This should be an image name, but sanity check:
      if (nextLine.charAt(0) != '[') {
        throw new IllegalStateException("Confused and lost in picasa.ini.");
      }
      // Strip the brackets off it to get the image name.
      nextLine = nextLine.substring(1, nextLine.length() - 1);

      // Is this a jpg? If so, skip it. jpgs are handled already.
      if (nextLine.endsWith("jpg") || nextLine.endsWith("jpeg") ||
              nextLine.endsWith("JPG") || nextLine.endsWith("JPEG")) {
        // this space intentionally left blank
      }
      else {

      }

      String pattern = "^[*]";
      iniFile.hasNext(pattern);
    }
  }

  private static void dbConnect(String dbDir) {
    conn = null;
    try {
      conn = DriverManager.getConnection(JDBC_AFFIX+dbDir+"/"+DB_NAME);
      System.out.println("Established connection to DigiKam database.");
    } catch (SQLException throwables) {
      throwables.printStackTrace();
      System.out.println("Couldn't establish database connection.");
      System.out.println("(Most likely, either "+DB_NAME+" wasn't found in "+dbDir+" or DigiKam is " +
              "currently open and has locked the database.)");
    }
  }
}
