/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2009  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.ui.browser;

import java.net.URI;
import java.net.URISyntaxException;

import davmail.BundleMessage;
import davmail.ui.AboutFrame;
import davmail.ui.tray.DavGatewayTray;

/**
 * Open default browser.
 */
public final class DesktopBrowser {
	private DesktopBrowser() {
    }

    /**
     * Open default browser at location URI.
     * User Java 6 Desktop class, OSX open command or SWT program launch
     *
     * @param location location URI
     */
    public static void browse(URI location) {
        try {
            // trigger ClassNotFoundException
            ClassLoader classloader = AboutFrame.class.getClassLoader();
            classloader.loadClass("java.awt.Desktop");

            // Open link in default browser
            AwtDesktopBrowser.browse(location);
        } catch (Exception e) {
            DavGatewayTray.error(new BundleMessage("LOG_UNABLE_TO_OPEN_LINK"), e);
        }
    }

    /**
     * Open default browser at location.
     * User Java 6 Desktop class, OSX open command or SWT program launch
     *
     * @param location target location
     */
    public static void browse(String location) {
    	try {
    		DesktopBrowser.browse(new URI(location));
    	} catch (URISyntaxException e) {
    		DavGatewayTray.error(new BundleMessage("LOG_UNABLE_TO_OPEN_LINK"), e);
    	}
	}

}
