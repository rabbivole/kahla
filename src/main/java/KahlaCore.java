import java.io.File;
import java.io.FileNotFoundException;
import java.sql.*;
import java.util.*;


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


  /**
   * Starting point for the script, passing in parameters.
   * @param dbDir the formatted user-provided digikam.db location
   * @param initialImageDir the formatted user-provided directory to trawl for images
   * @param doRecursive boolean flag, whether to recursively traverse directories in initialImageDir
   * @param tagPMeta boolean flag, whether to tag files with associated Picasa albums and face tags
   */
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

    // First look for a picasa.ini in this directory. If there isn't one, we move on.
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

  /**
   * Returns an array that contains the name of every folder in `topDir`. If `topDir` isn't a valid
   * file path, this may throw an exception.
   * @param topDir a file path to a folder to look for subfolders in
   * @return an array of String names of subfolders within `topDir`
   */
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

  /**
   * Trawls the Picasa `iniFile` in this directory and creates digiKam tags for all images listed
   * in it. Requires that `albumId` is the correct digiKam database album ID corresponding to the
   * directory this ini file comes from and that `iniFile` is a valid Scanner pointed at the
   * beginning of the file. If we're tagging images with Picasa album/face metadata, `picasaMetaTokens`
   * must contain appropriate information for those albums/people.
   * @param iniFile a Scanner pointing to the beginning of a .picasa.ini file
   * @param albumId the digiKam-assigned album ID for this directory
   * @param picasaMetaTokens a map of <id, name> pairs identifying Picasa albums or people; may be empty
   */
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
          // Found out picasa.ini can track images that are no longer there, which will also cause this.
          filesSkipped++;
        }
      }

      // nextLine should now have the next image name (OR we're out of lines to process).
    }

    // Report.
    String report = "Done tagging in this directory. "+filesTagged+" items tagged.";
    if (filesSkipped > 0) {
      report += " "+filesSkipped+" items were skipped, either because they don't exist in DigiKam's " +
              "database, or because picasa.ini contains a reference to an image that has been moved " +
              "or deleted.";
    }
    System.out.println(report);
  }

  /**
   * Example input:
   * keywords=interior design,minecraft
   *
   * Peels a 'keywords' line of the ini file apart into comma-delimited tags. Populates `tags` with
   * these tags.
   * @param tags a List of tags for the image we're working on, which this method will add to
   * @param nextLine a line from the ini file beginning with 'keywords='
   */
  private static void unpackTags(List<String> tags, String nextLine) {
    // Strip the 'keywords=' off.
    String tagsLine = nextLine.substring(nextLine.indexOf('=') + 1);
    // Split on commas.
    List<String> arr = List.of(tagsLine.split(","));
    // Add all of them to the list.
    tags.addAll(arr);
  }

  /**
   * Example input:
   * albums=953fc0ee97037ec68789367577cd3dbf,e293cfc3ccf741306c52a577562455ee
   * (...where the ini file also includes information on the albums those IDs correspond to)
   *
   * Processes an 'albums' line of the ini file and populates `tags` with appropriate tags for the
   * albums listed. The ini file contains ID numbers for albums; this method adds the names
   * of those albums to the list of tags, with a prefix marking it as a 'picasa meta' tag.
   * @param tags a List of tags for the image we're working on, which this method will add to
   * @param nextLine a line from the ini file beginning with 'albums='
   * @param picasaMetaTokens a HashMap of <id, name> pairs identifying albums and faces; needed to
   *                         find a String name for the Picasa album IDs listed
   */
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
   *
   * Processes a 'faces' line of the ini file and populates `tags` with appropriate tags for the
   * people listed. The ini file contains information on the facetags present in this directory;
   * this method adds the names of those people to the list of tags, with a prefix marking it as a
   * 'picasa meta' tag.
   * @param tags a List of tags for the image we're working on, which this method will add to
   * @param nextLine a line from the ini file beginning with 'faces='
   * @param picasaMetaTokens a HashMap of <id, name> pairs identifying albums and faces; needed to
   *                         find a String name for the facetags listed
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

  /**
   * Crawls the ini file opened by `iniFile` to build an index of Picasa albums and facetags present
   * on the images in this directory. The keys in this HashMap index are the alphanumeric IDs Picasa
   * assigns to albums and facetagged people, and the values are the human-readable names for those
   * artifacts. If none are found, this method returns an empty HashMap.
   *
   * This method DOES NOT clean up after itself; if called,
   * the caller needs to create a new Scanner `iniFile` to 'reset' it afterwards.
   * @param iniFile a Scanner pointing at the beginning of the ini file for our working directory
   * @return a HashMap populated with <id, name> pairs for the albums and facetags listed- or an empty HashMap, if none found
   */
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
    return picasaMetaTokens;
  }

  /**
   * Updates the DigiKam database to tag `imageName` with every tag in `tags`. If the tag doesn't
   * already exist, it will be created. If this image isn't present in DigiKam's data, this method
   * does nothing and returns false. If `tags` is an empty list, this method does nothing and
   * returns true.
   * @param imageName name of the file to tag
   * @param tags a list of tags that will be applied to `imageName`
   * @param albumId DigiKam's ID for the folder/drive location containing `imageName`
   * @return true if the file was tagged with anything in `tags` or `tags` was empty, false otherwise
   */
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

      // Do an INSERT OR IGNORE into ImageTags, just in case it's already tagged.
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

  /**
   * Create a new tag in DigiKam's database and return its ID. This method should only be called
   * after using fetchTagID to check that no tag named `tag` exists.
   * @param tag a String tag new to DigiKam's database
   * @return the unique ID DigiKam assigned the newly added tag, or -1 if a SQL error occurred
   */
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

  /**
   * Fetches DigiKam's ID for a tag with the name `tag`. Returns -1 if the tag doesn't exist.
   * @param tag the tag to look for
   * @return the unique ID DigiKam has assigned that tag, or -1 if it's not found
   */
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

  /**
   * Fetches DigiKam's unique image ID for a file with the name `imageName` in the folder indicated
   * by `albumId`. Returns -1 if not found.
   * @param imageName the file name
   * @param albumId the unique ID DigiKam has assigned to the folder we're working in
   * @return the unique ID for the file, or -1 if not found
   */
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

  /**
   * Fetch the unique ID DigiKam has assigned to the folder with full path `directoryName`. Returns
   * -1 if DigiKam isn't tracking this directory.
   * @param directoryName a filepath with single forward slashes
   * @return DigiKam's album ID for the directory or -1 if not found
   */
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
        return -1;
      }

    }
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
    // If we're here, this folder doesn't exist in DigiKam.
    return -1;
  }

  /**
   * Establish SQLite DB connection.
   * @param dbDir the file path to where digikam.db is stored
   */
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
