/*
 * Syncany, www.syncany.org
 * Copyright (C) 2011-2014 Philipp C. Heckel <philipp.heckel@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.syncany.plugins.googledrive;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.syncany.config.Config;
import org.syncany.plugins.transfer.AbstractTransferManager;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.StorageMoveException;
import org.syncany.plugins.transfer.TransferManager;
import org.syncany.plugins.transfer.files.ActionRemoteFile;
import org.syncany.plugins.transfer.files.CleanupRemoteFile;
import org.syncany.plugins.transfer.files.DatabaseRemoteFile;
import org.syncany.plugins.transfer.files.MultichunkRemoteFile;
import org.syncany.plugins.transfer.files.RemoteFile;
import org.syncany.plugins.transfer.files.SyncanyRemoteFile;
import org.syncany.plugins.transfer.files.TempRemoteFile;
import org.syncany.plugins.transfer.files.TransactionRemoteFile;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpResponse;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.Drive.Files;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;

/**
 * Implements a {@link TransferManager} based on an Google Drive storage backend for the
 * {@link GoogledriveTransferPlugin}.
 * <p/>
 * <p>Using an {@link GoogledriveTransferSettings}, the transfer manager is configured and uses
 * a Google Drive folder to store the Syncany repository data. While repo and
 * master file are stored in the given folder, databases and multichunks are stored
 * in special sub-folders:
 * <p/>
 * <ul>
 * <li>The <tt>databases</tt> folder keeps all the {@link DatabaseRemoteFile}s</li>
 * <li>The <tt>multichunks</tt> folder keeps the actual data within the {@link MultiChunkRemoteFile}s</li>
 * <li>The <tt>actions</tt> folder keeps the {@link ActionRemoteFile}s</li>
 * </ul>
 * <p/>
 * <p>All operations are auto-connected, i.e. a connection is automatically
 * established.
 *
 * @author Darryl Tam <contact@darryltam.com>
 * 
 */
public class GoogledriveTransferManager extends AbstractTransferManager {
	private static final Logger logger = Logger.getLogger(GoogledriveTransferManager.class.getSimpleName());

	private final Drive drive;
	private static final String MULTICHINKS_PATH_NAME = "multichunks";
	private static final String DATABASES_PATH_NAME = "databases";
	private static final String ACTIONS_PATH_NAME = "actions";
	private static final String TRANSACTIONS_PATH_NAME = "transactions";
	private static final String TEMP_PATH_NAME = "temporary";
	
	private static final String APPLICATION_CONTENT_TYPE = "application/x-syncany";
	private static final String FOLDER_CONTENT_TYPE = "application/vnd.google-apps.folder";
	private static final String APPLICATION_NAME = "Syncany Google Drive Client";
	private static final String FILE_DESCRIPTION = "Syncany Google Drive Repository file";
	
	private static final String NOT_TRASHED = "trashed=false";
	
	private final GoogledriveTransferSettings settings;
	
	public GoogledriveTransferManager(GoogledriveTransferSettings settings, Config config) throws StorageException {
		super(settings, config);

		this.settings = settings;
		
		GoogleCredential credentials = new GoogleCredential.Builder()
			.setTransport(GoogledriveTransferPlugin.HTTP_TRANSPORT)
			.setJsonFactory(GoogledriveTransferPlugin.JSON_FACTORY)
			.setClientSecrets(GoogledriveTransferPlugin.CLIENT_ID, GoogledriveTransferPlugin.CLIENT_SECRET)
			.build()
			.setRefreshToken(settings.getRefreshToken());


		//TODO: [medium] Find a way to re-authenticate token if it has expired/been revoked
		if (settings.isSetupComplete()) {
			try {
				credentials.refreshToken();
			} catch (IOException e) {
				logger.log(Level.SEVERE, "Unable to refresh token.  It may be expired or revoked.", new Object[] { settings.getRefreshToken() });
				throw new StorageException("Unable to refresh token.  It may be expired or revoked.");
			}
		} 
		
		this.drive = new Drive.Builder(GoogledriveTransferPlugin.HTTP_TRANSPORT, GoogledriveTransferPlugin.JSON_FACTORY, credentials)
			.setApplicationName(APPLICATION_NAME)
			.build();
	}

	@Override
	public void connect() throws StorageException {
		try {
			logger.log(Level.INFO, "Using Google Drive account from {0}", new Object[] { drive.about().get().execute().getName() });
		}
		catch (Exception e) {
			throw new StorageException("Unable to connect to googledrive", e);
		}
	}

	@Override
	public void disconnect() {
		// Nothing
	}

	@Override
	public void init(boolean createIfRequired) throws StorageException {
		connect();

		try {
			if (!testTargetExists() && createIfRequired) {
				settings.setPathId(createFolder(settings.getPath(), null).getId());
			}

			settings.setMultichunksPathId(createFolder(MULTICHINKS_PATH_NAME, settings.getPathId()).getId());
			settings.setDatabasesPathId(createFolder(DATABASES_PATH_NAME, settings.getPathId()).getId());
			settings.setActionsPathId(createFolder(ACTIONS_PATH_NAME, settings.getPathId()).getId());
			settings.setTransactionsPathId(createFolder(TRANSACTIONS_PATH_NAME, settings.getPathId()).getId());
			settings.setTempPathId(createFolder(TEMP_PATH_NAME, settings.getPathId()).getId());
			settings.setSetupComplete(true);
		}
		catch (IOException e) {
			throw new StorageException("init: Cannot create required directories", e);
		}
		finally {
			disconnect();
		}
	}

	private com.google.api.services.drive.model.File createFolder(String path, String parentId) throws IOException{
		
		return createFolder(splitPath(path), parentId);
	}
	
	private com.google.api.services.drive.model.File createFolder(String[] path, String parentId) throws IOException{
		
		com.google.api.services.drive.model.File result = null;
		
		for (String curPath: path) {
			try {
				com.google.api.services.drive.model.File body = new com.google.api.services.drive.model.File();
				body.setTitle(curPath).setMimeType(FOLDER_CONTENT_TYPE);
				
				if (parentId != null) {
					body.setParents(Arrays.asList(new ParentReference().setId(parentId)));
				}
				
				result = drive.files().insert(body).execute();
				parentId = result.getId();
			}
			catch (IOException e) {
				throw e;
			}
		}
		return result;
	}
	
	private String[] splitPath(String path) {
		// Assume / are directory separators except when escaped with a \, all \ are interpreted as a \ if not escaping a /,
		ArrayList<String> splitList= new ArrayList<String>();
		int curIndex = 0;
		while (curIndex != -1) {
			curIndex = path.indexOf('/', curIndex);
			if (curIndex>=0) {
				if (curIndex != 0) {
					if (path.charAt(curIndex-1) == '\\') {
							// escaped
						curIndex++;
						continue;
					}
				}
				String addString =path.substring(0, curIndex).replace("\\/","/");
				if (!addString.isEmpty()) {
					splitList.add(addString);	
				}
				path = path.substring(curIndex+1);
			}
		}
		String addString =path.replace("\\/","/");
		if (!addString.isEmpty()) {
			splitList.add(path.replace("\\/","/"));
		}
		return splitList.toArray(new String[splitList.size()]);
	}
	private String quoteString(String str) {
		return "'"+str.replace("'", "\\'")+"'";
	}
	
	private String assembleSearch(String... arguments) {
		String result = "";
		for (String argument:arguments) {
			if (result.length() != 0 && argument.length() != 0) {
				result +=" and ";
			}
			result+=argument;
		}
		return result;
	}
	
	private com.google.api.services.drive.model.File getFolder(String folderName) throws StorageException {
		try {
			String requestSearch = assembleSearch(NOT_TRASHED, "title="+quoteString(folderName), quoteString(settings.getPathId()) + " in parents", "mimeType="+quoteString(FOLDER_CONTENT_TYPE));
			Files.List request = drive.files().list().setQ(requestSearch);
			FileList files = request.execute();
			
			if (files.getItems().isEmpty()) {
				throw new StorageException("getFolder: Folder "+folderName+" does not exist.");
			}
			else if (files.getItems().size() != 1) {
				throw new StorageException("getFolder: "+files.getItems().size()+" folders with name "+folderName+" exist.");
			}
			
			return files.getItems().get(0);
			
		}
		catch (Exception ex) {
			logger.log(Level.WARNING, "getFolder: Target does NOT exist, error occurred.", ex);
			throw new StorageException(ex);
			
		}
	}
	
	@Override
	public void download(RemoteFile remoteFile, File localFile) throws StorageException {
		if (!remoteFile.getName().equals(".") && !remoteFile.getName().equals("..")) {
			try {
				// Download file
				File tempFile = createTempFile(localFile.getName());
				
				if (logger.isLoggable(Level.INFO)) {
					logger.log(Level.INFO, "Google Drive: Downloading {0} to temp file {1}", new Object[] { remoteFile.getName(), tempFile });
				}
				
				com.google.api.services.drive.model.File curFile = getFileByName(getRemoteFolderId(remoteFile), remoteFile.getName());
				
				if (curFile.getDownloadUrl() == null || curFile.getDownloadUrl().length() == 0) {
					throw new StorageException("Cannot get download URL for file");
				}
				
				HttpResponse resp = drive.getRequestFactory().buildGetRequest(new GenericUrl(curFile.getDownloadUrl())).execute();

				OutputStream tempFOS = new FileOutputStream(tempFile);

				InputStream tempFIS = resp.getContent();
				IOUtils.copy(tempFIS, tempFOS);
				
				tempFIS.close();
				tempFOS.close();

				// Move file
				if (logger.isLoggable(Level.INFO)) {
					logger.log(Level.INFO, "Google Drive: Renaming temp file {0} to file {1}", new Object[] { tempFile, localFile });
				}

				localFile.delete();
				FileUtils.moveFile(tempFile, localFile);
				tempFile.delete();
			}
			catch ( IOException| StorageException ex) {
				logger.log(Level.SEVERE, "Error while downloading file " + remoteFile.getName(), ex);
				throw new StorageException(ex);
			}
		}
	}

	private java.util.List<com.google.api.services.drive.model.File> getFilesHelper(String folderId, String fileName) throws StorageException{
		try {
			ArrayList<String> requestList= new ArrayList<String>();
			requestList.add(NOT_TRASHED);
			
			if (fileName != null) {
				requestList.add("title="+quoteString(fileName));
			}
			
			if (folderId != null) {
				requestList.add(quoteString(folderId)+" in parents");
			}
			String[] requestArray = requestList.toArray(new String[requestList.size()]);
			
			Files.List request = drive.files().list().setQ(assembleSearch(requestArray));
			
			java.util.List<com.google.api.services.drive.model.File> result = new ArrayList<com.google.api.services.drive.model.File>();
			
			//Assemble list of all files matching name and in folder 
			do {
				try {
					FileList files = request.execute();
				
					result.addAll(files.getItems());
					request.setPageToken(files.getNextPageToken());
				} catch (IOException ex) {
					logger.log(Level.WARNING, "getFilesHelper: Error occurred.", ex);
					request.setPageToken(null);
				}
			} while (request.getPageToken() != null && request.getPageToken().length() > 0);
			
			return result;
			
		}
		catch (Exception ex) {
			logger.log(Level.WARNING, "getFilesHelper: Target does NOT exist, error occurred.", ex);
			throw new StorageException(ex);
		}
	}
	
	private java.util.List<com.google.api.services.drive.model.File> getFilesById(String folderId, String fileName) throws StorageException{
		return getFilesHelper(folderId, fileName);
	}
	
	private java.util.List<com.google.api.services.drive.model.File> getFolderContents(String folderId) throws StorageException{
		return getFilesHelper(folderId, null);
	}
	
	private com.google.api.services.drive.model.File getFileByName(String folderId, String fileName) throws StorageException{
		java.util.List<com.google.api.services.drive.model.File> fileList = getFilesHelper(folderId, fileName);
		if (fileList.isEmpty()) {
			logger.log(Level.WARNING, "getFileByName: Target does NOT exist, error occurred.");
			throw new StorageException("getFileByName: Target does NOT exist, error occurred.");
		} 
		else if (fileList.size() > 1) {
			logger.log(Level.WARNING, "getFileByName: "+fileList.size()+ " files exist.");
			throw new StorageException("getFileByName: "+fileList.size()+ " files exist.");
		}
		return fileList.get(0);
	}
	
	@Override
	public void upload(File localFile, RemoteFile remoteFile) throws StorageException {
		try {
			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Google Drive: Uploading {0} to temp file {1}", new Object[] { localFile, getRemoteFolderId(remoteFile) + ":" + ("temp-"+remoteFile.getName()) });
			}

			com.google.api.services.drive.model.File curFile = new com.google.api.services.drive.model.File();
			curFile.setTitle("temp-"+remoteFile.getName()).setDescription(FILE_DESCRIPTION).setMimeType(APPLICATION_CONTENT_TYPE).setParents(Arrays.asList(new ParentReference().setId(getRemoteFolderId(remoteFile))));
			FileContent mediaContent = new FileContent(APPLICATION_CONTENT_TYPE, localFile);
			curFile = drive.files().insert(curFile, mediaContent).execute();

			if (logger.isLoggable(Level.INFO)) {
				logger.log(Level.INFO, "Google Drive: Renaming temp file {0} to file {1}", new Object[] { "temp-"+remoteFile.getName(), remoteFile.getName() });
			}

			curFile.setTitle(remoteFile.getName());
			Files.Patch patchRequest = drive.files().patch(curFile.getId(),  curFile);
			patchRequest.setFields("title");
			curFile = patchRequest.execute();
			
			if (curFile == null) {
				if (logger.isLoggable(Level.WARNING)) {
					logger.log(Level.WARNING, "Google Drive: Renaming temp file failed");
				}
				throw new StorageException("Renaming Failed");
			}
		}
		catch (StorageException | IOException ex) {
			logger.log(Level.SEVERE, "Could not upload file " + localFile + " to " + remoteFile.getName(), ex);
			throw new StorageException(ex);
		}
	}

	@Override
	public boolean delete(RemoteFile remoteFile) throws StorageException {
		try {
			java.util.List<com.google.api.services.drive.model.File> curFiles = getFilesById(getRemoteFolderId(remoteFile), remoteFile.getName());
			if (curFiles.isEmpty()) {
				return true;
			}
			
			for (com.google.api.services.drive.model.File curFile: curFiles) {
				drive.files().delete(curFile.getId()).execute();
			}
			return true;
		} 
		catch (IOException e) {
			logger.log(Level.SEVERE, "Could not delete file " + remoteFile.getName(), e);
			throw new StorageException(e);
		}
	}

	@Override
	public void move(RemoteFile sourceFile, RemoteFile targetFile) throws StorageException {
		String sourceFolderId = getRemoteFolderId(sourceFile);
		String sourceFileName = sourceFile.getName();
		
		String targetFolderId = getRemoteFolderId(targetFile);
		String targetFileName = targetFile.getName();

		try {
			com.google.api.services.drive.model.File curSourceFile = getFileByName(sourceFolderId, sourceFileName);
			java.util.List<com.google.api.services.drive.model.File> curTargetFiles = getFilesById(targetFolderId, targetFileName);

			if (curTargetFiles.size() > 1) {
				throw new IOException("Multiple destination files matching destination criteria");
			}
			
			com.google.api.services.drive.model.File curTargetFile = null;
			
			if (!curTargetFiles.isEmpty()) {
				curTargetFile = curTargetFiles.get(0);  
			}
			
			boolean modified = false;
			
			//Rename file
			if (!sourceFileName.equals(targetFileName)) {
				if (logger.isLoggable(Level.INFO)) {
					logger.log(Level.INFO, "Google Drive: Renaming file {0} to file {1}", new Object[] { sourceFileName, targetFileName});
				}
				modified = true;
				com.google.api.services.drive.model.File patchFile = new com.google.api.services.drive.model.File();
				patchFile.setTitle(targetFileName);
				patchFile  = drive.files().patch(curSourceFile.getId(),  patchFile).setFields("title").execute();

				if (patchFile == null) {
					if (logger.isLoggable(Level.WARNING)) {
						logger.log(Level.WARNING, "Google Drive: Renaming temp file failed");
					}
					throw new StorageException("Renaming Failed");
				}
				
			}
			
			//Change folder
			if (!sourceFolderId.equals(targetFolderId)) {
				if (logger.isLoggable(Level.INFO)) {
					logger.log(Level.INFO, "Google Drive: Moving file {0} from folder {1} to {2}", new Object[] { sourceFileName, sourceFolderId, targetFolderId});
				}
				modified = true;
				drive.parents().delete(curSourceFile.getId(), sourceFolderId).execute();
				drive.parents().insert(curSourceFile.getId(), new ParentReference().setId(targetFolderId)).execute();

			}
			
			if (modified && curTargetFile != null) {
				//delete destination if moved, and target exists
				drive.files().delete(curTargetFile.getId()).execute();
			}
		}
		catch (IOException e) {
			logger.log(Level.SEVERE, "Could not rename file " + sourceFolderId+ ":" + sourceFileName + " to " + targetFolderId + ":" + targetFileName, e);
			throw new StorageMoveException("Could not rename file " + sourceFolderId+ ":" + sourceFileName + " to " + targetFolderId + ":" + targetFileName, e);
		}
	}

	@Override
	public <T extends RemoteFile> Map<String, T> list(Class<T> remoteFileClass) throws StorageException {
		try {
			// List folder
			String remoteFolderID = getRemoteFolderIdString(remoteFileClass);

			java.util.List<com.google.api.services.drive.model.File> listing = getFolderContents(remoteFolderID);
			
			// Create RemoteFile objects
			Map<String, T> remoteFiles = new HashMap<String, T>();

			for (com.google.api.services.drive.model.File child : listing) {
				try {
					T remoteFile = RemoteFile.createRemoteFile(child.getTitle(), remoteFileClass);
					remoteFiles.put(child.getTitle(), remoteFile);
				}
				catch (Exception e) {
					logger.log(Level.INFO, "Cannot create instance of " + remoteFileClass.getSimpleName() + " for file " + child.getTitle()
							+ "; maybe invalid file name pattern. Ignoring file.");
				}
			}

			return remoteFiles;
		}
		catch (Exception ex) {
			disconnect();
			logger.log(Level.SEVERE, "Unable to list Google Drive directory.", ex);
			throw new StorageException(ex);
		}
	}

	private String getRemoteFolderId(RemoteFile remoteFile) {
		return getRemoteFolderIdString(remoteFile.getClass());
	}

	private String getRemoteFolderIdString(Class<? extends RemoteFile> remoteFile) {
		if (remoteFile.equals(MultichunkRemoteFile.class)) {
			return settings.getMultichunksPathId();
		}
		else if (remoteFile.equals(DatabaseRemoteFile.class) || remoteFile.equals(CleanupRemoteFile.class)) {
			return settings.getDatabasesPathId();
		}
		else if (remoteFile.equals(ActionRemoteFile.class)) {
			return settings.getActionsPathId();
		}
		else if (remoteFile.equals(TransactionRemoteFile.class)) {
			return settings.getTransactionsPathId();
		}
		else if (remoteFile.equals(TempRemoteFile.class)) {
			return settings.getTempPathId();
		}
		else {
			return settings.getPathId();
		}
	}

	@Override
	public boolean testTargetCanWrite() {
		try {
			if (testTargetExists()) {
				String temporaryFilename = "syncany-write-test";
				File tempFile = File.createTempFile(temporaryFilename, "tmp");

				// Write
				com.google.api.services.drive.model.File curFile = new com.google.api.services.drive.model.File();
				curFile.setTitle(temporaryFilename).setDescription(FILE_DESCRIPTION).setMimeType(APPLICATION_CONTENT_TYPE).setParents(Arrays.asList(new ParentReference().setId(settings.getPathId())));
				FileContent mediaContent = new FileContent(APPLICATION_CONTENT_TYPE, tempFile);
				curFile = drive.files().insert(curFile, mediaContent).execute();
				
				//Delete
				tempFile.delete();
				drive.files().delete(curFile.getId()).execute();
				
				logger.log(Level.INFO, "testTargetCanWrite: Can write, test file created/deleted successfully.");
				return true;
			}
			else {
				logger.log(Level.INFO, "testTargetCanWrite: Can NOT write, target does not exist.");
				return false;
			}
		}
		catch ( IOException e) {
			logger.log(Level.INFO, "testTargetCanWrite: Can NOT write to target.", e);
			return false;
		}
	}

	@Override
	public boolean testTargetExists() {
		try {
			
			String[] splitPath = splitPath(settings.getPath());
			String parent = null;
			for (String pathElement:splitPath) {
				String requestSearch = assembleSearch(NOT_TRASHED, "mimeType="+quoteString(FOLDER_CONTENT_TYPE),"title="+quoteString(pathElement));
				if (parent != null) {
					requestSearch = assembleSearch(requestSearch, quoteString(parent)+" in parents");
				}
				Files.List request = drive.files().list().setQ(requestSearch);
				FileList files = request.execute();
				
				if (files.isEmpty()) {
					logger.log(Level.INFO, "testTargetExists: Target does NOT exist.");
					return false;
				} else if (files.getItems().size() != 1) {
					logger.log(Level.WARNING, "testTargetExists: Target exists, but there are multiple folders with the same name.");
					return false;
				}
				parent = files.getItems().get(0).getId();

			}
			
			if (!settings.isSetupComplete()) {
				// When connecting to an existing repository, retrieve the path ids and store them
				settings.setPathId(parent);
				try {
					settings.setMultichunksPathId(getFolder(MULTICHINKS_PATH_NAME).getId());
					settings.setDatabasesPathId(getFolder(DATABASES_PATH_NAME).getId());
					settings.setActionsPathId(getFolder(ACTIONS_PATH_NAME).getId());
					settings.setTransactionsPathId(getFolder(TRANSACTIONS_PATH_NAME).getId());
					settings.setTempPathId(getFolder(TEMP_PATH_NAME).getId());
					settings.setSetupComplete(true);
				}
				catch (StorageException e) {
					logger.log(Level.INFO, "not all subfolders exist yet, which is fine if it's still initializing the repository", e);
				}
			}
			
			logger.log(Level.INFO, "testTargetExists: Target does exist.");
			return true;
			
		}
		catch (Exception e) {
			logger.log(Level.WARNING, "testTargetExists: Target does NOT exist, error occurred.", e);
			return false;
		}
	}

	@Override
	public boolean testTargetCanCreate() {

		try {
			if (testTargetExists()) {
				return true;
			}
			// Google Drive doesn't have per-folder permissions, so creating any folder means you can create the target
			//Note that if there's a subpath, this won't match exactly the target folder
			com.google.api.services.drive.model.File tmpFolder = new com.google.api.services.drive.model.File();
			tmpFolder.setTitle(settings.getPath()).setMimeType(FOLDER_CONTENT_TYPE);
			tmpFolder = drive.files().insert(tmpFolder).execute(); 
			drive.files().delete(tmpFolder.getId()).execute();
		
			logger.log(Level.INFO, "testTargetCanCreate: Can create target at " + settings.getPath());
			return true;
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testTargetCanCreate: Can NOT create target", e);
			return false;
		}
	}

	@Override
	public boolean testRepoFileExists() {
		try {
			SyncanyRemoteFile remoteFile= new SyncanyRemoteFile();
			String remoteFolderId = getRemoteFolderId(remoteFile);
			java.util.List<com.google.api.services.drive.model.File> curFiles = getFilesById(remoteFolderId, remoteFile.getName());
			
			if (curFiles.isEmpty()) {
				logger.log(Level.INFO, "testRepoFileExists: Repo file DOES NOT exist at " + remoteFolderId+":"+remoteFile.getName());
				return false;
			}
			else if (curFiles.size() == 1) {
				logger.log(Level.INFO, "testRepoFileExists: Repo file exists at " + remoteFolderId+":"+remoteFile.getName());
				return true;
			}
			else {
				throw new StorageException("testRepoFileExists: Multiple Repo files exist at " + remoteFolderId+":"+remoteFile.getName());
			}
		}
		catch (Exception e) {
			logger.log(Level.INFO, "testRepoFileExists: Exception when trying to check repo file existence.", e);
			return false;
		}
	}
}
