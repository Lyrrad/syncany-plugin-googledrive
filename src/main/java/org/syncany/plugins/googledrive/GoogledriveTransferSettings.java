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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.simpleframework.xml.Element;
import org.syncany.plugins.googledrive.GoogledriveTransferSettings.GoogleDriveOAuthGenerator;
import org.syncany.plugins.transfer.Encrypted;
import org.syncany.plugins.transfer.OAuth;
import org.syncany.plugins.transfer.OAuthGenerator;
import org.syncany.plugins.transfer.Setup;
import org.syncany.plugins.transfer.StorageException;
import org.syncany.plugins.transfer.TransferSettings;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.services.drive.DriveScopes;

/**
 * The Google Drive connection represents the settings required to connect to 
 * a Google Drive-based storage backend. It can be used to initialize/create 
 * an {@link GoogledriveTransferManager} and is part of the 
 * {@link GoogledriveTransferPlugin}.
 * 
 * @author Darryl Tam <contact@darryltam.com>
 */
@OAuth(value = GoogleDriveOAuthGenerator.class)
public class GoogledriveTransferSettings extends TransferSettings {

	private static final String REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob";
	private static GoogleAuthorizationCodeFlow flow;

	@Element(name = "refreshToken", required = true)
	@Setup(order = 1, visible = false)
	@Encrypted
	private String refreshToken;
	
	@Element(name = "pathId", required = false)
	@Setup(order = 2, visible = false)
	@Encrypted
	private String pathId;

	@Element(name = "path", required = true)
	@Setup(order = 3, description = "Relative path on Google Drive")
	private String path;
	
	@Element(name = "isSetupComplete", required = true)
	@Setup(order = 4, visible=false)
	private boolean isSetupComplete;

	public String getRefreshToken() {
		return refreshToken;
	}

	public void setRefreshToken(String refreshToken) {
		this.refreshToken = refreshToken;
	}

	public String getPathId() {
		return pathId;
	}

	public void setPathId(String pathId) {
		this.pathId = pathId;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isSetupComplete() {
		return isSetupComplete;
	}

	public void setSetupComplete(boolean isSetupComplete) {
		this.isSetupComplete = isSetupComplete;
	}

	public class GoogleDriveOAuthGenerator implements OAuthGenerator {		
		@Override
		public URI generateAuthUrl() throws StorageException {
			
			flow = new GoogleAuthorizationCodeFlow.Builder(
		        GoogledriveTransferPlugin.HTTP_TRANSPORT, GoogledriveTransferPlugin.JSON_FACTORY, 
		        GoogledriveTransferPlugin.CLIENT_ID, GoogledriveTransferPlugin.CLIENT_SECRET,
		        Arrays.asList(DriveScopes.DRIVE)).setAccessType("offline")
		        .setApprovalPrompt("force").build();

		    String url = flow.newAuthorizationUrl().setRedirectUri(REDIRECT_URI).build();
			    
			try {
				return new URI(url);
			} 
			catch (URISyntaxException e) {
				throw new StorageException(e);
			}
		}

		@Override
		public void checkToken(String code) throws StorageException {
			try {
				GoogleTokenResponse response = flow.newTokenRequest(code).setRedirectUri(REDIRECT_URI).execute();

				GoogleCredential credential = new GoogleCredential.Builder().setTransport(GoogledriveTransferPlugin.HTTP_TRANSPORT)
						.setJsonFactory(GoogledriveTransferPlugin.JSON_FACTORY)
						.setClientSecrets(GoogledriveTransferPlugin.CLIENT_ID, GoogledriveTransferPlugin.CLIENT_SECRET)
						.build()
						.setFromTokenResponse(response);

				refreshToken = credential.getRefreshToken();
				isSetupComplete = false;
			}
			catch (Exception e) {
				throw new RuntimeException("Error requesting Google Drive data: " + e.getMessage());
			}
		}
	}
}
