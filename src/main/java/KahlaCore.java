import java.io.File;
import java.io.FileNotFoundException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Scanner;
import java.util.regex.Pattern;

public class KahlaCore {
  private static Connection conn;

  private static final String JDBC_AFFIX = "jdbc:sqlite:";
  private static final String DB_NAME = "digikam4.db";


  public static void start(String dbDir, String initialImageDir) {
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
      File f = new File(currentDir+"/.picasa.ini");
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
    // this is probably incorrect
    // Pattern for determining that the next line in the Scanner is an image name.
    Pattern p = Pattern.compile("\\[.*");
    // The ini file is a plaintext series of lines. An image name in brackets will be followed by
    // various attributes on their own lines. The one we care about is 'keywords=' followed by a
    // comma-delimited list of tags. I am *reasonably* sure 'keywords' always comes first, but not
    // completely certain. To be safe, we'll assume it might not.

    // TODO - might want to grab the DigiKam AlbumID here to make queries more efficient?
    // Grab first line.
    String nextLine = iniFile.nextLine();
    while (iniFile.hasNextLine()) {
      // We should have an image name, but sanity check:
      if (nextLine.charAt(0) != '[') {
        throw new IllegalStateException("Confused and lost in picasa.ini.");
      }
      // Strip the brackets off it to get the image name.
      String imageName = nextLine.substring(1, nextLine.length() - 1);

      // Try and find a 'keywords' line while we advance the Scanner to the next file name.
      // This is jank because I can't get hasNext(Pattern) to work.
      String tagsLine = null;
      do {
        nextLine = iniFile.nextLine();
        if (nextLine.startsWith("keywords")) {
          tagsLine = nextLine;
        }
      } while (nextLine.charAt(0) != '[' && iniFile.hasNextLine());

      // If we got some tags and this isn't a jpg, tag the image in DigiKam.
      if ((tagsLine != null) && isNotJpg(imageName)) {
        String[] tags = tagsLine.split(",");
        if (tagImage(imageName, tags)) {
          filesTagged++;
        }
        else { // If we didn't tag, that implies we found a Picasa-tagged image that DigiKam doesn't
          // know about. User should probably be warned.
          filesSkipped++;
        }
      }

      // Scanner should now be pointing at the next file block.
    }

    // Report.
    String report = "Done tagging in this directory. "+filesTagged+" items tagged.";
    if (filesSkipped > 0) {
      report += " "+filesSkipped+" items were skipped, because they don't exist in DigiKam's database.";
    }
    System.out.println(report);
  }

  private static boolean tagImage(String imageName, String[] tags) {
    System.out.println("DEBUG: tagImage currently does nothing! got "+imageName+" with "+tags.length+" tags");
    return true;
  }

  private static boolean isNotJpg(String imageName) {
    // This won't save you if it's some other file renamed to jpg, but it's good enough.
    return (!imageName.endsWith("jpg") && !imageName.endsWith("jpeg") &&
            !imageName.endsWith("JPG") && !imageName.endsWith("JPEG"));
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
