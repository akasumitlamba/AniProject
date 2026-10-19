package app.anibrave;

import java.net.URI;

/** HTTP-only, same-site resume policy. Normal-profile foreground pages only. */
final class ResumeLocation {
    static boolean valid(String url) {
        if(url==null||url.length()>8192||SiteKey.of(url).isEmpty())return false;
        try {return URI.create(url).getRawUserInfo()==null;}catch(Exception e){return false;}
    }
    static String resolve(String seed,String saved) {
        if(!valid(seed)||!valid(saved)||!SiteKey.of(seed).equals(SiteKey.of(saved)))return seed;
        // Never turn an HTTPS shortcut into an insecure request.
        if(seed.regionMatches(true,0,"https:",0,6)&&!saved.regionMatches(true,0,"https:",0,6))return seed;
        return saved;
    }
}
