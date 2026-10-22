package app.anibrave;

/** Exercises the native-policy bridge without starting an Android UI. */
public final class NativeStripTest {
    public static final class Controller {
        public boolean p,u;
        public Owner a;
        public int q=1280,n=600,e=40,r=0;
        int transitions;
        boolean animate;
        public void b(boolean animate){transitions++;this.animate=animate;}
        public void d(int height,boolean animate){throw new AssertionError("Must not bypass native capture protocol");}
    }
    public static final class Owner {public Object C;}
    public static void main(String[] args)throws Exception{
        Controller c=new Controller();
        if(NativeStrip.layer(null)!=null||NativeStrip.layer(c)!=null)throw new AssertionError("Unattached compositor must retry");
        c.a=new Owner();
        if(NativeStrip.layer(c)!=null)throw new AssertionError("Unattached layer must retry");
        c.a.C=new Object();
        if(NativeStrip.layer(c)!=c.a.C)throw new AssertionError("Attached layer");
        if(NativeStrip.desired(c)!=40)throw new AssertionError("Visible native strip");
        c.p=true;
        if(NativeStrip.desired(c)!=0)throw new AssertionError("Hidden native strip");
        NativeStrip.request(null,null,c,0,null);
        if(c.transitions!=1||c.animate)throw new AssertionError("Native transition delegation");
        c.p=false;c.u=true;c.q=411;
        if(NativeStrip.desired(c)!=0)throw new AssertionError("Responsive phone strip");
        c.q=1200;c.r=12;
        NativeStrip.immediate(c,52);
        if(c.transitions!=2)throw new AssertionError("Restoring native policy");
        try{NativeStrip.request(null,null,c,0,null);throw new AssertionError("Conflicting height accepted");}
        catch(IllegalArgumentException expected){}
        System.out.println("NativeStrip: 9 native policy/capture checks passed");
    }
}
