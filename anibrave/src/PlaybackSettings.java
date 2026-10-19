package app.anibrave;

import android.content.Context;
import android.content.SharedPreferences;

/** Revision checks reject stale frame acknowledgements after a newer native choice. */
final class PlaybackSettings {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("anibrave_sites",0);}
    static boolean valid(double rate){return Double.isFinite(rate)&&rate>=.25&&rate<=4;}
    static synchronized long set(Context c,String site,double rate){
        if(site.isEmpty()||!valid(rate))return -1;
        SharedPreferences p=prefs(c);long revision=Math.max(System.currentTimeMillis(),p.getLong("rateRevision:"+site,0)+1);
        p.edit().putFloat("rate:"+site,(float)rate).putLong("rateRevision:"+site,revision).apply();return revision;
    }
    static synchronized boolean adopt(Context c,String site,double rate,long revision){
        if(!valid(rate)||site.isEmpty()||prefs(c).getLong("rateRevision:"+site,0)!=revision)return false;
        set(c,site,rate);return true;
    }
}
