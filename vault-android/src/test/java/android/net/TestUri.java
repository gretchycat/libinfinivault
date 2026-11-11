package android.net;

import android.os.Parcel;
import java.util.Collections;
import java.util.List;

public class TestUri extends Uri {
    private final String uriString;

    public TestUri(String uriString) {
        super();
        this.uriString = uriString;
    }

    @Override public boolean isHierarchical() { return true; }
    @Override public boolean isRelative() { return false; }

    @Override public String getScheme() {
        if (uriString.contains(":")) {
            return uriString.substring(0, uriString.indexOf(":"));
        }
        return "mock";
    }

    @Override public String getAuthority() {
        if (uriString.contains("://")) {
            int schemeEnd = uriString.indexOf("://") + 3;
            String rest = uriString.substring(schemeEnd);
            int slash = rest.indexOf('/');
            int q = rest.indexOf('?');
            int end = rest.length();
            if (slash != -1) end = Math.min(end, slash);
            if (q != -1) end = Math.min(end, q);
            return rest.substring(0, end);
        }
        return "mock";
    }

    @Override public String getHost() { return getAuthority(); }

    @Override public String getPath() {
        if (uriString.contains("://")) {
            int schemeEnd = uriString.indexOf("://") + 3;
            String rest = uriString.substring(schemeEnd);
            int slash = rest.indexOf('/');
            if (slash == -1) return "";
            int q = rest.indexOf('?');
            return q != -1 ? rest.substring(slash, q) : rest.substring(slash);
        }
        return uriString;
    }

    @Override public String getLastPathSegment() {
        String p = getPath();
        if (p == null || p.isEmpty()) return null;
        int slash = p.lastIndexOf('/');
        return slash != -1 ? p.substring(slash + 1) : p;
    }

    @Override public String getQueryParameter(String key) {
        if (uriString.contains(key + "=")) {
            String after = uriString.substring(uriString.indexOf(key + "=") + key.length() + 1);
            int amp = after.indexOf('&');
            return amp != -1 ? after.substring(0, amp) : after;
        }
        return null;
    }

    @Override public String toString() { return uriString; }
    @Override public String getSchemeSpecificPart() { return uriString; }
    @Override public String getEncodedSchemeSpecificPart() { return uriString; }
    @Override public String getEncodedAuthority() { return getAuthority(); }
    @Override public String getUserInfo() { return null; }
    @Override public String getEncodedUserInfo() { return null; }
    @Override public int getPort() { return -1; }
    @Override public String getEncodedPath() { return getPath(); }
    @Override public String getQuery() { return null; }
    @Override public String getEncodedQuery() { return null; }
    @Override public String getFragment() { return null; }
    @Override public String getEncodedFragment() { return null; }
    @Override public List<String> getPathSegments() { return Collections.emptyList(); }
    @Override public Builder buildUpon() { return null; }
    @Override public int compareTo(Uri other) { return other == null ? 1 : uriString.compareTo(other.toString()); }
    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Uri)) return false;
        return uriString.equals(o.toString());
    }
    @Override public int hashCode() { return uriString.hashCode(); }
    @Override public int describeContents() { return 0; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
}
