package com.mse.player;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.Stream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@CapacitorPlugin(name = "YouTubeExtractor")
public class YouTubeExtractorPlugin extends Plugin {

    private static boolean initialized = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void load() {
        initExtractor();
    }

    private synchronized void initExtractor() {
        if (!initialized) {
            NewPipe.init(DownloaderImpl.getInstance());
            initialized = true;
        }
    }

    private File getMusicDir() {
        File musicDir = new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "MSE");
        if (!musicDir.exists()) musicDir.mkdirs();
        return musicDir;
    }

    @PluginMethod
    public void checkStoragePermission(PluginCall call) {
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            granted = Environment.isExternalStorageManager();
        } else {
            granted = ContextCompat.checkSelfPermission(getContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestStoragePermission(PluginCall call) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                call.resolve(new JSObject().put("granted", true));
            } else {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                    getActivity().startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    getActivity().startActivity(intent);
                }
                call.resolve(new JSObject().put("granted", false));
            }
        } else {
            if (ContextCompat.checkSelfPermission(getContext(),
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                call.resolve(new JSObject().put("granted", true));
            } else {
                ActivityCompat.requestPermissions(getActivity(),
                    new String[]{
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    }, 1002);
                call.resolve(new JSObject().put("granted", false));
            }
        }
    }

    @PluginMethod
    public void migrateFiles(PluginCall call) {
        executor.execute(() -> {
            try {
                File oldDir = new File(getContext().getFilesDir(), "mse_music");
                if (!oldDir.exists() || !oldDir.isDirectory()) {
                    call.resolve(new JSObject().put("migrated", 0));
                    return;
                }
                File newDir = getMusicDir();
                int count = 0;
                File[] files = oldDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.getName().endsWith(".mp3")) {
                            File dest = new File(newDir, f.getName());
                            if (!dest.exists()) {
                                try (FileInputStream in = new FileInputStream(f);
                                     FileOutputStream out = new FileOutputStream(dest)) {
                                    byte[] buf = new byte[8192];
                                    int len;
                                    while ((len = in.read(buf)) != -1) {
                                        out.write(buf, 0, len);
                                    }
                                }
                            }
                            f.delete();
                            count++;
                        }
                    }
                }
                File[] remaining = oldDir.listFiles();
                if (remaining == null || remaining.length == 0) {
                    oldDir.delete();
                }
                call.resolve(new JSObject().put("migrated", count));
            } catch (Exception e) {
                call.reject("Migration failed: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void search(PluginCall call) {
        String query = call.getString("query", "");
        if (query.isEmpty()) {
            call.reject("Query is required");
            return;
        }

        executor.execute(() -> {
            try {
                SearchInfo searchInfo = SearchInfo.getInfo(
                    ServiceList.YouTube,
                    ServiceList.YouTube.getSearchQHFactory().fromQuery(query)
                );

                JSArray results = new JSArray();
                for (InfoItem item : searchInfo.getRelatedItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem stream = (StreamInfoItem) item;
                        JSObject obj = new JSObject();
                        obj.put("title", stream.getName());
                        obj.put("url", stream.getUrl());
                        obj.put("duration", stream.getDuration());
                        String videoUrl = stream.getUrl();
                        String id = videoUrl.contains("v=")
                            ? videoUrl.substring(videoUrl.indexOf("v=") + 2).split("&")[0]
                            : "";
                        obj.put("id", id);
                        results.put(obj);
                    }
                }

                JSObject ret = new JSObject();
                ret.put("results", results);
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Search failed: " + e.getMessage(), e);
            }
        });
    }

    private String getBestStreamUrl(StreamInfo info) {
        List<AudioStream> audioStreams = info.getAudioStreams();
        if (audioStreams != null && !audioStreams.isEmpty()) {
            AudioStream best = audioStreams.get(0);
            for (AudioStream s : audioStreams) {
                if (s.getAverageBitrate() > best.getAverageBitrate()) {
                    best = s;
                }
            }
            if (best.getContent() != null && !best.getContent().isEmpty()) {
                return best.getContent();
            }
        }
        List<VideoStream> videoStreams = info.getVideoStreams();
        if (videoStreams != null && !videoStreams.isEmpty()) {
            VideoStream smallest = videoStreams.get(0);
            for (VideoStream s : videoStreams) {
                if (s.getResolution() != null && smallest.getResolution() != null
                    && s.getResolution().compareTo(smallest.getResolution()) < 0) {
                    smallest = s;
                }
            }
            if (smallest.getContent() != null && !smallest.getContent().isEmpty()) {
                return smallest.getContent();
            }
        }
        return null;
    }

    @PluginMethod
    public void getAudioUrl(PluginCall call) {
        String videoId = call.getString("videoId", "");
        if (videoId.isEmpty()) {
            call.reject("videoId is required");
            return;
        }

        executor.execute(() -> {
            try {
                String url = "https://www.youtube.com/watch?v=" + videoId;
                StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
                String streamUrl = getBestStreamUrl(info);
                if (streamUrl == null) {
                    int audioCount = info.getAudioStreams() != null ? info.getAudioStreams().size() : 0;
                    int videoCount = info.getVideoStreams() != null ? info.getVideoStreams().size() : 0;
                    call.reject("No streams found (audio: " + audioCount + ", video: " + videoCount + ")");
                    return;
                }

                JSObject ret = new JSObject();
                ret.put("streamUrl", streamUrl);
                ret.put("title", info.getName());
                ret.put("duration", info.getDuration());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Extract failed: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void download(PluginCall call) {
        String videoId = call.getString("videoId", "");
        String displayTitle = call.getString("title", "");
        if (videoId.isEmpty()) {
            call.reject("videoId is required");
            return;
        }

        executor.execute(() -> {
            try {
                String url = "https://www.youtube.com/watch?v=" + videoId;
                StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, url);
                String streamUrl = getBestStreamUrl(info);
                if (streamUrl == null) {
                    int audioCount = info.getAudioStreams() != null ? info.getAudioStreams().size() : 0;
                    int videoCount = info.getVideoStreams() != null ? info.getVideoStreams().size() : 0;
                    call.reject("No streams found (audio: " + audioCount + ", video: " + videoCount + ")");
                    return;
                }

                String title = displayTitle.isEmpty() ? info.getName() : displayTitle;
                String filename = title.replaceAll("[<>:\"/\\\\|?*]", "") + ".mp3";

                File musicDir = getMusicDir();
                File outFile = new File(musicDir, filename);

                OkHttpClient client = DownloaderImpl.getInstance().getClient();
                Request request = new Request.Builder().url(streamUrl).build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful()) {
                    call.reject("CDN download failed: HTTP " + response.code());
                    response.close();
                    return;
                }

                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }

                JSObject ret = new JSObject();
                ret.put("filename", filename);
                ret.put("title", title);
                ret.put("path", outFile.getAbsolutePath());
                call.resolve(ret);
            } catch (Exception e) {
                call.reject("Download failed: " + e.getMessage(), e);
            }
        });
    }
}
