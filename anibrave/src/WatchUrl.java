package app.anibrave;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/** Compare a script document with its native tab, without accepting another episode. */
final class WatchUrl {
    static boolean same(String first,String second) {
        try {
            if(!ResumeLocation.valid(first)||!ResumeLocation.valid(second))return false;
            URI a=URI.create(URI.create(first).toASCIIString()),b=URI.create(URI.create(second).toASCIIString());
            return a.getScheme().equalsIgnoreCase(b.getScheme()) && SiteKey.of(first).equals(SiteKey.of(second))
                && port(a)==port(b) && path(a).equals(path(b))
                && Objects.equals(encoding(a.getRawQuery()),encoding(b.getRawQuery()))
                && Objects.equals(encoding(a.getRawFragment()),encoding(b.getRawFragment()));
        }catch(Exception ignored){return false;}
    }
    private static int port(URI u){return u.getPort()>=0?u.getPort():"https".equalsIgnoreCase(u.getScheme())?443:80;}
    private static String path(URI u){String p=encoding(u.getRawPath());if(p==null||p.isEmpty())return "/";return p.length()>1&&p.endsWith("/")?p.substring(0,p.length()-1):p;}
    private static String encoding(String value){
        if(value==null)return null;
        StringBuilder out=new StringBuilder();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='%'&&i+2<value.length()){
                int n=Integer.parseInt(value.substring(i+1,i+3),16);
                if(n>='a'&&n<='z'||n>='A'&&n<='Z'||n>='0'&&n<='9'||n=='-'||n=='.'||n=='_'||n=='~')out.append((char)n);
                else out.append('%').append(value.substring(i+1,i+3).toUpperCase(Locale.ROOT));
                i+=2;
            }else out.append(c);
        }
        return out.toString();
    }
    private WatchUrl(){}
}
