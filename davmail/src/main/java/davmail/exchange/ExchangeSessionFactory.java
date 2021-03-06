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
package davmail.exchange;

import it.fuffaware.davmail.User;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.GetMethod;
import org.codehaus.jackson.map.ObjectMapper;

import davmail.BundleMessage;
import davmail.Settings;
import davmail.exception.DavMailAuthenticationException;
import davmail.exception.DavMailException;
import davmail.exception.WebdavNotAvailableException;
import davmail.exchange.dav.DavExchangeSession;
import davmail.exchange.ews.EwsExchangeSession;

/**
 * Create ExchangeSession instances.
 */
public final class ExchangeSessionFactory {
    private static final Object LOCK = new Object();
    private static final Map<PoolKey, ExchangeSession> POOL_MAP = new HashMap<PoolKey, ExchangeSession>();
    
    private static Map<String, Boolean> configChecked = new HashMap<String, Boolean>();
    private static Map<String, Boolean> errorSent = new HashMap<String, Boolean>();
    
    private static ObjectMapper mapper = new ObjectMapper();
    
    static class PoolKey {
        final User context;
        final String userName;
        final String password;

        PoolKey(User context, String userName, String password) {
            this.context = context;
            this.userName = convertUserName(userName);
            this.password = password;
        }

        @Override
        public boolean equals(Object object) {
            return object == this ||
                    object instanceof PoolKey &&
                            ((PoolKey) object).context.equals(this.context) &&
                            ((PoolKey) object).userName.equals(this.userName) &&
                            ((PoolKey) object).password.equals(this.password);
        }

        @Override
        public int hashCode() {
            return context.hashCode() + userName.hashCode() + password.hashCode();
        }
    }

    private ExchangeSessionFactory() {
    }

    private static String normalizeUserResource(String path, String userName) throws UnsupportedEncodingException {
    	if (path != null) {
	    	if (path.endsWith("/")) {
	    		return path + URLEncoder.encode(userName.toLowerCase(), "UTF-8")+".json";
	    	} else if (path != null) {
	    		return path + "/" + URLEncoder.encode(userName.toLowerCase(), "UTF-8")+".json";
	    	}
    	}
		return null;
    }
    
    private static User getUserContext(String userName) throws DavMailException {
    	ExchangeSession.LOGGER.debug("Getting URL for userName: " + userName);
    	
    	User user = null;
    	try {
	    	String uri = Settings.getProperty("davmail.user.context.url");
	    	if (uri != null) {
	    		uri = normalizeUserResource(uri, userName);
	    		if (uri.startsWith("file://")) {
	    			// Open file
	    			user = mapper.readValue(new FileInputStream(uri.substring("file://".length())), User.class);
	    		} else {
	    			// try with HttpClient
	    			HttpClient client = new HttpClient();
			    	GetMethod get = new GetMethod(uri);
			    	get.setFollowRedirects(true);
			    	int httpCode = client.executeMethod(get);
		
			    	if (httpCode == 200) {
			    		user = mapper.readValue(get.getResponseBodyAsStream(), User.class);
			    	}
			    }
	    		
	    		if (user != null) {
	    			if (!user.isConfirmed()) {
		    			ExchangeSession.LOGGER.debug("Account not yet confirmed");
		    			throw new DavMailException("EXCEPTION_CONFIRMATION_EXCEPTION");
	    			}
	    		}
	    	} else {
	    		ExchangeSession.LOGGER.warn("Exception while trying to get URL: davmail.user.context.url is NULL");
	        	BundleMessage message = new BundleMessage("EXCEPTION_REST_EXCEPTION", userName);
	        	throw new DavMailException("EXCEPTION_DAVMAIL_CONFIGURATION", message);
	    	}
    	} catch (Throwable exc) {
    		ExchangeSession.LOGGER.warn("Exception while trying to get URL", exc);
        	BundleMessage message = new BundleMessage("EXCEPTION_REST_EXCEPTION", userName, exc.getClass().getName(), exc.getMessage());
        	throw new DavMailAuthenticationException("EXCEPTION_DAVMAIL_CONFIGURATION", message);
		}
    	
    	if (user == null) {
			ExchangeSession.LOGGER.warn("Account not found : " + userName);
			throw new DavMailException("EXCEPTION_CONNECT_DENIED", userName);
		}
    	
    	ExchangeSession.LOGGER.debug("Got URL for userName: " + userName + ", it is: " + user.getUrl());
    	return user;
    }
    
    private static String convertUserName(String userName) {
        String result = userName;
        // prepend default windows domain prefix
        String defaultDomain = Settings.getProperty("davmail.defaultDomain");
        if (defaultDomain != null && userName.indexOf('\\') < 0 && userName.indexOf('@') < 0) {
            result = defaultDomain + '\\' + userName;
        }
        return result;
    }

    /**
     * Create authenticated Exchange session
     *
     * @param baseUrl  OWA base URL
     * @param userName user login
     * @param password user password
     * @return authenticated session
     * @throws IOException on error
     */
    public static ExchangeSession getInstance(String userName, String password) throws IOException {
        ExchangeSession session = null;
        User context = getUserContext(userName);
        try {
            PoolKey poolKey = new PoolKey(context, userName, password);

            synchronized (LOCK) {
                session = POOL_MAP.get(poolKey);
            }
            if (session != null) {
                ExchangeSession.LOGGER.debug("Got session " + session + " from cache");
            }

            if (session != null && session.isExpired()) {
                ExchangeSession.LOGGER.debug("Session " + session +" for user " + session.userName+ " expired");
                session = null;
                // expired session, remove from cache
                synchronized (LOCK) {
                    POOL_MAP.remove(poolKey);
                }
            }

            if (session == null) {
                String enableEws = Settings.getProperty("davmail.enableEws", "auto");
                if ("true".equals(enableEws) || poolKey.context.getUrl().toLowerCase().endsWith("/ews/exchange.asmx")) {
                    session = new EwsExchangeSession(poolKey.context, poolKey.password);
                } else {
                    try {
                        session = new DavExchangeSession(poolKey.context, poolKey.password);
                    } catch (WebdavNotAvailableException e) {
                        if ("auto".equals(enableEws)) {
                            ExchangeSession.LOGGER.debug(e.getMessage() + ", retry with EWS");
                            session = new EwsExchangeSession(poolKey.context, poolKey.password);
                        } else {
                            throw e;
                        }
                    }
                }
                ExchangeSession.LOGGER.debug("Created new session " + session+" for user "+poolKey.userName);
            }
            // successful login, put session in cache
            synchronized (LOCK) {
                POOL_MAP.put(poolKey, session);
            }
            // session opened, future failure will mean network down
            configChecked.put(poolKey.context.getUrl(), true);
            // Reset so next time an problem occurs message will be sent once
            errorSent.put(poolKey.context.getUrl(), false);
        } catch (DavMailAuthenticationException exc) {
            throw exc;
        } catch (DavMailException exc) {
            throw exc;
        } catch (IllegalStateException exc) {
            throw exc;
        } catch (NullPointerException exc) {
            throw exc;
        } catch (Exception exc) {
            handleNetworkDown(context, exc);
        }
        return session;
    }

    /**
     * Get a non expired session.
     * If the current session is not expired, return current session, else try to create a new session
     *
     * @param currentSession current session
     * @param userName       user login
     * @param password       user password
     * @return authenticated session
     * @throws IOException on error
     */
    public static ExchangeSession getInstance(ExchangeSession currentSession, String userName, String password)
            throws IOException {
        ExchangeSession session = currentSession;
        User context = getUserContext(userName);
        try {
            if (session.isExpired()) {
                ExchangeSession.LOGGER.debug("Session " + session + " expired, trying to open a new one");
                session = null;
                PoolKey poolKey = new PoolKey(context, userName, password);
                // expired session, remove from cache
                synchronized (LOCK) {
                    POOL_MAP.remove(poolKey);
                }
                session = getInstance(userName, password);
            }
        } catch (DavMailAuthenticationException exc) {
            ExchangeSession.LOGGER.debug("Unable to reopen session", exc);
            throw exc;
        } catch (Exception exc) {
            ExchangeSession.LOGGER.debug("Unable to reopen session", exc);
            handleNetworkDown(context, exc);
        }
        return session;
    }

    private static void handleNetworkDown(User context, Exception exc) throws DavMailException {
    	String baseUrl = context.getUrl();
    	if (!checkNetwork() || (configChecked.get(baseUrl) != null && configChecked.get(baseUrl))) {
            ExchangeSession.LOGGER.warn(BundleMessage.formatLog("EXCEPTION_NETWORK_DOWN"));
            // log full stack trace for unknown errors
            if (!((exc instanceof UnknownHostException)||(exc instanceof NetworkDownException))) {
                ExchangeSession.LOGGER.debug(exc, exc);
            }
            throw new NetworkDownException("EXCEPTION_NETWORK_DOWN");
        } else {
            BundleMessage message = new BundleMessage("EXCEPTION_CONNECT", exc.getClass().getName(), exc.getMessage());
            if (errorSent.get(baseUrl) != null && errorSent.get(baseUrl)) {
                ExchangeSession.LOGGER.warn(message);
                throw new NetworkDownException("EXCEPTION_DAVMAIL_CONFIGURATION", message);
            } else {
                // Mark that an error has been sent so you only get one
                // error in a row (not a repeating string of errors).
            	errorSent.put(baseUrl, true);
                ExchangeSession.LOGGER.error(message);
                throw new DavMailException("EXCEPTION_DAVMAIL_CONFIGURATION", message);
            }
        }
    }

    /**
     * Get user password from session pool for SASL authentication
     *
     * @param userName Exchange user name
     * @return user password
     */
    public static String getUserPassword(String userName) {
        String fullUserName = convertUserName(userName);
        for (PoolKey poolKey : POOL_MAP.keySet()) {
            if (poolKey.userName.equals(fullUserName)) {
                return poolKey.password;
            }
        }
        return null;
    }

    /**
     * Check if at least one network interface is up and active (i.e. has an address)
     *
     * @return true if network available
     */
    static boolean checkNetwork() {
        boolean up = false;
        Enumeration<NetworkInterface> enumeration;
        try {
            enumeration = NetworkInterface.getNetworkInterfaces();
            if (enumeration != null) {
                while (!up && enumeration.hasMoreElements()) {
                    NetworkInterface networkInterface = enumeration.nextElement();
                    //noinspection Since15
                    up = networkInterface.isUp() && !networkInterface.isLoopback()
                            && networkInterface.getInetAddresses().hasMoreElements();
                }
            }
        } catch (NoSuchMethodError error) {
            ExchangeSession.LOGGER.debug("Unable to test network interfaces (not available under Java 1.5)");
            up = true;
        } catch (SocketException exc) {
            ExchangeSession.LOGGER.error("DavMail configuration exception: \n Error listing network interfaces " + exc.getMessage(), exc);
        }
        return up;
    }

    /**
     * Reset config check status and clear session pool.
     */
    public static void reset() {
    	configChecked = new HashMap<String, Boolean>();
        errorSent = new HashMap<String, Boolean>();
        POOL_MAP.clear();
    }
}
