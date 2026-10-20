package app.anibrave;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Hidden pin/launcher entry point; web windows live in separate retained documents. */
public final class ShortcutLaunchActivity extends Activity {
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String seed=getIntent().getDataString();
        if(ResumeLocation.valid(seed)){
            Uri identity=Uri.parse("anibrave://shortcut/"+Uri.encode(SiteKey.of(seed)));
            ActivityManager manager=getSystemService(ActivityManager.class);
            if(manager!=null)for(ActivityManager.AppTask task:manager.getAppTasks()){
                try{
                ActivityManager.RecentTaskInfo info=task.getTaskInfo();
                Intent base=info.baseIntent;
                if(base!=null&&base.getComponent()!=null&&base.getComponent().getClassName().equals(ShortcutTaskActivity.class.getName())
                        &&identity.equals(base.getData())&&info.numActivities>1
                        &&ResumeStore.resolve(this,seed).equals(AniRuntime.taskUrl(info.taskId))){
                    task.setExcludeFromRecents(false);task.moveToFront();finish();return;
                }
                }catch(IllegalArgumentException|SecurityException ignored){} // Task may have been dismissed.
            }
            Intent document=new Intent(this,ShortcutTaskActivity.class).setData(identity);
            document.putExtra(ShortcutTaskActivity.SEED,seed);
            document.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT|Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS);
            startActivity(document);
        }
        finish();
    }
}
