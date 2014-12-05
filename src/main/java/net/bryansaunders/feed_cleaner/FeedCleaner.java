/**
 * 
 */
package net.bryansaunders.feed_cleaner;

import java.io.IOException;
import java.net.SocketException;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPClientConfig;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPListParseEngine;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Feed Cleaner Main Class. Configures and Starts Application.
 * 
 * @author Bryan Saunders <btsaunde@gmail.com>
 *
 */
public class FeedCleaner {

    private static final Logger LOGGER = LogManager.getLogger(FeedCleaner.class);

    private static final String CONFIG_FILE = "feedCleaner.properties";

    private static final String FTP_HOSTNAME = "ftp.host";

    private static final String FTP_PORT = "ftp.port";

    private static final String FTP_USERNAME = "ftp.username";

    private static final String FTP_PASSWORD = "ftp.password";

    private static final String FEED_DIRECTORY = "feed.directory";

    private static final String FEED_DAYS_OLD = "feed.days_old";
    
    private List<String> ignoreList;

    private PropertiesConfiguration config;

    /**
     * Main Method.
     * 
     * @param cmdArgs
     *            Command Line Arguments.
     */
    public static void main(String[] cmdArgs) {
        LOGGER.info("Feed Cleaner Started.");

        int exitCode = 0;

        FeedCleaner fc = new FeedCleaner();

        try {
            fc.configure();
            fc.cleanFeeds();
        } catch (ConfigurationException ce) {
            LOGGER.error("An Error Occured Configuring the Feed Cleaner, Exiting.", ce);
            exitCode = 1;
        } catch (IOException ioe) {
            LOGGER.error("An Error Occured Communicating with the Server, Exiting.", ioe);
            exitCode = 2;
        }

        LOGGER.info("Feed Cleaner Exiting...");
        System.exit(exitCode);
    }

    /**
     * Connects to the FTP Servers and Removes Stale Feeds.
     * 
     * @throws IOException
     * @throws SocketException
     */
    private void cleanFeeds() throws SocketException, IOException {
        FTPClient ftpClient = this.createFtpClient();
        this.connectToServer(ftpClient);
        this.openFeedDirectory(ftpClient);
        this.listAndRemoveFeeds(ftpClient);
        this.disconnectServer(ftpClient);
    }

    private void openFeedDirectory(FTPClient ftpClient) throws IOException {
        LOGGER.info("Opening Feed Directory...");
        String directory = this.config.getString(FEED_DIRECTORY);
        boolean result = ftpClient.changeWorkingDirectory(directory);
        if (!result) {
            this.disconnectServer(ftpClient);
            throw new SocketException("Server Error Opening Feed Directory.");
        }
        LOGGER.info("Changed to Feed Directory.");
    }

    private void listAndRemoveFeeds(FTPClient ftpClient) throws IOException {
        LOGGER.info("Getting File List...");
        FTPListParseEngine engine = ftpClient.initiateListParsing("net.bryansaunders.feed_cleaner.Mp4FileParser", ".");

        LOGGER.info("Checking Files...");
        // Loop over File List
        while (engine.hasNext()) {
            // Get Files 25 at a time
            FTPFile[] files = engine.getNext(25);
            for (FTPFile file : files) {
                
                // Check for File Type
                if(file.getType() == FTPFile.FILE_TYPE){
                    String fileName = file.getName();

                    // Print File Info
                    if (LOGGER.isDebugEnabled()) {
                        Date timestampDate = file.getTimestamp().getTime();
                        String timestamp = DateFormat.getDateTimeInstance().format(timestampDate);
                        LOGGER.debug("File: " + fileName + " : " + timestamp +" - " +file.getTimestamp().get(Calendar.YEAR));
                    }
                    
                    if(this.ignoreList.contains(fileName)){
                        LOGGER.info("Skipped File (Ignored): " + fileName);
                        continue;
                    }

                    // Check File Age
                    if (this.fileIsStale(file)) {
                        // Delete File
                        ftpClient.deleteFile(fileName);
                        LOGGER.info("Deleted File: " + fileName);
                    }else{
                        LOGGER.info("Skipped File (Not Stale): " + fileName);
                    }
                }
            }
        }
        LOGGER.info("File Check Complete.");
    }

    private boolean fileIsStale(FTPFile file) {
        boolean isStale = false;
        Integer daysOld = this.config.getInt(FEED_DAYS_OLD);
        Date fileDate = file.getTimestamp().getTime();

        long diffInMillies = System.currentTimeMillis() - fileDate.getTime();
        long age = TimeUnit.DAYS.convert(diffInMillies, TimeUnit.MILLISECONDS);

        if (age > daysOld) {
            isStale = true;
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(file.getName() + " is " + age + " Days Old, Stale: " + isStale);
        }

        return isStale;
    }

    private void disconnectServer(FTPClient ftpClient) throws IOException {
        LOGGER.info("Disconnecting from Server...");
        ftpClient.logout();
        ftpClient.disconnect();
        LOGGER.info("Server Disconnected.");
    }

    private void connectToServer(FTPClient ftpClient) throws SocketException, IOException {
        LOGGER.info("Opening Connection to Server...");
        String hostname = this.config.getString(FTP_HOSTNAME);
        Integer port = this.config.getInt(FTP_PORT);
        ftpClient.connect(hostname, port);

        int reply = ftpClient.getReplyCode();
        if (!FTPReply.isPositiveCompletion(reply)) {
            ftpClient.disconnect();
            throw new SocketException("Server Refused Connection.");
        }
        LOGGER.info("Connected to Server.");

        LOGGER.info("Passing Login Information...");
        String username = this.config.getString(FTP_USERNAME);
        String password = this.config.getString(FTP_PASSWORD);
        ftpClient.login(username, password);

        LOGGER.info("Logged in to Server.");
    }

    /**
     * Creates and Configures FTP Client.
     * 
     * @return @Link {@link FTPClient}
     */
    private FTPClient createFtpClient() {
        LOGGER.info("Creating FTP Client...");
        FTPClient client = new FTPClient();

        LOGGER.info("Configuring FTP Client...");
        FTPClientConfig ftpConfig = new FTPClientConfig();
        client.configure(ftpConfig);

        LOGGER.info("FTP Client Ready.");
        return client;
    }

    /**
     * Configures the Feed Cleaner.
     * 
     * @throws ConfigurationException
     */
    private void configure() throws ConfigurationException {
        LOGGER.info("Configuring Feed Cleaner...");

        this.config = new PropertiesConfiguration(CONFIG_FILE);
        this.config.setAutoSave(true);
        this.config.setReloadingStrategy(new FileChangedReloadingStrategy());

        if (LOGGER.isDebugEnabled()) {
            Iterator<?> keys = this.config.getKeys();
            LOGGER.debug("Configuration Properties:");
            while (keys.hasNext()) {
                String key = (String) keys.next();
                String value = (String) this.config.getProperty(key);
                LOGGER.debug("Prop: " + key + " = " + value);
            }
        }
        
        this.ignoreList = new LinkedList<String>();
        this.ignoreList.add(".htaccess");

        LOGGER.info("Feed Cleaner Configured.");
    }

}
