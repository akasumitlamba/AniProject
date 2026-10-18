package app.anibrave;

public final class TabLayoutPolicyTest {
    public static void main(String[] args) {
        int checks=0;
        for(int width:new int[]{240,320,360,480,600,800,839,840,1024,1280,1920}) {
            for(boolean expanded:new boolean[]{false,true}) {
                int reserve=TabLayoutPolicy.reserved(width,expanded),panel=TabLayoutPolicy.panel(width,expanded);
                if(reserve!=56&&width-reserve<600)throw new AssertionError("Page squeezed at "+width);
                if(panel>width-48||panel<56)throw new AssertionError("Overlay has no outside dismissal area");
                if(reserve>panel)throw new AssertionError("Reserved space exceeds rail");
                if(!expanded&&(reserve!=56||panel!=56))throw new AssertionError("Collapsed rail changed width");
                checks++;
            }
        }
        if(TabLayoutPolicy.reserved(839,true)!=56||TabLayoutPolicy.reserved(840,true)!=240)throw new AssertionError("Responsive threshold");
        System.out.println(checks+" tab geometry scenarios passed");
    }
}
