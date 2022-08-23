import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.sql.*;
import java.util.Arrays;
import java.util.Scanner;
import java.util.regex.Pattern;

// TODO: Automating special tags for files that are in Picasa albums or contain facetags seems
// totally doable. It's slightly a pain in the ass (I think we need to scrape any [.album or
// [Contacts2] data for the directory before we do any tag parsing and store those in a list or
// something; also, we need to *not* skip jpgs for this purpose) so I'll probably add it later.
// Might want to make it optional.

public class KahlaCore {
  private static Connection conn;

  private static final String JDBC_AFFIX = "jdbc:sqlite:";
  private static final String DB_NAME = "digikam4.db";

  // PreparedStatements.
  private static final String QUERY_FETCH_ALBUMID = "SELECT id FROM Albums WHERE relativePath = ? AND albumRoot = ?";
  private static final String QUERY_FETCH_ROOTID = "SELECT id FROM AlbumRoots WHERE specificPath = ?";
  private static final String QUERY_FETCH_IMAGEID = "SELECT id FROM Images WHERE album = ? AND name = ?";
  private static final String QUERY_FETCH_TAGID = "SELECT id FROM Tags WHERE name = ?";
  private static final String QUERY_CREATE_TAG = "INSERT INTO Tags (pid, name) VALUES (0, ?)";
  private static final String QUERY_TAG_IMAGE = "INSERT OR IGNORE INTO ImageTags (imageid, tagid) VALUES(?, ?)";


  public static void start(String dbDir, String initialImageDir, boolean doRecursive) {
    dbConnect(dbDir);
    process(initialImageDir, doRecursive);
    System.out.println("All done. Kahla will now close.");
    try {
      conn.close();
    } catch (SQLException throwables) {
      // Cannot imagine how closing would fail (if we failed to open, we would've exited already)
      // but here you go anyway.
      throwables.printStackTrace();
    }
  }

  private static void process(String currentDir, boolean doRecursive) {
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
      // We need an albumId to tag things properly, or we'll choke on files with duplicate names.
      int albumId = fetchAlbumID(currentDir);
      processImages(iniFile, albumId);
    }

    // Optionally, move through directories. Finding all the directories in currentDir gets ugly.
    if (doRecursive) {
      System.out.println("Continuing through file system.");
      String[] dirsInCurrentDir = getFolders(currentDir);
      System.out.println("DEBUG: found directories: " + Arrays.toString(dirsInCurrentDir));
      for (String d : dirsInCurrentDir) {
        process(currentDir+"/"+d, doRecursive);
      }
    }
  }

  // this is some stackoverflow stuff: https://stackoverflow.com/a/5125258
  private static String[] getFolders(String topDir) {
    File f = new File(topDir);
    return f.list((dir, name) -> { // filtering input
      // To speed this up a bit, auto-reject anything with a common file extension.
      // This is spicy.
      if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".gif")) {
        return false;
      }
      return new File(dir, name).isDirectory();
    });
  }

  private static void processImages(Scanner iniFile, int albumId) {
    int filesTagged = 0; // For verbosely reporting everything we did as a sanity check.
    int filesSkipped = 0;
    // The ini file is a plaintext series of lines. An image name in brackets will be followed by
    // various attributes on their own lines. The one we care about is 'keywords=' followed by a
    // comma-delimited list of tags. I am *reasonably* sure 'keywords' always comes first, but not
    // completely certain. To be safe, we'll assume it might not.

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
        tagsLine = tagsLine.substring(tagsLine.indexOf('=') + 1); // strip the 'keywords='
        String[] tags = tagsLine.split(",");
        if (tagImage(imageName, tags, albumId)) {
          filesTagged++;
        }
        else { // If we didn't tag, that implies we found a Picasa-tagged image that DigiKam doesn't
          // know about. User should probably be warned.
          filesSkipped++;
        }
      }

      // nextLine should now have the next image name (OR we're out of lines to process).
    }

    // Report.
    String report = "Done tagging in this directory. "+filesTagged+" items tagged.";
    if (filesSkipped > 0) {
      report += " "+filesSkipped+" items were skipped, because they don't exist in DigiKam's database.";
    }
    System.out.println(report);
  }

  private static boolean tagImage(String imageName, String[] tags, int albumId) {
    // Try to grab an image id- if there isn't one, DigiKam doesn't know this file, and
    // we should skip.
    int imageId = fetchImageID(imageName, albumId);
    if (imageId == -1) {
      return false;
    }

    for (String tag : tags) {
      // Check if this tag already exists in DigiKam. If not, we need to insert it into Tags.
      int tagId = fetchTagID(tag);
      if (tagId == -1) {
        tagId = createNewTag(tag);
      }

      // Do an INSERT OR IGNORE into ImageTags to actually tag the image, just in case it's already
      // tagged.
      try {
        PreparedStatement ps = conn.prepareStatement(QUERY_TAG_IMAGE);
        ps.clearParameters();
        ps.setInt(1, imageId);
        ps.setInt(2, tagId);
        ps.executeUpdate();

      } catch (SQLException throwables) {
        System.out.println("Failed to tag image.");
        throwables.printStackTrace();
        return false;
      }
    }

    return true;
  }

  private static int createNewTag(String tag) {
    // Tag id is an integer primary key, so if we don't specify it, it'll autoincrement.
    // Then we have to use fetchTagID to figure out what id it actually received (which is lame but
    // I don't know a way to do this that doesn't involve two queries somewhere).
    try {
      PreparedStatement ps = conn.prepareStatement(QUERY_CREATE_TAG);
      ps.clearParameters();
      ps.setString(1, tag);
      ps.executeUpdate();
      return fetchTagID(tag);
    } catch (SQLException throwables) {
      System.out.println("Failed to create tag.");
      throwables.printStackTrace();
      return -1;
    }
  }

  private static int fetchTagID(String tag) {
    try {
      PreparedStatement ps = conn.prepareStatement(QUERY_FETCH_TAGID);
      ps.clearParameters();
      ps.setString(1, tag);
      ResultSet res = ps.executeQuery();
      int tagId = -1;
      if (res.next()) {
        tagId = res.getInt("id");
      }
      return tagId;
    } catch (SQLException throwables) {
      System.out.println("Failed trying to fetch tag ID. (Is DigiKam open?)");
      throwables.printStackTrace();
      return -1;
    }
  }

  private static int fetchImageID(String imageName, int albumId) {
    try {
      PreparedStatement ps = conn.prepareStatement(QUERY_FETCH_IMAGEID);
      ps.clearParameters();
      ps.setInt(1, albumId);
      ps.setString(2, imageName);
      ResultSet res = ps.executeQuery();
      int imageId = -1;
      if (res.next()) {
        imageId = res.getInt("id");
      }
      return imageId;
    } catch (SQLException throwables) {
      System.out.println("Failed trying to fetch image ID. (Is DigiKam open?)");
      throwables.printStackTrace();
      return -1;
    }

  }

  private static int fetchAlbumID(String directoryName) {
    // DigiKam stores directory paths in an extremely wack way. We need to figure out the albumRoot
    // in order to figure out what albumID we're in.

    // DigiKam stores directory paths without the drive letter on them for some reason, so we need
    // to trim that off.
    String relativePath = directoryName;
    if (directoryName.contains(":")) {
      relativePath = directoryName.substring(directoryName.indexOf(':') + 1);
      // If it's only one character long, it's a single '/' and the album's root directory; otherwise,
      // we need to remove a possible trailing slash.
      if (relativePath.length() > 1 && relativePath.endsWith("/")) {
        relativePath = relativePath.substring(0, relativePath.length() - 1);
      }
    }
    System.out.println("ALBUMID DEBUG: relativePath is "+relativePath);
    // We need to figure out the albumRoot. We'll start checking AlbumRoots and lop off more of the
    // path as we go. Anything we lop off will be a path stored in Albums.
    int albumRootId = -1;
    String leftoverPath = "";
    while (albumRootId == -1 && relativePath.length() > 0) {
      try {
        PreparedStatement rootPs = conn.prepareStatement(QUERY_FETCH_ROOTID);
        rootPs.clearParameters();
        rootPs.setString(1, relativePath);
        ResultSet rootRes = rootPs.executeQuery();
        if (rootRes.next()) {
          albumRootId = rootRes.getInt("id");
        }
        else {
          // If we didn't find it, remove the last directory and try again.
          leftoverPath = relativePath.substring(relativePath.lastIndexOf('/')) + leftoverPath;
          relativePath = relativePath.substring(0, relativePath.lastIndexOf('/'));
        }
      } catch (SQLException throwables) {
        System.out.println("Failed to get album root ID; this is a directory DigiKam isn't aware of.");
        // if we didn't get an albumrootID, this file isn't indexed by digikam and we need to
        // continue elegantly
        // TODO: i have a feeling this will print many, many times, which is not ideal
        return -1;
      }

    }
    System.out.println("DEBUG got album root: "+albumRootId);
    // If leftoverPath is an empty string, the current directory is an album root and should just be
    // "/".
    if (leftoverPath.equals("")) {
      leftoverPath = "/";
    }
    try {
      PreparedStatement ps = conn.prepareStatement(QUERY_FETCH_ALBUMID);
      ps.clearParameters();
      ps.setString(1, leftoverPath);
      ps.setInt(2, albumRootId);
      ResultSet res = ps.executeQuery();
      if (res.next()) {
        return res.getInt("id");
      }
    } catch (SQLException throwables) {
      System.out.println("Failed to get album ID for directory: "+directoryName);
      throwables.printStackTrace();
    }
    // We shouldn't ever get here.
    return -1;
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
