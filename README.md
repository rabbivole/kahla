##What it does
This is a (probably pretty jank) script for scraping Picasa data- mostly tags- and moving it to a DigiKam installation. I wrote it for myself, and it worked on my machine, but I have no guarantee it would work for anyone else.

(kahla like... kahlo with an a, sort of like picasso with- nevermind, man)

##How to use it
1. Be on Windows 
>(It might work on other operating systems, but I've had to do some kind of ugly file path surgery and have little faith that it'll transfer over.)
2. Have an established bunch of stuff tagged in Picasa and be comfortable moving to DigiKam
>(It seems like a reasonable successor. This script moves the tags into DigiKam's SQL database, which might be less futureproof than directly writing XMP/EXIF metadata on all the files. However, that's not super accessible for all file types, and DigiKam keeps its data in SQL, so metadata could theoretically be scraped back out later.)
3. Configure DigiKam to use a local SQLite database for its data. Add the folders you keep Picasa-tagged images in as DigiKam 'album roots', let it crawl everything, then back up all your .db files 
4. Invoke the script:

###**java -jar kahla.jar -id="X:/images/stored/here" -dd="X:/digikam/db/stored/here"**

...with optional flags:

###-m 
to also tag images with the Picasa Albums they're in, as well as the names of people facetagged in them.
>(Tags generated from albums and facetags will be prefixed with ``pmeta/``.)

###-r 
to keep looking for more images to tag in subfolders within the image directory provided to **-id**.

---
This will take a while on folders with thousands of files in them. The script is fairly verbose and reports back whenever it finishes a directory.

##Additional notes

For what it's worth, there are Python scripts out there that purport to do this by reading the proprietary Picasa .pmp files and then embedding stuff directly in XMP/EXIF metadata. These options didn't work for me, but they might for you.

If the majority or all of your tagged files are jpgs, DigiKam will probably inherit all of those tags by default. Picasa- or at least, whatever version I was carefully using for like 10 years- stores tags on jpgs directly in the metadata, so those tags are visible in, say, Windows Explorer and DigiKam can see them just fine without additional help. Metadata for other file formats like .png seems to be less well supported, so .jpg seems to be the only format that gets this treatment.

For non-jpgs, Picasa keeps metadata in a hidden ``.picasa.ini`` file in any folder where you have Picasa-tagged files. This file is- interestingly- just human-readable text, so this script functions by crawling through folders and parsing those ini files. If for some reason there are other Picasa versions out there that used .pmp files entirely, this won't work at all. Such are the perils of trying to save stuff from the Google software graveyard.

Additionally, I'm a student and not, like, a professional developer. I wrote this to save my own image archival system because I couldn't get other people's (probably better) scripts to work. It's written in Java because it's tragically what I'm most comfortable with. I make no claims as to whether this will work and have a good feeling it'll explode the second it leaves my PC.  