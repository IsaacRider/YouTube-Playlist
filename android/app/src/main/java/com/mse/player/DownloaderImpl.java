package com.mse.player;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;

import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;

public class DownloaderImpl extends Downloader {

    private static final String USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";

    private static DownloaderImpl instance;
    private final OkHttpClient client;

    private DownloaderImpl() {
        this.client = new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .build();
    }

    public static synchronized DownloaderImpl getInstance() {
        if (instance == null) {
            instance = new DownloaderImpl();
        }
        return instance;
    }

    public OkHttpClient getClient() {
        return client;
    }

    @Override
    public Response execute(@Nonnull Request request)
            throws IOException, ReCaptchaException {

        final String httpMethod = request.httpMethod();
        final String url = request.url();
        final Map<String, List<String>> headers = request.headers();
        final byte[] dataToSend = request.dataToSend();

        okhttp3.Request.Builder requestBuilder = new okhttp3.Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT);

        if (dataToSend != null) {
            requestBuilder.method(httpMethod, RequestBody.create(dataToSend));
        } else if ("POST".equals(httpMethod)) {
            requestBuilder.method(httpMethod, RequestBody.create(new byte[0]));
        }

        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        requestBuilder.addHeader(entry.getKey(), value);
                    }
                }
            }
        }

        okhttp3.Response response = client.newCall(requestBuilder.build()).execute();

        if (response.code() == 429) {
            response.close();
            throw new ReCaptchaException("Rate limited", url);
        }

        Map<String, List<String>> responseHeaders = new HashMap<>();
        for (String name : response.headers().names()) {
            responseHeaders.put(name, response.headers().values(name));
        }

        final ResponseBody body = response.body();
        String responseBody = (body != null) ? body.string() : "";

        return new Response(
            response.code(),
            response.message(),
            responseHeaders,
            responseBody,
            response.request().url().toString()
        );
    }
}
