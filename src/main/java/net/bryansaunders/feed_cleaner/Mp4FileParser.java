/**
 * 
 */
package net.bryansaunders.feed_cleaner;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParser;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Parses File List and Only Displays MP4 Files.
 * 
 * @author Bryan Saunders <btsaunde@gmail.com>
 *
 */
public class Mp4FileParser implements FTPFileEntryParser {

    private static final Logger LOGGER = LogManager.getLogger(Mp4FileParser.class);

    /**
     * Line Parser Regex.
     */
    private static final String REGEX = "([bcdelfmpSs-])"
            + "(((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-])))\\+?\\s*" + "(\\d+)\\s+" // link
                                                                                                               // count
            + "(?:(\\S+(?:\\s\\S+)*?)\\s+)?" // owner name (optional spaces)
            + "(?:(\\S+(?:\\s\\S+)*)\\s+)?" // group name (optional spaces)
            + "(\\d+(?:,\\s*\\d+)?)\\s+" // size or n,m
            /*
             * numeric or standard format date: yyyy-mm-dd (expecting hh:mm to follow) MMM [d]d [d]d MMM N.B. use
             * non-space for MMM to allow for languages such as German which use diacritics (e.g. umlaut) in some
             * abbreviations.
             */
            + "((?:\\d+[-/]\\d+[-/]\\d+)|(?:\\S{3}\\s+\\d{1,2})|(?:\\d{1,2}\\s+\\S{3}))\\s+"
            /*
             * year (for non-recent standard format) - yyyy or time (for numeric or recent standard format) [h]h:mm
             */
            + "(\\d+(?::\\d+)?)\\s+"

            + "(\\S*)(\\s*.*)"; // the rest

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.commons.net.ftp.FTPFileEntryParser#preParse(java.util.List)
     */
    public List<String> preParse(List<String> original) {

        List<String> approved = new LinkedList<String>();
        for (String line : original) {
            boolean matched = false;
            try {
                Pattern regex = Pattern.compile("[a-z]*\\.[0-9]*_[0-9]*\\.mp4", Pattern.CANON_EQ);
                Matcher regexMatcher = regex.matcher(line);
                matched = regexMatcher.find();
                if (matched) {
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Approved Line: " + line);
                    }
                    approved.add(line);
                }
            } catch (PatternSyntaxException ex) {
                // Syntax error in the regular expression
            }
        }

        return approved;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.commons.net.ftp.FTPFileEntryParser#parseFTPEntry(java.lang.String)
     */
    public FTPFile parseFTPEntry(String line) {
        FTPFile file = new FTPFile();
        file.setRawListing(line);
        Integer type;
        boolean isDevice = false;

        Pattern regex = Pattern.compile(REGEX, Pattern.CANON_EQ);
        Matcher regexMatcher = regex.matcher(line);
        boolean matched = regexMatcher.find();

        String typeStr = regexMatcher.group(1);
        String hardLinkCount = regexMatcher.group(15);
        String usr = regexMatcher.group(16);
        String grp = regexMatcher.group(17);
        String filesize = regexMatcher.group(18);
        //String datestr = regexMatcher.group(19) + " " + regexMatcher.group(20);
        String name = regexMatcher.group(21);
        String endtoken = regexMatcher.group(22);

        if (matched) {

            // Set Timestamp
            // FTP Server is Reporting Incorrect Values for Some Reason, so we are Relying on the File Name
            try {
                // FTPTimestampParser timestampParser = new FTPTimestampParserImpl();
                // file.setTimestamp(timestampParser.parseTimestamp(datestr));

                Pattern nameRegex = Pattern.compile("([a-z-_]*)\\.([0-9]{4})([0-9]{2})([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2})\\.mp4", Pattern.CANON_EQ);
                Matcher nameRegexMatcher = nameRegex.matcher(line);
                boolean nameMatched = nameRegexMatcher.find();
                if (nameMatched) {
                    Calendar cal = Calendar.getInstance();
                    cal.set(Calendar.YEAR, Integer.valueOf(nameRegexMatcher.group(2)));
                    cal.set(Calendar.MONTH, Integer.valueOf(nameRegexMatcher.group(3))-1);
                    cal.set(Calendar.DAY_OF_MONTH, Integer.valueOf(nameRegexMatcher.group(4)));
                    cal.set(Calendar.HOUR_OF_DAY, Integer.valueOf(nameRegexMatcher.group(5)));
                    cal.set(Calendar.MINUTE, Integer.valueOf(nameRegexMatcher.group(6)));
                    cal.set(Calendar.SECOND, Integer.valueOf(nameRegexMatcher.group(7)));
                    file.setTimestamp(cal);
                } else {
                    file.setTimestamp(Calendar.getInstance());
                }

            } catch (PatternSyntaxException e) {
                // intentionally do nothing
            }

            // Set File Type
            switch (typeStr.charAt(0)) {
                case 'd':
                    type = FTPFile.DIRECTORY_TYPE;
                    break;
                case 'e': // NET-39 => z/OS external link
                    type = FTPFile.SYMBOLIC_LINK_TYPE;
                    break;
                case 'l':
                    type = FTPFile.SYMBOLIC_LINK_TYPE;
                    break;
                case 'b':
                case 'c':
                    isDevice = true;
                    type = FTPFile.FILE_TYPE;
                    break;
                case 'f':
                case '-':
                    type = FTPFile.FILE_TYPE;
                    break;
                default: // e.g. ? and w = whiteout
                    type = FTPFile.UNKNOWN_TYPE;
            }
            file.setType(type);

            // Set Permissions
            Integer g = 4;
            for (int access = 0; access < 3; access++, g += 4) {
                // Use != '-' to avoid having to check for suid and sticky bits
                file.setPermission(access, FTPFile.READ_PERMISSION, (!regexMatcher.group(g).equals("-")));
                file.setPermission(access, FTPFile.WRITE_PERMISSION, (!regexMatcher.group(g + 1).equals("-")));

                String execPerm = regexMatcher.group(g + 2);
                if (!execPerm.equals("-") && !Character.isUpperCase(execPerm.charAt(0))) {
                    file.setPermission(access, FTPFile.EXECUTE_PERMISSION, true);
                } else {
                    file.setPermission(access, FTPFile.EXECUTE_PERMISSION, false);
                }
            }

            // Set Link Counts
            if (!isDevice) {
                try {
                    file.setHardLinkCount(Integer.parseInt(hardLinkCount));
                } catch (NumberFormatException e) {
                    // intentionally do nothing
                }
            }

            // Set Usr/Grp
            file.setUser(usr);
            file.setGroup(grp);

            // Set Size
            try {
                file.setSize(Long.parseLong(filesize));
            } catch (NumberFormatException e) {
                // Intentionally do nothing
            }

            // Set Name
            if (null == endtoken) {
                file.setName(name);
            } else {
                // oddball cases like symbolic links, file names
                // with spaces in them.
                name += endtoken;
                if (type == FTPFile.SYMBOLIC_LINK_TYPE) {

                    int end = name.indexOf(" -> ");
                    // Give up if no link indicator is present
                    if (end == -1) {
                        file.setName(name);
                    } else {
                        file.setName(name.substring(0, end));
                        file.setLink(name.substring(end + 4));
                    }

                } else {
                    file.setName(name);
                }
            }

            // Return File
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("FTPFile Created for " + name);
            }
            return file;
        }

        // Didnt Match Regex, No File Created
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("FTPFile NOT Created for " + name);
        }
        return null;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.apache.commons.net.ftp.FTPFileEntryParser#readNextEntry(java.io.BufferedReader)
     */
    public String readNextEntry(BufferedReader reader) throws IOException {
        return reader.readLine();
    }

}
