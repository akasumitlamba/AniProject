package app.anibrave;
public final class LaunchTrustTest {
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    public static void main(String[] args){
        byte[] a=new byte[32],b=new byte[32];b[0]=1;
        String url="https://www.example.com/watch";
        String t=LaunchTrust.token(a,url);
        check(t.length()==64,"token is a SHA-256 hex digest");
        check(LaunchTrust.matches(a,url,t),"own token accepted");
        check(!LaunchTrust.matches(b,url,t),"another install's token rejected");
        check(!LaunchTrust.matches(a,"https://phish.example/watch",t),"token bound to its URL");
        check(!LaunchTrust.matches(a,url,null),"missing token rejected");
        check(!LaunchTrust.matches(a,url,"x"),"malformed token rejected");
        check(LaunchTrust.hex(LaunchTrust.fromHex(LaunchTrust.ANIHOME_CERT)).equals(LaunchTrust.ANIHOME_CERT),"pinned certificate round-trips");
        check(LaunchTrust.fromHex(LaunchTrust.ANIHOME_CERT).length==32,"pinned certificate is SHA-256");
        System.out.println("8 launch trust checks passed");
    }
}
