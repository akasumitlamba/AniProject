package app.anibrave;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.*;

/** Shared cross-site identity rules; compatible with migrated AniHome keys. */
final class AnimeIdentity {
    private static final Set<String> NOISE = new HashSet<>(Arrays.asList(
        "watch online free english eng sub dub subbed dubbed hd stream streaming anime episodes full latest now tv the and in on at crunchyroll hianime aniwatch zoro gogoanime anitaku animepahe aniwave 9anime kickassanime animesuge animixplay allanime animekai anikoto anikototv animeflix animeheaven animedao netflix hulu funimation youtube bilibili miruro yugenanime".split(" ")));
    private static final Set<String> SITE_WORDS=new HashSet<>(Arrays.asList("crunchyroll hianime aniwatch zoro gogoanime anitaku animepahe aniwave 9anime kickassanime animesuge animixplay allanime animekai anikoto anikototv animeflix animeheaven animedao netflix hulu funimation youtube bilibili miruro yugenanime".split(" ")));
    static String key(String raw) {
        String text=Normalizer.normalize(raw.trim(),Normalizer.Form.NFKD).replaceAll("\\p{M}+","").replace('’','\'').replace('–','-').replace('—','-');
        int season=0;
        Pattern[] seasons={Pattern.compile("(?i)\\bseason\\s*(\\d{1,2})\\b"),Pattern.compile("(?i)\\b(\\d{1,2})(?:st|nd|rd|th)\\s+season\\b"),Pattern.compile("(?i)\\bs(\\d{1,2})\\b")};
        for(Pattern pattern:seasons){Matcher m=pattern.matcher(text);if(m.find()){if(season==0)season=Integer.parseInt(m.group(1));text=text.substring(0,m.start())+" "+text.substring(m.end());}}
        String[] ordinals={"first","second","third","fourth","fifth","sixth"};
        for(int i=0;i<ordinals.length;i++){String expression="(?i)\\b"+ordinals[i]+"\\s+season\\b";if(Pattern.compile(expression).matcher(text).find()){if(season==0)season=i+1;text=text.replaceFirst(expression," ");}}
        text=text.replaceAll("(?i)\\b(?:part|cour)\\s*\\d{1,2}\\b"," ").replaceAll("(?i)\\bfinal\\s+season\\b"," ").replaceAll("(?i)\\((?:dub|sub|dubbed|subbed|uncensored|tv|ona|ova)\\)"," ").replaceAll("\\[[^\\]]*]"," ").replaceAll("['`]","").replaceAll("[^\\p{L}\\p{N}]+"," ").toLowerCase(Locale.ROOT).trim();
        List<String> words=new ArrayList<>(Arrays.asList(text.split(" +")));
        String[] romans={"ii","iii","iv","v","vi"};
        if(season==0&&!words.isEmpty())for(int i=0;i<romans.length;i++)if(words.get(words.size()-1).equals(romans[i])){season=i+2;words.remove(words.size()-1);break;}
        int count=words.size();
        words.removeIf(w->w.isEmpty()||(NOISE.contains(w)&&(count>1||SITE_WORDS.contains(w))));
        String key=String.join(" ",words);if(key.length()>80)key=key.substring(0,80);
        if(key.length()<2||key.matches("[\\d ]+"))return "";
        return key+(season>1?"|s"+season:"");
    }
    static boolean staysDismissed(boolean dismissed,int previous,int next){return dismissed&&previous>0&&previous==next;}
    static boolean newSitting(int previous,String oldSite,long time,int next,String site,long now){return previous!=next||!site.equalsIgnoreCase(oldSite)||now-time>=6L*60*60*1000;}
}
