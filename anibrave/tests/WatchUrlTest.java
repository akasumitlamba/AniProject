package app.anibrave;
public final class WatchUrlTest {
    public static void main(String[] args){
        String page="https://crunchyroll.com/watch/caf%C3%A9?q=one";
        for(String alias:new String[]{"https://www.crunchyroll.com/watch/caf%c3%a9?q=one","https://crunchyroll.com:443/watch/café/?q=one"})
            if(!WatchUrl.same(page,alias))throw new AssertionError(alias);
        if(!WatchUrl.same("https://hianime.to/watch/%61?ep=12","https://hianime.to/watch/a/?ep=12"))throw new AssertionError("unreserved encoding");
        for(String other:new String[]{"http://crunchyroll.com/watch/caf%C3%A9?q=one","https://other.com/watch/caf%C3%A9?q=one","https://crunchyroll.com/watch/caf%C3%A9?q=two","https://crunchyroll.com/watch/next?q=one","https://crunchyroll.com:444/watch/caf%C3%A9?q=one","https://user@crunchyroll.com/watch/caf%C3%A9?q=one","","about:blank"})
            if(WatchUrl.same(page,other))throw new AssertionError(other);
        if(WatchUrl.same("https://hianime.to/watch/a%2Fb","https://hianime.to/watch/a/b"))throw new AssertionError("reserved slash");
        if(WatchUrl.same("https://hianime.to/watch/a#one","https://hianime.to/watch/a#two"))throw new AssertionError("hash route");
        System.out.println("13 watch URL checks passed");
    }
}
