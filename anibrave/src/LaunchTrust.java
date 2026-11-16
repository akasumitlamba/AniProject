package app.anibrave;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.net.Uri;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decides who may open a site as a fullscreen window without an address bar. Only AniBrave's
 * own pinned shortcuts (which carry a per-install signed token that other apps cannot read)
 * and a signature-verified AniHome qualify. Any other app or web link opens a normal tab.
 */
final class LaunchTrust {
    static final String TOKEN="app.anibrave.launchToken";
    static final String ANIHOME="app.anihome.launcher";
    /** SHA-256 of AniHome's release signing certificate. */
    static final String ANIHOME_CERT="8f7d9e2044630d790c6b73607910bb8c64e4285ed894f6a6ca3b210592e6270e";
    private static final String PREFS="anibrave_launch", SECRET="secret", MIGRATED="shortcuts_v1";

    private LaunchTrust(){}

    static String token(byte[] secret,String url){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return hex(mac.doFinal(String.valueOf(url).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    static boolean matches(byte[] secret,String url,String token){
        if(token==null||url==null)return false;
        return MessageDigest.isEqual(token(secret,url).getBytes(StandardCharsets.US_ASCII),token.getBytes(StandardCharsets.US_ASCII));
    }
    static byte[] fromHex(String value){
        byte[] out=new byte[value.length()/2];
        for(int i=0;i<out.length;i++)out[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return out;
    }
    static String hex(byte[] bytes){StringBuilder b=new StringBuilder();for(byte x:bytes)b.append(String.format("%02x",x&0xff));return b.toString();}

    private static synchronized byte[] secret(Context c){
        SharedPreferences prefs=c.getSharedPreferences(PREFS,0);
        String saved=prefs.getString(SECRET,null);
        if(saved!=null)return Base64.decode(saved,Base64.NO_WRAP);
        byte[] fresh=new byte[32];new SecureRandom().nextBytes(fresh);
        prefs.edit().putString(SECRET,Base64.encodeToString(fresh,Base64.NO_WRAP)).commit();
        return fresh;
    }

    /** Shortcut intent for a site; the token binds it to this URL and this install. */
    static Intent shortcut(Context c,String url){
        Intent launch=new Intent(Intent.ACTION_VIEW,Uri.parse(url)).setClass(c,ShortcutLaunchActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        launch.putExtra(TOKEN,token(secret(c),url));
        return launch;
    }

    static boolean trusted(android.app.Activity a,String url){
        try{ if(matches(secret(a),url,a.getIntent().getStringExtra(TOKEN)))return true; }catch(RuntimeException ignored){}
        String caller=a.getCallingPackage();
        if(!ANIHOME.equals(caller))return false;
        try{ return a.getPackageManager().hasSigningCertificate(ANIHOME,fromHex(ANIHOME_CERT),PackageManager.CERT_INPUT_SHA256); }
        catch(RuntimeException e){return false;}
    }

    /** Adds tokens to site shortcuts pinned before tokens existed, once. */
    static void migrate(Context c){
        SharedPreferences prefs=c.getSharedPreferences(PREFS,0);
        if(prefs.getBoolean(MIGRATED,false))return;
        try{
            ShortcutManager manager=c.getSystemService(ShortcutManager.class);
            if(manager==null)return;
            List<ShortcutInfo> updates=new ArrayList<>();
            for(ShortcutInfo info:manager.getPinnedShortcuts()){
                Intent old=info.getIntent();
                if(!info.getId().startsWith("site-")||old==null||old.getDataString()==null)continue;
                if(old.getComponent()==null||!ShortcutLaunchActivity.class.getName().equals(old.getComponent().getClassName()))continue;
                updates.add(new ShortcutInfo.Builder(c,info.getId()).setIntent(shortcut(c,old.getDataString())).build());
            }
            if(updates.isEmpty()||manager.updateShortcuts(updates))prefs.edit().putBoolean(MIGRATED,true).apply();
        }catch(RuntimeException ignored){} // Retried on the next start.
    }
}
