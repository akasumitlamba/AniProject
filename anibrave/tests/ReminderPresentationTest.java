package app.anibrave;

public final class ReminderPresentationTest {
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    public static void main(String[] args){
        ReminderPresentation guard=new ReminderPresentation();
        check(guard.accept("site/a","show",9),"initial card");
        check(!guard.accept("site/a","show",9),"repeated progress must not restart or revive timed-out card");
        check(guard.accept("site/a","show",4),"past episode revives card");
        check(!guard.accept("site/a","show",4),"same episode stays hidden");
        check(guard.accept("other-site/a","show",4),"new site visit shows reminder");
        guard.enter("untracked/page");
        check(guard.accept("other-site/a","show",4),"navigation after timeout re-arms next visit");
        guard.reset();check(guard.accept("other-site/a","show",4),"new foreground session");
        check(ReminderPresentation.TIMEOUT_MS==8000,"eight-second baseline");
        System.out.println("8 reminder presentation checks passed");
    }
}
