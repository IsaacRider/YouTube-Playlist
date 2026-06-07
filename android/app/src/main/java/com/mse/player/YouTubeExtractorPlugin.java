package com.mse.player;

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
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.File;
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

                List<AudioStream> audioStreams = info.getAudioStreams();
                if (audioStreams.isEmpty()) {
                    call.reject("No audio streams found");
                    return;
                }

                AudioStream best = audioStreams.get(0);
                for (AudioStream s : audioStreams) {
                    if (s.getAverageBitrate() > best.getAverageBitrate()) {
                        best = s;
                    }
                }

                JSObject ret = new JSObject();
                ret.put("streamUrl", best.getContent());
                ret.put("title", info.getName());
                ret.put("duration", info.getDuration());
                ret.put("bitrate", best.getAverageBitrate());
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

                List<AudioStream> audioStreams = info.getAudioStreams();
                if (audioStreams.isEmpty()) {
                    call.reject("No audio streams found");
                    return;
                }

                AudioStream best = audioStreams.get(0);
                for (AudioStream s : audioStreams) {
                    if (s.getAverageBitrate() > best.getAverageBitrate()) {
                        best = s;
                    }
                }

                String title = displayTitle.isEmpty() ? info.getName() : displayTitle;
                String filename = title.replaceAll("[<>:\"/\\\\|?*]", "") + ".mp3";
                String streamUrl = best.getContent();

                File musicDir = new File(getContext().getFilesDir(), "mse_music");
                if (!musicDir.exists()) musicDir.mkdirs();
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
