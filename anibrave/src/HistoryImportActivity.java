package app.anibrave;

import android.app.*;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Explicit, user-confirmed local transfer from AniHome; never exports data. */
public final class HistoryImportActivity extends Activity {
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        Intent intent=getIntent();Uri uri=intent.getData();
        if(!"app.anihome.launcher".equals(getCallingPackage())||!Intent.ACTION_SEND.equals(intent.getAction())||uri==null||!"content".equals(uri.getScheme())||!"app.anihome.launcher.history-transfer".equals(uri.getAuthority())||!"application/json".equals(intent.getType())){setResult(RESULT_CANCELED);finish();return;}
        new AlertDialog.Builder(this).setTitle("Move watched history to AniBrave?").setMessage("Copy your local anime watch log from AniHome into AniBrave. Existing AniBrave history is kept. AniHome retains the original as a backup.")
            .setPositiveButton("Import",(dialog,which)->importLocal(uri)).setNegativeButton("Cancel",(dialog,which)->{setResult(RESULT_CANCELED);finish();}).setOnCancelListener(dialog->{setResult(RESULT_CANCELED);finish();}).show();
    }
    private void importLocal(Uri uri){
        new Thread(()->{
            try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                if(in==null)throw new IOException("History unavailable");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>16*1024*1024)throw new IOException("History exceeds 16 MB");out.write(buffer,0,n);}
                int added;try(AnimeHistory history=new AnimeHistory(getApplicationContext())){added=history.importHistory(new JSONObject(out.toString(StandardCharsets.UTF_8.name())));}
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("History imported").setMessage(added+" watch log entries added. Tracking now lives in AniBrave.").setPositiveButton("Done",(d,w)->{setResult(RESULT_OK,new Intent().putExtra("imported",true));finish();}).setOnCancelListener(d->{setResult(RESULT_OK,new Intent().putExtra("imported",true));finish();}).show();});
            }catch(Exception failure){runOnUiThread(()->{if(isFinishing()||isDestroyed())return;new AlertDialog.Builder(this).setTitle("History was not imported").setMessage("The local file could not be read or validated. Your existing history was kept.").setPositiveButton("Close",(d,w)->{setResult(RESULT_CANCELED);finish();}).setOnCancelListener(d->{setResult(RESULT_CANCELED);finish();}).show();});}
        },"AniBrave-history-import").start();
    }
}
