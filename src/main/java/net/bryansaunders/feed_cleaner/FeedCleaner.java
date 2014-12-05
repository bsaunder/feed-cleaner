/**
 * 
 */
package net.bryansaunders.feed_cleaner;

import java.io.IOException;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.ParseException;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.reloading.FileChangedReloadingStrategy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.enterprisedt.net.ftp.FTPClient;
import com.enterprisedt.net.ftp.FTPException;
import com.enterprisedt.net.ftp.FTPFile;

/**
 * Feed Cleaner Main Class. Configures and Starts Application.
 * 
 * @author Bryan Saunders <btsaunde@gmail.com>
 *
 */
public class FeedCleaner {

	private static final Logger LOGGER = LogManager
			.getLogger(FeedCleaner.class);

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
			LOGGER.error(
					"An Error Occured Configuring the Feed Cleaner, Exiting.",
					ce);
			exitCode = 1;
		} catch (IOException ioe) {
			LOGGER.error(
					"An IO Error Occured Communicating with the Server, Exiting.",
					ioe);
			exitCode = 2;
		} catch (FTPException e) {
			LOGGER.error(
					"A FTP Error Occured Communicting with the Server, Exiting.",
					e);
			exitCode = 3;
		} catch (ParseException e) {
			LOGGER.error(
					"A Parse Error Occured Communicting with the Server, Exiting.",
					e);
			exitCode = 4;
		}

		LOGGER.info("Feed Cleaner Exiting...");
		System.exit(exitCode);
	}

	/**
	 * Connects to the FTP Servers and Removes Stale Feeds.
	 * 
	 * @throws IOException
	 * @throws SocketException
	 * @throws FTPException
	 * @throws ParseException
	 */
	private void cleanFeeds() throws SocketException, IOException,
			FTPException, ParseException {
		FTPClient ftpClient = this.createFtpClient();

		this.connectToServer(ftpClient);
		this.listAndRemoveFeeds(ftpClient);
		this.disconnectServer(ftpClient);
	}

	private void listAndRemoveFeeds(FTPClient ftpClient) throws IOException,
			FTPException, ParseException {
		LOGGER.info("Getting File List...");
		String directory = this.config.getString(FEED_DIRECTORY);
		FTPFile[] files = ftpClient.dirDetails(directory);

		LOGGER.info("Checking Files...");
		for (FTPFile file : files) {

			// Check for File Type
			if (!file.isDir()) {
				String fileName = file.getName();

				// Print File Info
				if (LOGGER.isDebugEnabled()) {
					Date timestampDate = this.getDateFromFileName(fileName);
					String timestamp = DateFormat.getDateTimeInstance().format(
							timestampDate);
					LOGGER.debug("File: " + fileName + " : " + timestamp);
				}

				if (this.ignoreList.contains(fileName)) {
					LOGGER.info("Skipped File (Ignored): " + fileName);
					continue;
				}

				// Check File Age
				if (this.fileIsStale(file)) {
					// Delete File
					// ftpClient.deleteFile(fileName);
					LOGGER.info("Deleted File: " + fileName);
				} else {
					LOGGER.info("Skipped File (Not Stale): " + fileName);
				}
			}
		}
		LOGGER.info("File Check Complete.");
	}

	private boolean fileIsStale(FTPFile file) {
		boolean isStale = false;
		Integer daysOld = this.config.getInt(FEED_DAYS_OLD);
		Date fileDate = this.getDateFromFileName(file.getName());

		long diffInMillies = System.currentTimeMillis() - fileDate.getTime();
		long age = TimeUnit.DAYS.convert(diffInMillies, TimeUnit.MILLISECONDS);

		if (age > daysOld) {
			isStale = true;
		}

		if (LOGGER.isDebugEnabled()) {
			LOGGER.debug(file.getName() + " is " + age + " Days Old, Stale: "
					+ isStale);
		}

		return isStale;
	}

	private Date getDateFromFileName(String name) {
		Date date = new Date();
		try {
			Pattern nameRegex = Pattern
					.compile(
							"([a-z-_]*)\\.([0-9]{4})([0-9]{2})([0-9]{2})_([0-9]{2})([0-9]{2})([0-9]{2})\\.mp4",
							Pattern.CANON_EQ);
			Matcher nameRegexMatcher = nameRegex.matcher(name);
			boolean nameMatched = nameRegexMatcher.find();
			if (nameMatched) {
				Calendar cal = Calendar.getInstance();
				cal.set(Calendar.YEAR,
						Integer.valueOf(nameRegexMatcher.group(2)));
				cal.set(Calendar.MONTH,
						Integer.valueOf(nameRegexMatcher.group(3)) - 1);
				cal.set(Calendar.DAY_OF_MONTH,
						Integer.valueOf(nameRegexMatcher.group(4)));
				cal.set(Calendar.HOUR_OF_DAY,
						Integer.valueOf(nameRegexMatcher.group(5)));
				cal.set(Calendar.MINUTE,
						Integer.valueOf(nameRegexMatcher.group(6)));
				cal.set(Calendar.SECOND,
						Integer.valueOf(nameRegexMatcher.group(7)));
				date = cal.getTime();
			}
		} catch (PatternSyntaxException e) {
			// intentionally do nothing
		}
		return date;
	}

	private void disconnectServer(FTPClient ftpClient) throws FTPException,
			IOException {
		LOGGER.info("Disconnecting from Server...");
		ftpClient.quit();
		LOGGER.info("Server Disconnected.");
	}

	private void connectToServer(FTPClient ftpClient) throws SocketException,
			IOException, FTPException {
		LOGGER.info("Opening Connection to Server...");
		String hostname = this.config.getString(FTP_HOSTNAME);
		Integer port = this.config.getInt(FTP_PORT);
		ftpClient.setRemoteHost(hostname);
		ftpClient.setRemotePort(port);
		ftpClient.connect();

		/*
		 * String reply = ftpClient.getLastValidReply().getReplyCode(); if
		 * (!reply.equals("200")) { ftpClient.quit(); throw new
		 * SocketException("Server Refused Connection, Code: " + reply); }
		 */
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
	 * @return @Link {@link FileTransferClient}
	 */
	private FTPClient createFtpClient() {
		LOGGER.info("Creating FTP Client...");
		FTPClient client = new FTPClient();

		LOGGER.info("Configuring FTP Client...");
		// No Config

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
