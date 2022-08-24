import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.sql.*;
import java.util.*;
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

  private static final String METATAG_AFFIX = "pmeta/";

  // PreparedStatements.
  private static final String QUERY_FETCH_ALBUMID = "SELECT id FROM Albums WHERE relativePath = ? AND albumRoot = ?";
  private static final String QUERY_FETCH_ROOTID = "SELECT id FROM AlbumRoots WHERE specificPath = ?";
  private static final String QUERY_FETCH_IMAGEID = "SELECT id FROM Images WHERE album = ? AND name = ?";
  private static final String QUERY_FETCH_TAGID = "SELECT id FROM Tags WHERE name = ?";
  private static final String QUERY_CREATE_TAG = "INSERT INTO Tags (pid, name) VALUES (0, ?)";
  private static final String QUERY_TAG_IMAGE = "INSERT OR IGNORE INTO ImageTags (imageid, tagid) VALUES(?, ?)";


  public static void start(String dbDir, String initialImageDir,
                           boolean doRecursive, boolean tagPMeta) {
    dbConnect(dbDir);
    process(initialImageDir, doRecursive, tagPMeta);
    System.out.println("All done. Kahla will now close.");
    try {
      conn.close();
    } catch (SQLException throwables) {
      // Cannot imagine how closing would fail (if we failed to open, we would've exited already)
      // but here you go anyway.
      throwables.printStackTrace();
    }
  }

  private static void process(String currentDir, boolean doRecursive, boolean tagPAlbums) {
    String[] folders = currentDir.split("/");
    System.out.println("Processing folder "+folders[folders.length-1]+".");

    // First look for a picasa.ini in this directory. If there isn't one, we move on to the next
    // folder.
    Scanner iniFile = null;
    File f = new File(currentDir+"/.picasa.ini");
    try {
      iniFile = new Scanner(f);
    } catch (FileNotFoundException e) {
      System.out.println("No picasa.ini found in this directory. Continuing.");
    }

    // If we do have an ini file, tag all the images in this directory.
    if (iniFile != null) {
      // We need an albumId to tag things properly, or we'll choke on files with duplicate names.
      int albumId = fetchAlbumID(currentDir);

      // If tagPAlbums is true, we're going to add digiKam tags for Picasa albums and Picasa faces.
      // To do this, we first need to scan through the ini file and build a record of all the
      // albums/people. We'll store each unique alphanumeric token as a hashmap key, with the plaintext
      // name as its value.
      HashMap<String, String> picasaMetaTokens = null;
      if (tagPAlbums) {
        picasaMetaTokens = buildTokenMap(iniFile);
        // Clean up after ourselves by creating a new Scanner; processImages still needs to read the
        // file.
        try {
          iniFile = new Scanner(f);
        } catch (FileNotFoundException e) {
          // This shouldn't happen; we only get here if we successfully created a Scanner above.
          e.printStackTrace();
        }

      }
      processImages(iniFile, albumId, picasaMetaTokens);
    }

    // Optionally, move through directories. Finding all the directories in currentDir gets ugly.
    if (doRecursive) {
      System.out.println("Continuing through file system.");
      String[] dirsInCurrentDir = getFolders(currentDir);
      for (String d : dirsInCurrentDir) {
        process(currentDir+"/"+d, doRecursive, tagPAlbums);
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

  private static void processImages(Scanner iniFile, int albumId, HashMap<String, String> picasaMetaTokens) {
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

      // Try and find applicable data while we advance the Scanner to the next file name.
      // This is jank because I can't get hasNext(Pattern) to work.
      String tagsLine = null;
      String albumsLine = null;
      String facesLine = null;
      List<String> tags = new LinkedList<>();
      do {
        nextLine = iniFile.nextLine();
        if (nextLine.startsWith("keywords")) {
          unpackTags(tags, nextLine);
        }
        else if (nextLine.startsWith("albums")) {
          unpackAlbums(tags, nextLine, picasaMetaTokens);
        }
        else if (nextLine.startsWith("faces")) {
          unpackFaces(tags, nextLine, picasaMetaTokens);
        }
      } while (nextLine.charAt(0) != '[' && iniFile.hasNextLine());

      // If we got some tags, tag the image in DigiKam.
      if (!tags.isEmpty()) {
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

  /**
   * Example input:
   * keywords=interior design,minecraft
   * @param tags
   * @param nextLine
   */
  private static void unpackTags(List<String> tags, String nextLine) {
    // Strip the 'keywords=' off.
    String tagsLine = nextLine.substring(nextLine.indexOf('=') + 1);
    // Split on commas.
    List<String> arr = List.of(tagsLine.split(","));
    // Add all of them to the list.
    tags.addAll(arr);
  }

  private static void unpackAlbums(List<String> tags, String nextLine,
                                   HashMap<String, String> picasaMetaTokens) {
    // Strip the 'albums=' off.
    String albumsLine = nextLine.substring(nextLine.indexOf('=') + 1);
    // Split on commas.
    String[] arr = albumsLine.split(",");
    // We have a list of tokens. We need to add the plaintext album names that correspond to
    // those tokens.
    for (String t : arr) {
      tags.add(METATAG_AFFIX + picasaMetaTokens.get(t));
    }
  }

  /**
   * Example input:
   * faces=rect64(1c863ab430a462a5),7765103530c632d3;rect64(44633848593c6170),1b5634af99e8c7e2
   * (the second token in each pair is the thing we need)
   * @param tags
   * @param nextLine
   */
  private static void unpackFaces(List<String> tags, String nextLine,
                                  HashMap<String, String> picasaMetaTokens) {
    // This one gets uglier.
    // Strip the 'faces=' off.
    String facesLine = nextLine.substring(nextLine.indexOf('=') + 1);
    // Split on semicolons to get a list of 'rect64(#),token' pairs.
    String[] pairs = facesLine.split(";");
    for (String p : pairs) {
      // We want everything after the comma.
      String token = p.substring(p.indexOf(",") + 1);
      // Add the plaintext person name that corresponds to this token.
      tags.add(METATAG_AFFIX + picasaMetaTokens.get(token));
    }
  }

  private static HashMap<String, String> buildTokenMap(Scanner iniFile) {
    HashMap<String, String> picasaMetaTokens = new HashMap<>();
    String nextLine = iniFile.nextLine();
    while (iniFile.hasNextLine()) {
      // If this is the beginning of an album info block:
      if (nextLine.startsWith("[.album")) {
        /**
         * Example input:
         * [.album:ca7ebf894b2735c6f8bed85e4eadc9f5]
         * name=debug-album
         * token=ca7ebf894b2735c6f8bed85e4eadc9f5
         * date=2022-08-23T13:44:49-07:00
         *
         * However, Picasa can also generate 'phantom' albums with no name or date that aren't used?
         * Great. Thanks. So we need to be prepared for that.
         */
        nextLine = iniFile.nextLine();
        String name = nextLine.substring(nextLine.indexOf('=') + 1);
        nextLine = iniFile.nextLine();
        // If this is a 'phantom' album, abort saving this album's data.
        if (nextLine.startsWith("[")) {
          continue;
        }
        System.out.println("DEBUG: " + nextLine);
        String token = nextLine.substring(nextLine.indexOf('=') + 1);
        picasaMetaTokens.put(token, name); // we'll want to find name using token
      } // If this is the beginning of the face info block:
      else if (nextLine.equals("[Contacts2]")) {
        /**
         * Example input:
         * [Contacts2]
         * 461691f5081b1d22=debug-peser;;
         * f4b185d2a7bfac7d=debug-yoshi;;
         */
        nextLine = iniFile.nextLine();
        while (!nextLine.startsWith("[")) {
          String token = nextLine.substring(0, nextLine.indexOf('='));
          String name = nextLine.substring(nextLine.indexOf('=') + 1, nextLine.indexOf(';'));
          picasaMetaTokens.put(token, name);
          nextLine = iniFile.nextLine();
        }
      }
      else {
        nextLine = iniFile.nextLine();
      }
    }
    System.out.println("DEBUG: built meta table: ");
    System.out.println(picasaMetaTokens);
    return picasaMetaTokens;
  }

  private static boolean tagImage(String imageName, List<String> tags, int albumId) {
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
