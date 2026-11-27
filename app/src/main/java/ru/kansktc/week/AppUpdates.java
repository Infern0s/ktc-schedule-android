package ru.kansktc.week;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class AppUpdates {
    private static final String REPOSITORY = "Infern0s/ktc-schedule-android";
    private static final String LATEST = "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";
    private static final int MAX_APK_BYTES = 40 * 1024 * 1024;

    static final class Release {
        final String version;
        final String url;

        Release(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    static Release check(Context context) throws Exception {
        HttpURLConnection connection = open(LATEST, "application/vnd.github+json");
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream response = new ByteArrayOutputStream()) {
            copy(input, response, 512 * 1024);
            JSONObject release = new JSONObject(new String(response.toByteArray(), StandardCharsets.UTF_8));
            String tag = release.getString("tag_name");
            if (!tag.matches("v[0-9]{1,6}(\\.[0-9]{1,6}){1,3}")
                    || release.optBoolean("draft") || release.optBoolean("prerelease")) return null;
            String version = tag.substring(1);
            String installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            if (!isNewer(version, installed)) return null;
            JSONArray assets = release.getJSONArray("assets");
            String name = "ktc-schedule-" + tag + ".apk";
            String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + name;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (name.equals(asset.optString("name")) && expected.equals(asset.optString("browser_download_url"))
                        && "uploaded".equals(asset.optString("state"))) return new Release(version, expected);
            }
            throw new IOException("Release APK is missing");
        } finally {
            connection.disconnect();
        }
    }

    static boolean isNewer(String candidate, String installed) {
        String[] next = candidate.split("\\.");
        String[] old = installed.split("\\.");
        for (int i = 0; i < Math.max(next.length, old.length); i++) {
            int a = i < next.length ? Integer.parseInt(next[i]) : 0;
            int b = i < old.length ? Integer.parseInt(old[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    static File apk(Context context) {
        return new File(context.getCacheDir(), "ktc-update.apk");
    }

    static void download(Context context, Release release) throws Exception {
        File temporary = File.createTempFile("ktc-update-", ".apk", context.getCacheDir());
        try {
            HttpURLConnection connection = open(release.url, "application/vnd.android.package-archive");
            try (InputStream input = connection.getInputStream(); OutputStream output = new FileOutputStream(temporary)) {
                copy(input, output, MAX_APK_BYTES);
            } finally {
                connection.disconnect();
            }
            verify(context, temporary, release.version);
            if (!temporary.renameTo(apk(context))) throw new IOException("Failed to store update");
        } finally {
            temporary.delete();
        }
    }

    @SuppressWarnings("deprecation")
    static void verify(Context context, File file, String version) throws Exception {
        PackageManager manager = context.getPackageManager();
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        PackageInfo update = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (update == null || !installed.packageName.equals(update.packageName)
                || (version != null && !version.equals(update.versionName))
                || update.versionCode <= installed.versionCode
                || installed.signatures == null || installed.signatures.length == 0
                || !Arrays.equals(installed.signatures, update.signatures)) {
            throw new IOException("Update identity, version or signature did not match");
        }
    }

    private static HttpURLConnection open(String location, String accept) throws Exception {
        URL url = new URL(location);
        for (int i = 0; i < 5; i++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || (url.getPort() != -1 && url.getPort() != 443)
                    || !("api.github.com".equals(host) || "github.com".equals(host)
                    || "release-assets.githubusercontent.com".equals(host)
                    || "objects.githubusercontent.com".equals(host))) throw new IOException("Invalid download host");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("Accept", accept);
            connection.setRequestProperty("User-Agent", "KTC-Schedule-Android");
            int status = connection.getResponseCode();
            if (status == 200) return connection;
            String redirect = connection.getHeaderField("Location");
            connection.disconnect();
            if (status < 300 || status >= 400 || redirect == null) throw new IOException("GitHub HTTP " + status);
            url = new URL(url, redirect);
        }
        throw new IOException("Too many redirects");
    }

    private static void copy(InputStream input, OutputStream output, int limit) throws IOException {
        byte[] buffer = new byte[8192];
        int read, total = 0;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("Download exceeds size limit");
            output.write(buffer, 0, read);
        }
    }
}
