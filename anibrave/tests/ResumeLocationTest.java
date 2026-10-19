package app.anibrave;
public final class ResumeLocationTest {
 public static void main(String[] args){
  String base="https://www.crunchyroll.com/";
  check(ResumeLocation.resolve(base,"https://www.crunchyroll.com/watch/episode?lang=en").contains("watch/episode"));
  check(ResumeLocation.resolve(base,"https://crunchyroll.com/watch/next#player").contains("watch/next"));
  for(String bad:new String[]{"","javascript:alert(1)","file:///tmp/page","https://attacker.com/watch","https://crunchyroll.com.attacker.com/","https://user:pass@crunchyroll.com/watch","http://crunchyroll.com/watch"})check(ResumeLocation.resolve(base,bad).equals(base));
  check(ResumeLocation.resolve("http://localhost:8767/","http://localhost:8767/last").endsWith("/last"));
  check(!ResumeLocation.valid("https://example.com/"+"a".repeat(8192)));
  System.out.println("Resume location: 11 checks passed");
 }
 static void check(boolean result){if(!result)throw new AssertionError();}
}
