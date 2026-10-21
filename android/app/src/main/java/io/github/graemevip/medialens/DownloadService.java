package io.github.graemevip.medialens;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import com.yausername.youtubedl_android.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class DownloadService extends Service {
    static volatile boolean running;
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final Set<String> cancelled=ConcurrentHashMap.newKeySet();
    private final AtomicInteger pending=new AtomicInteger();
    private final Set<String> scheduled=ConcurrentHashMap.newKeySet();
    private final Map<String,HttpURLConnection> connections=new ConcurrentHashMap<>();
    private String active;
    private NotificationManager manager;
    private static final int NOTICE=41;

    @Override public void onCreate() {
        super.onCreate(); running=true; manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("downloads","Downloads",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String id=intent.getStringExtra("id");
        if("cancel".equals(intent.getAction())) {
            if(id!=null) {cancelled.add(id); YoutubeDL.getInstance().destroyProcessById(id); HttpURLConnection conn=connections.get(id);if(conn!=null)conn.disconnect();}
            if(pending.get()==0)stopSelf(); return START_NOT_STICKY;
        }
        if(id==null || !scheduled.add(id)){if(pending.get()==0)stopSelf();return START_NOT_STICKY;}
        JSONObject job=Jobs.get(this,id);if(job==null){scheduled.remove(id);stopSelf(startId);return START_NOT_STICKY;}
        startForeground(NOTICE,notification("Preparing download…",id,0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        pending.incrementAndGet();
        executor.execute(()->{active=id; download(job);active=null;scheduled.remove(id);if(pending.decrementAndGet()==0)new Handler(Looper.getMainLooper()).post(()->{if(pending.get()==0){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}}); });
        return START_NOT_STICKY;
    }
    private Notification notification(String text,String id,int progress) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent cancel=new Intent(this,DownloadService.class).setAction("cancel").putExtra("id",id);
        PendingIntent cp=PendingIntent.getService(this,id.hashCode(),cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle("Media Lens").setContentText(text).setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setProgress(100,Math.min(progress,100),progress<=0).addAction(new Notification.Action.Builder(null,"Cancel",cp).build()).build();
    }
    private void check(String id) throws InterruptedIOException {if(cancelled.contains(id)||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Cancelled");}
    private void progress(JSONObject j,float value,String text) {
        Jobs.put(j,"progress",Math.max(0,Math.min(99,(int)value))); Jobs.put(j,"detail",text);Jobs.save(this,j);
        manager.notify(NOTICE,notification(text,j.optString("id"),(int)value));
    }
    private void download(JSONObject j) {
        String id=j.optString("id"); File folder=new File(getCacheDir(),"downloads/"+id);Uri published=null;
        try {
            check(id); Jobs.put(j,"status","downloading");Jobs.put(j,"error","");Jobs.put(j,"progress",0);Jobs.save(this,j);
            if(!folder.mkdirs()&&!folder.isDirectory())throw new IOException("Cannot create download folder.");
            String url=Engine.url(j.optString("url"));
            if(j.optBoolean("image")) downloadImage(url,folder,j);
            else {
                Engine.init(this);check(id);YoutubeDLRequest q=Engine.request(url);
                String quality=j.optString("quality","best");
                q.addOption("--newline");q.addOption("--no-part");q.addOption("--restrict-filenames");
                q.addOption("-o",new File(folder,"%(title).120B [%(id)s].%(ext)s").getAbsolutePath());
                if("audio".equals(quality)){q.addOption("-f","bestaudio/best");q.addOption("-x");q.addOption("--audio-format","m4a");}
                else {String filter="best".equals(quality)?"":"[height<="+Integer.parseInt(quality)+"]";q.addOption("-f","bv*"+filter+"+ba/b"+filter);q.addOption("--merge-output-format","mp4/mkv");}
                long[] last={0};
                YoutubeDL.getInstance().execute(q,id,(percent,eta,line)->{
                    if(cancelled.contains(id))YoutubeDL.getInstance().destroyProcessById(id);
                    long now=System.currentTimeMillis();if(now-last[0]>600){last[0]=now;progress(j,percent,percent>=99?"Finishing media…":"Downloading · "+Math.max(0,(int)(float)percent)+"%");}
                    return kotlin.Unit.INSTANCE;
                });
            }
            check(id);File[] files=folder.listFiles(f->f.isFile()&&!f.getName().endsWith(".part")&&!f.getName().endsWith(".ytdl")&&!f.getName().endsWith(".temp"));
            if(files==null||files.length!=1||files[0].length()==0)throw new IOException("The engine did not produce one complete media file.");
            File file=files[0];progress(j,99,"Saving to Downloads…");String extension=file.getName().contains(".")?file.getName().substring(file.getName().lastIndexOf('.')+1):"";
            String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(Locale.ROOT));if(mime==null)mime=j.optBoolean("image")?"image/*":"application/octet-stream";
            ContentValues values=new ContentValues();values.put(MediaStore.Downloads.DISPLAY_NAME,file.getName());values.put(MediaStore.Downloads.MIME_TYPE,mime);values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Media Lens");values.put(MediaStore.Downloads.IS_PENDING,1);
            published=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(published==null)throw new IOException("Android could not create the download.");
            try(InputStream in=new FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(published)){if(out==null)throw new IOException("Cannot save file.");byte[] b=new byte[131072];int n;while((n=in.read(b))!=-1){check(id);out.write(b,0,n);}}
            check(id);values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);getContentResolver().update(published,values,null,null);
            Jobs.put(j,"uri",published.toString());Jobs.put(j,"mime",mime);Jobs.put(j,"filename",file.getName());Jobs.put(j,"status","complete");Jobs.put(j,"progress",100);Jobs.put(j,"detail","Saved to Downloads / Media Lens");Jobs.save(this,j);published=null;
        } catch(Exception e){Jobs.put(j,"status",cancelled.contains(id)?"cancelled":"failed");Jobs.put(j,"error",cancelled.contains(id)?"Download cancelled.":Engine.friendly(e));Jobs.save(this,j);}
        finally {if(published!=null)getContentResolver().delete(published,null,null);Engine.delete(folder);cancelled.remove(id);connections.remove(id);}
    }
    private void downloadImage(String url,File dir,JSONObject j) throws Exception {
        String id=j.optString("id");HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();connections.put(id,conn);conn.setConnectTimeout(20000);conn.setReadTimeout(20000);conn.setRequestProperty("User-Agent","MediaLens/0.1");
        try {
            if(conn.getResponseCode()!=200)throw new IOException("Image server returned HTTP "+conn.getResponseCode());
            String type=conn.getContentType();if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("image/"))throw new IOException("This link did not return an image.");
            String extension=MimeTypeMap.getSingleton().getExtensionFromMimeType(type.split(";")[0]);if(extension==null)extension="img";
            File file=new File(dir,"Media-Lens-"+System.currentTimeMillis()+"."+extension);long size=conn.getContentLengthLong(),count=0,last=0;
            try(InputStream in=conn.getInputStream();OutputStream out=new FileOutputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){check(id);out.write(b,0,n);count+=n;long now=System.currentTimeMillis();if(now-last>600){last=now;progress(j,size>0?100f*count/size:0,"Downloading image…");}}}
        }finally{conn.disconnect();}
    }
    @Override public void onTimeout(int startId,int fgsType){if(active!=null){cancelled.add(active);YoutubeDL.getInstance().destroyProcessById(active);}executor.shutdownNow();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){running=false;executor.shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
