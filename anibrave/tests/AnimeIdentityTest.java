package app.anibrave;

public final class AnimeIdentityTest {
    private static void eq(String expected,String actual){if(!expected.equals(actual))throw new AssertionError(expected+" != "+actual);}
    public static void main(String[] args){
        eq("frieren beyond journeys end|s2",AnimeIdentity.key("Frieren: Beyond Journey's End 2nd Season | Crunchyroll"));
        eq("frieren beyond journeys end|s2",AnimeIdentity.key("Watch Frieren: Beyond Journey’s End Season 2 English Sub - HiAnime"));
        eq("one piece",AnimeIdentity.key("One Piece"));
        eq("",AnimeIdentity.key("Crunchyroll"));
        eq("example ii",AnimeIdentity.key("Example Season 1 II"));
        eq("example|s2",AnimeIdentity.key("Example Season 2 S3"));
        eq("example|s4",AnimeIdentity.key("Example Final Season IV"));
        if(!AnimeIdentity.staysDismissed(true,9,9)||AnimeIdentity.staysDismissed(true,9,8)||AnimeIdentity.staysDismissed(true,9,10)||AnimeIdentity.staysDismissed(true,9,99))throw new AssertionError("Latest episode reminder rule");
        if(AnimeIdentity.newSitting(9,"hianime.to",100,9,"hianime.to",10000)||!AnimeIdentity.newSitting(9,"hianime.to",100,8,"hianime.to",10000)||!AnimeIdentity.newSitting(9,"hianime.to",100,9,"crunchyroll.com",10000))throw new AssertionError("Watch sittings");
        System.out.println("Anime identity/reminder/sitting tests passed");
    }
}
