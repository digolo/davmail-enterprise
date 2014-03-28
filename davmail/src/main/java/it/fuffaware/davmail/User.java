package it.fuffaware.davmail;

import java.util.Properties;

public class User {

	private String user			= null;
	private String email		= null;
	private String url			= null;
	private boolean confirmed 	= false;
	private Properties properties = new Properties();

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public boolean isConfirmed() {
		return confirmed;
	}

	public void setConfirmed(boolean confirmed) {
		this.confirmed = confirmed;
	}

	public void setProperties(Properties properties) {
		this.properties = properties;
	}

	public Properties getProperties() {
		return properties;
	}
	
    @Override
    public boolean equals(Object object) {
        return object == this ||
                object instanceof User && ((User) object).user.equals(this.user);
    }

    @Override
    public int hashCode() {
        return user.hashCode() + url.hashCode();
    }
}