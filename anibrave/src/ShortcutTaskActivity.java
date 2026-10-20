package app.anibrave;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Browser;

/** Stable document root beneath the native web activity, so Recents can recreate it. */
public final class ShortcutTaskActivity extends Activity {
    static final String SEED="app.anibrave.shortcutSeed";
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String seed=getIntent().getStringExtra(SEED);
        if(!ResumeLocation.valid(seed)){finishAndRemoveTask();return;}
        setTaskDescription(new ActivityManager.TaskDescription(SiteKey.of(seed)+" · AniBrave"));
        // Android restores the child activity itself when recreating a saved stack.
        if(saved==null||!hasWebChild())open();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);open();}
    private void open(){
        String seed=getIntent().getStringExtra(SEED);
        if(!ResumeLocation.valid(seed)){finishAndRemoveTask();return;}
        Intent web=new Intent(Intent.ACTION_VIEW,Uri.parse(ResumeStore.resolve(this,seed)));
        web.setClassName(getPackageName(),"org.chromium.chrome.browser.customtabs.FullScreenCustomTabActivity");
        web.putExtra("app.anibrave.immersive",true);
        web.putExtra(Browser.EXTRA_APPLICATION_ID,getPackageName());
        web.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY",0);
        web.putExtra("android.support.customtabs.extra.ENABLE_URLBAR_HIDING",false);
        // Keep the native activity above this document root, never in the main browser task.
        startActivity(web);
    }
    private boolean hasWebChild(){
        ActivityManager manager=getSystemService(ActivityManager.class);
        if(manager!=null)for(ActivityManager.AppTask task:manager.getAppTasks())try{
            ActivityManager.RecentTaskInfo info=task.getTaskInfo();
            if(info.taskId==getTaskId())return info.numActivities>1;
        }catch(IllegalArgumentException|SecurityException ignored){}
        return false;
    }
    @Override protected void onResume(){
        super.onResume();
        getWindow().getDecorView().post(()->{
            // Translucent native windows may start the root underneath them.
            // Only exit when the child has actually finished, never onRestart.
            if(!isFinishing()&&!isDestroyed()&&!hasWebChild())finish();
        });
    }
}
