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

import org.syncany.plugins.transfer.TransferPlugin;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;

/**
 * @author Darryl Tam <contact@darryltam.com>
 */
public class GoogledriveTransferPlugin extends TransferPlugin {
	//TODO [high] Get ClientId and Secret from official account
	//TODO [medium] Consider restructuring so that we can use less than full drive permissions. eg "file" scope.  However, would this still work if shared with another user?  We wouldn't be able to use existing folders either, unless combined with readonly permission.  Problem: Won't be able to edit files that have been manually copied.
	//TODO [medium] There's an official limit of 25 refresh tokens per client per account.  Since each repository and client uses a refresh token, we should consider sharing them so that old ones don't get revoked.
	public static final String CLIENT_ID = "854258312615-fkucj177vkg4m2atcsbpkqmo0p6u5t34.apps.googleusercontent.com";
	public static final String CLIENT_SECRET = "0c6VG4ACLC99Sy61ETsP7PPS";
	public static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
	public static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
	
	public GoogledriveTransferPlugin() {
		super("googledrive");
	}
}
