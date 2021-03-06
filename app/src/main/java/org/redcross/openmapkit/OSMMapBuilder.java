package org.redcross.openmapkit;

import android.app.ProgressDialog;
import android.os.AsyncTask;
import android.util.Log;

import com.spatialdev.osm.OSMMap;
import com.spatialdev.osm.model.JTSModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.io.CountingInputStream;
import com.spatialdev.osm.model.OSMDataSet;

import org.redcross.openmapkit.odkcollect.ODKCollectData;
import org.redcross.openmapkit.odkcollect.ODKCollectHandler;

/**
 * Created by Nicholas Hallahan on 1/28/15.
 * nhallahan@spatialdev.com* 
 */
public class OSMMapBuilder extends AsyncTask<File, Long, JTSModel> {
    
    private static final float MIN_VECTOR_RENDER_ZOOM = 18;
    
    private static Set<String> loadedOSMFiles = new HashSet<>();
    private static JTSModel jtsModel = new JTSModel();
    private static ProgressDialog progressDialog;

    private static int totalFiles = -1;
    private static int completedFiles = 0;
    private static boolean running = false;
    private static Set<OSMMapBuilder> activeBuilders = new HashSet<>();
    private static long totalBytesLoaded = -1;
    private static long totalFileSizes = -1;

    private MapActivity mapActivity; 
    private String fileName;
    private CountingInputStream countingInputStream;
    private long fileSize = -1;
    private long fileBytesLoaded = -1;
    
    // Should be set to true if we are loading edited OSM XML
    private boolean isOSMEdit = false;

    
    public static void buildMapFromExternalStorage(MapActivity mapActivity) throws IOException {
        if (running) {
            throw new IOException("MAP BUILDER CURRENTLY LOADING!");
        }
        running = true;
        
        File[] xmlFiles = ExternalStorage.fetchOSMXmlFiles();
        totalFiles = xmlFiles.length;

        // load the OSM files in OpenMapKit
        for (int i = 0; i < xmlFiles.length; i++) {
            File xmlFile = xmlFiles[i];
            OSMMapBuilder builder = new OSMMapBuilder(mapActivity, false);
//            builder.execute(xmlFile);  // stock executor that doesnt handle big files well
            builder.executeOnExecutor(LARGE_STACK_THREAD_POOL_EXECUTOR, xmlFile);
        }

        // load the edited OSM files in ODK Collect
        if (ODKCollectHandler.isODKCollectMode()) {
            List<File> editedOsmFiles = ODKCollectHandler.getODKCollectData().getEditedOSM();
            totalFiles += editedOsmFiles.size();
            for (File f : editedOsmFiles) {
                OSMMapBuilder builder = new OSMMapBuilder(mapActivity, true);
                builder.executeOnExecutor(LARGE_STACK_THREAD_POOL_EXECUTOR, f);
            }
        }

        setupProgressDialog(mapActivity);
    }

    private OSMMapBuilder(MapActivity mapActivity, boolean isOSMEdit) {
        super();
        this.mapActivity = mapActivity;
        this.isOSMEdit = isOSMEdit;
        activeBuilders.add(this);
    }

    protected static void setupProgressDialog(MapActivity mapActivity) {
        progressDialog = new ProgressDialog(mapActivity);
        progressDialog.setTitle("Loading OSM Data");
        progressDialog.setMessage("");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
//        progressDialog.setProgressNumberFormat("%1d MB / %2d MB");
        progressDialog.setProgress(0);
        progressDialog.setMax(100);
        progressDialog.show();
    }
    
    @Override
    protected JTSModel doInBackground(File... params) {
        File f = params[0];
        fileName = f.getName();
        
        // Check to see if we have already loaded this file...
        if (loadedOSMFiles.contains(f.getAbsolutePath())) {
            return jtsModel;
        }
        
        Log.i("BEGIN_PARSING", fileName);
        setFileSize(f.length());
        try {
            InputStream is = new FileInputStream(f);
            countingInputStream = new CountingInputStream(is);
            OSMDataSet ds = OSMXmlParserInOSMMapBuilder.parseFromInputStream(countingInputStream, this);
            if (isOSMEdit) {
                jtsModel.mergeEditedOSMDataSet(ds);
            } else {
                jtsModel.addOSMDataSet(ds);
            }
            loadedOSMFiles.add(f.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return jtsModel;
    }

    @Override
    protected void onProgressUpdate(Long... progress) {
        long percent = progress[0];
        long elementsRead = progress[1];
        long nodesRead = progress[2];
        long waysRead = progress[3];
        long relationsRead = progress[4];
        Log.i("PARSER_PROGRESS", 
                "fileName=" + fileName + ", " +
                "percent=" + percent + ", " +
                "elementsRead=" + elementsRead + ", " +
                "nodesRead=" + nodesRead + ", " +
                "waysRead=" + waysRead + ", " +
                "relationsRead=" + relationsRead);
        progressDialog.setMessage("Parsing " + (completedFiles + 1) + " of " + totalFiles + " OSM XML Files.");
        progressDialog.setProgress((int)percent);
    }

    @Override
    protected void onPostExecute(JTSModel model) {
        ++completedFiles;
        // do this when everything is done loading
        if (completedFiles == totalFiles) {
            finishAndResetStaticState();
            new OSMMap(mapActivity.getMapView(), model, mapActivity, MIN_VECTOR_RENDER_ZOOM);
        }
    }
    
    private void finishAndResetStaticState() {
        if(progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        running = false;
        completedFiles = 0;
        activeBuilders = new HashSet<>();
    }
    
    public void updateFromParser(long elementReadCount, 
                                 long nodeReadCount, 
                                 long wayReadCount, 
                                 long relationReadCount, 
                                 long tagReadCount) {
        
        fileBytesLoaded = countingInputStream.getCount();
        computeTotalProgress();
        long percent = (long)(((float)totalBytesLoaded / (float)totalFileSizes) * 100);
        publishProgress(percent,
                        elementReadCount, 
                        nodeReadCount, 
                        wayReadCount, 
                        relationReadCount, 
                        tagReadCount);
    }

    private void setFileSize(long size) {
        fileSize = size;
    }
    
    private long getFileSize() {
        return fileSize;
    }
    
    private long getFileBytesLoaded() {
        return fileBytesLoaded;
    }
    
    
    private static void computeTotalProgress() {
        totalBytesLoaded = 0;
        totalFileSizes = 0;
        for (OSMMapBuilder builder : activeBuilders) {
            long bytesLoaded = builder.getFileBytesLoaded();
            long fileSize = builder.getFileSize();
            totalBytesLoaded += bytesLoaded;
            totalFileSizes += fileSize;
        }
    }
    

    /**
     *  CUSTOM THREAD POOL THAT HAS A LARGER STACK SIZE TO HANDLE LARGER OSM XML FILES
     *  Sometimes the tags parsing recurses deeply... 
     *  http://stackoverflow.com/questions/27277861/increase-asynctask-stack-size
     */

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final int KEEP_ALIVE = 1;

    private static final ThreadFactory factory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            ThreadGroup group = new ThreadGroup("OSMMapBuilder_group");
            return new Thread(group, r, "OSMMapBuilder_thread", 50000);
        }
    };

    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    public static final Executor LARGE_STACK_THREAD_POOL_EXECUTOR
            = new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE,
            TimeUnit.SECONDS, sPoolWorkQueue, factory);
    
}
